package app.sotreus.core.data.pipeline

import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.data.live.LiveRadio
import app.sotreus.core.data.live.LiveSnapshot
import app.sotreus.core.data.repository.AttentionCodec
import app.sotreus.core.database.dao.AttentionDao
import app.sotreus.core.database.dao.EncounterDao
import app.sotreus.core.database.dao.EntityDao
import app.sotreus.core.database.dao.EntityLabelRow
import app.sotreus.core.database.dao.EntitySystemFields
import app.sotreus.core.database.dao.ObservationDao
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.AttentionEventEntity
import app.sotreus.core.database.entity.EncounterEntity
import app.sotreus.core.database.entity.EntityEntity
import app.sotreus.core.database.entity.ObservationEntity
import app.sotreus.core.database.entity.PlaceEntity
import app.sotreus.core.database.entity.SessionEntity
import app.sotreus.core.database.entity.SessionEntityEntity
import app.sotreus.core.database.entity.SessionEventEntity
import app.sotreus.core.database.entity.VisitEntityEntity
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.PresenceState
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.SessionEventKind
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.model.UserEntityState
import app.sotreus.intelligence.AttentionEngine
import app.sotreus.intelligence.Classification
import app.sotreus.intelligence.Fingerprint
import app.sotreus.intelligence.PlusCode
import app.sotreus.intelligence.SignatureClassifier
import app.sotreus.intelligence.fieldwatch.Observation
import app.sotreus.intelligence.fieldwatch.Sighting
import app.sotreus.sensing.RadioAccess
import app.sotreus.sensing.RadioStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import app.sotreus.intelligence.fieldwatch.RadioKind as FwKind

/**
 * Observation → Room pipeline (architecture handoff §11):
 *
 *     radio result → live sighting → classification → entity + sampled evidence
 *                  → encounter / place visit / session presence → attention → Now snapshot
 *
 * [ingest] is cheap and called for every packet. [tick] runs once a second from the
 * [ObservationController]; it publishes the Now snapshot every tick, writes to Room every
 * two seconds and runs the attention engine every fifteen.
 */
@Singleton
class ObservationPipeline @Inject constructor(
    private val entities: EntityDao,
    private val observations: ObservationDao,
    private val encounters: EncounterDao,
    private val places: PlaceDao,
    private val sessions: SessionDao,
    private val attention: AttentionDao,
    private val classifier: SignatureClassifier,
    private val placeTracker: PlaceTracker,
    private val location: PhoneLocation,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val mutex = Mutex()
    private val sightings = LiveSightings()
    private val simulatedIds = HashSet<String>()
    private val trails = HashMap<String, ArrayDeque<Pair<Long, Int>>>()
    private val dirty = LinkedHashSet<String>()

    private val classified = HashMap<String, Pair<String, Classification>>()
    private val rows = HashMap<String, EntityEntity>()
    private val lastObservationWrite = HashMap<String, Long>()
    private val openEncounters = HashMap<String, OpenEncounter>()
    private val visitFirstSeen = HashMap<String, Long>()
    private val windowPresence = HashMap<String, Long>()
    private val lastPresenceTick = HashMap<String, Long>()
    private val sessionRows = HashMap<String, SessionEntityEntity>()
    private val sessionEventKeys = HashSet<String>()
    private val attentionIds = HashMap<String, Long>()

    @Volatile private var labels: Map<String, EntityLabelRow> = emptyMap()
    @Volatile private var placeNames: Map<String, Set<String>> = emptyMap()
    @Volatile private var activeSession: SessionEntity? = null
    @Volatile private var currentPlace: PlaceEntity? = null
    @Volatile private var baseline: PlaceTracker.Baseline? = null
    @Volatile private var tagPlusCodes = false
    @Volatile private var placeByLocation = false
    private var baselineLoadedAt = 0L
    private var windowStartMs = 0L
    private var runStartMs = 0L
    private var tickCount = 0L
    private var lastNewFingerprintEvent = 0L
    private var lastWifiDelayEvent = 0L
    private var wifiRejected = false
    private var boundSessionId: Long? = null

    private val _snapshot = MutableStateFlow(LiveSnapshot())
    val snapshot: StateFlow<LiveSnapshot> = _snapshot.asStateFlow()

    private data class OpenEncounter(
        val id: Long,
        val placeId: Long?,
        val sessionId: Long?,
        var lastMs: Long,
        var persistedEndMs: Long,
        var maxRssi: Int,
    )

    init {
        scope.launch { entities.observeLabeled().collect { list -> labels = list.associateBy { it.id } } }
        scope.launch {
            encounters.observePlaceNames().collect { list ->
                placeNames = list.groupBy({ it.entityId }, { it.name }).mapValues { it.value.toSet() }
            }
        }
        scope.launch { sessions.observeActive().collect { activeSession = it } }
    }

    /** Called for every advertisement / AP result. */
    fun ingest(observation: Observation, simulated: Boolean) {
        val kind = if (observation.kind == FwKind.WIFI) RadioKind.WIFI else RadioKind.BLE
        val key = (if (simulated) SIMULATED_PREFIX else "") + Fingerprint.key(kind, observation.mac)
        synchronized(lock) {
            val s = sightings.upsert(key, observation)
            if (simulated) simulatedIds += key
            if (observation.kind == FwKind.BLE || observation.fresh) {
                val trail = trails.getOrPut(key) { ArrayDeque() }
                val last = trail.peekLast()
                if (last == null || s.lastSeen - last.first >= TRAIL_STEP_MS) trail.addLast(s.lastSeen to observation.rssi)
                while (trail.isNotEmpty() && s.lastSeen - trail.first.first > TRAIL_WINDOW_MS) trail.removeFirst()
                dirty += key
            }
        }
    }

    /** Last 60 s of RSSI for the proximity check: (wall ms, dBm). */
    fun trail(entityId: String): List<Pair<Long, Int>> = synchronized(lock) { trails[entityId]?.toList().orEmpty() }

    fun lastHeard(entityId: String): Long? = synchronized(lock) { sightings.live[entityId]?.lastSeen }

    /** The live Fieldwatch sighting for detail screens (radio metadata). */
    fun sighting(entityId: String): Sighting? = synchronized(lock) { sightings.live[entityId] }

    suspend fun onStart(now: Long) = mutex.withLock {
        runStartMs = now
        windowStartMs = activeSession?.startedAtMs ?: now
        windowPresence.clear()
        lastPresenceTick.clear()
        attentionIds.clear()
    }

    suspend fun onStop(now: Long) = mutex.withLock {
        flushLocked(now)
        placeTracker.touch(now)
        openEncounters.clear()
    }

    /** Selected place changed (or the pipeline is starting): load its name and baseline. */
    suspend fun setPlace(place: PlaceEntity?, now: Long) = mutex.withLock {
        if (place?.id == currentPlace?.id && baseline != null) return@withLock
        flushLocked(now)
        currentPlace = place
        visitFirstSeen.clear()
        attentionIds.clear()
        placeTracker.ensure(place?.id, now)
        baseline = place?.let { placeTracker.baseline(it.id) }
        baselineLoadedAt = now
    }

    suspend fun tick(now: Long, status: RadioStatus, access: RadioAccess?, settings: SotreusSettings, observing: Boolean) =
        mutex.withLock {
            tickCount++
            tagPlusCodes = settings.plusCodeTags
            placeByLocation = settings.currentPlaceByLocation
            bindSession(now)
            if (tickCount % 2 == 0L) flushLocked(now)
            if (tickCount % 15 == 0L) assessAttention(now, settings)
            if (now - baselineLoadedAt > BASELINE_REFRESH_MS) {
                currentPlace?.let { baseline = placeTracker.baseline(it.id) }
                baselineLoadedAt = now
            }
            noteWifiDelay(now, status)
            publish(now, status, access, settings, observing)
        }

    // --- Writing --------------------------------------------------------------------------

    private suspend fun flushLocked(now: Long) {
        val batch: List<Sighting>
        synchronized(lock) {
            batch = dirty.mapNotNull { sightings.live[it] }
            dirty.clear()
            sightings.evictOlderThan(now - EVICT_MS)
            trails.keys.retainAll(sightings.live.keys)
        }
        if (batch.isEmpty()) return

        val toClassify = batch.filter { stamp(it) != classified[it.key]?.first }
        if (toClassify.isNotEmpty()) {
            classifier.classify(toClassify, now).forEach { (key, c) ->
                toClassify.firstOrNull { it.key == key }?.let { classified[key] = stamp(it) to c }
            }
        }
        val unknown = batch.map { it.key }.filter { it !in rows }
        if (unknown.isNotEmpty()) unknown.chunked(500).forEach { chunk -> entities.getAll(chunk).forEach { rows[it.id] = it } }

        val placeId = currentPlace?.id
        val session = activeSession
        val visitId = placeTracker.ensure(placeId, now)
        // Opt-in: where the phone was, as a ~14 m plus code, only from a fix taken in the last minute.
        val plusCode = if (tagPlusCodes) location.fresh(now)?.let { PlusCode.encode(it.lat, it.lon) } else null
        val brandNew = mutableListOf<EntityEntity>()
        val updates = mutableListOf<EntitySystemFields>()
        val evidence = mutableListOf<ObservationEntity>()

        for (s in batch) {
            val existing = rows[s.key]
            val kind = if (s.kind == FwKind.WIFI) RadioKind.WIFI else RadioKind.BLE
            val sampleEvery = if (kind == RadioKind.WIFI) WIFI_SAMPLE_MS else BLE_SAMPLE_MS
            val write = s.lastSeen - (lastObservationWrite[s.key] ?: 0L) >= sampleEvery
            val row = toEntity(s, kind, classified[s.key]?.second, existing, (existing?.observationCount ?: 0) + if (write) 1 else 0)
            if (existing == null) brandNew += row else updates += row.systemFields()
            rows[s.key] = row
            if (write) {
                lastObservationWrite[s.key] = s.lastSeen
                evidence += ObservationEntity(
                    entityId = s.key,
                    observedAtMs = s.lastSeen,
                    rssi = s.rssi,
                    radio = kind,
                    placeId = placeId,
                    sessionId = session?.id,
                    lat = null,
                    lon = null,
                    plusCode = plusCode,
                    rawHex = s.rawHex.take(RAW_HEX_LIMIT).ifBlank { null },
                )
            }
        }
        if (brandNew.isNotEmpty()) entities.insertIgnore(brandNew)
        if (updates.isNotEmpty()) entities.updateSystem(updates)
        if (evidence.isNotEmpty()) {
            session?.takeIf { it.geotag }?.let { sessions.lastLocation(it.id) }?.let { fix ->
                evidence.replaceAll { it.copy(lat = fix.lat, lon = fix.lon) }
            }
            observations.insert(evidence)
        }

        batch.forEach { recordEncounter(it, placeId, session?.id) }
        accumulatePresence(batch, now)
        if (visitId != null) {
            places.upsertVisitEntities(
                batch.map { s ->
                    VisitEntityEntity(visitId, s.key, visitFirstSeen.getOrPut(s.key) { s.lastSeen }, s.lastSeen)
                },
            )
        }
        if (session != null) recordSession(session, batch, brandNew.map { it.id }.toSet(), now)
    }

    private fun stamp(s: Sighting): String =
        "${s.name}|${s.serviceUuids.joinToString(",")}|${s.manufacturerId}|${s.manufacturerDataHex.take(8)}|${s.vendorIeOuis.joinToString(",")}"

    private fun toEntity(s: Sighting, kind: RadioKind, c: Classification?, existing: EntityEntity?, count: Int): EntityEntity {
        val address = s.mac
        val type = Fingerprint.addressType(kind, s.facts.addressType, address)
        return EntityEntity(
            id = s.key,
            radio = kind,
            address = address,
            addressType = type,
            linkConfidence = Fingerprint.linkConfidence(kind, type, address),
            advertisedName = s.name.trim().ifBlank { null },
            userName = existing?.userName,
            userState = existing?.userState ?: UserEntityState.UNCLASSIFIED,
            note = existing?.note,
            family = c?.family ?: existing?.family,
            signatureName = c?.signatureName ?: existing?.signatureName,
            guess = c?.guess?.ifBlank { null } ?: existing?.guess,
            guessConfidence = c?.guessConfidence ?: existing?.guessConfidence ?: Confidence.LOW,
            notable = c?.notable ?: existing?.notable ?: false,
            vendor = s.vendor,
            companyId = s.manufacturerId,
            serviceUuids = s.serviceUuids.joinToString(","),
            serviceData = s.facts.serviceData.joinToString(",") { "${it.uuid}:${it.dataHex.length / 2}" }.ifBlank { null },
            connectable = s.facts.connectable,
            txPower = s.facts.txPowerDbm,
            security = s.facts.security ?: s.facts.capabilities,
            frequencyMhz = s.frequencyMhz.takeIf { kind == RadioKind.WIFI },
            channel = s.channel.takeIf { kind == RadioKind.WIFI && it > 0 },
            wifiStandard = s.facts.wifiStandard,
            firstSeenMs = existing?.firstSeenMs ?: s.firstSeen,
            lastSeenMs = s.lastSeen,
            lastRssi = s.rssi,
            observationCount = count,
        )
    }

    private fun EntityEntity.systemFields() = EntitySystemFields(
        id, advertisedName, family, signatureName, guess, guessConfidence, notable, vendor, companyId, serviceUuids,
        serviceData, connectable, txPower, security, frequencyMhz, channel, wifiStandard, lastSeenMs, lastRssi, observationCount,
    )

    private suspend fun recordEncounter(s: Sighting, placeId: Long?, sessionId: Long?) {
        val open = openEncounters[s.key]
        if (open != null && open.placeId == placeId && open.sessionId == sessionId && s.lastSeen - open.lastMs < ENCOUNTER_GAP_MS) {
            open.lastMs = s.lastSeen
            open.maxRssi = maxOf(open.maxRssi, s.rssi)
            if (s.lastSeen - open.persistedEndMs >= ENCOUNTER_PERSIST_MS) {
                encounters.extend(open.id, s.lastSeen, open.maxRssi)
                open.persistedEndMs = s.lastSeen
            }
            return
        }
        val id = encounters.insert(
            EncounterEntity(entityId = s.key, placeId = placeId, sessionId = sessionId, startedAtMs = s.lastSeen, endedAtMs = s.lastSeen, maxRssi = s.rssi),
        )
        openEncounters[s.key] = OpenEncounter(id, placeId, sessionId, s.lastSeen, s.lastSeen, s.rssi)
    }

    private fun accumulatePresence(batch: List<Sighting>, now: Long) {
        batch.forEach { s ->
            val prev = lastPresenceTick[s.key]
            if (prev != null) windowPresence[s.key] = (windowPresence[s.key] ?: 0L) + minOf(now - prev, PRESENCE_STEP_CAP_MS)
            lastPresenceTick[s.key] = now
        }
    }

    // --- Sessions -------------------------------------------------------------------------

    private suspend fun bindSession(now: Long) {
        val session = activeSession
        if (session?.id == boundSessionId) return
        boundSessionId = session?.id
        sessionRows.clear()
        sessionEventKeys.clear()
        windowPresence.clear()
        lastPresenceTick.clear()
        attentionIds.clear()
        windowStartMs = session?.startedAtMs ?: now
        if (session != null) sessions.entities(session.id).forEach { sessionRows[it.entityId] = it }
    }

    private suspend fun recordSession(session: SessionEntity, batch: List<Sighting>, created: Set<String>, now: Long) {
        val updated = batch.map { s ->
            val prev = sessionRows[s.key]
            val firstSeenOverall = rows[s.key]?.firstSeenMs ?: s.firstSeen
            val row = if (prev == null) {
                SessionEntityEntity(session.id, s.key, s.lastSeen, s.lastSeen, 0, s.rssi.toLong(), 1, newToYou = firstSeenOverall >= session.startedAtMs)
            } else {
                prev.copy(
                    lastSeenMs = s.lastSeen,
                    presentMs = prev.presentMs + minOf(s.lastSeen - prev.lastSeenMs, PRESENCE_STEP_CAP_MS).coerceAtLeast(0),
                    rssiSum = prev.rssiSum + s.rssi,
                    rssiCount = prev.rssiCount + 1,
                )
            }
            sessionRows[s.key] = row
            row
        }
        sessions.upsertEntities(updated)

        batch.forEach { s ->
            val label = labels[s.key]
            val c = classified[s.key]?.second
            val name = label?.userName ?: s.name.ifBlank { null } ?: c?.signatureName
            if (label != null && (label.userState == UserEntityState.TAGGED || label.userState == UserEntityState.WATCH)) {
                event(session.id, now, SessionEventKind.TAGGED_REENCOUNTER, s.key, name)
            } else if (c != null && (c.notable || c.family?.attentionRelevant == true)) {
                event(session.id, now, SessionEventKind.FAMILY_SIGNATURE, s.key, c.family?.name)
            } else if (s.key in created && (c?.family != null || s.kind == FwKind.WIFI) && now - lastNewFingerprintEvent > NEW_EVENT_SPACING_MS) {
                if (event(session.id, now, SessionEventKind.NEW_FINGERPRINT, s.key, name)) lastNewFingerprintEvent = now
            }
        }
    }

    /** Adds a timeline event once per (kind, entity) per session. */
    private suspend fun event(sessionId: Long, now: Long, kind: SessionEventKind, entityId: String?, text: String?): Boolean {
        if (!sessionEventKeys.add("$kind:$entityId")) return false
        sessions.insertEvent(SessionEventEntity(sessionId = sessionId, atMs = now, kind = kind, entityId = entityId, text = text))
        return true
    }

    private suspend fun noteWifiDelay(now: Long, status: RadioStatus) {
        val rejected = status.running && status.wifiLastRequestRejected
        val session = activeSession
        if (rejected && !wifiRejected && session != null && now - lastWifiDelayEvent > WIFI_EVENT_SPACING_MS) {
            sessions.insertEvent(SessionEventEntity(sessionId = session.id, atMs = now, kind = SessionEventKind.WIFI_SCAN_DELAYED))
            lastWifiDelayEvent = now
        }
        wifiRejected = rejected
    }

    // --- Attention ------------------------------------------------------------------------

    private suspend fun assessAttention(now: Long, settings: SotreusSettings) {
        val live = synchronized(lock) { sightings.live.values.toList() }
        val windowMinutes = (now - windowStartMs) / 60_000.0
        val placeName = currentPlace?.name
        val session = activeSession
        val visitId = placeTracker.currentVisitId
        live.forEach { s ->
            val ageSeconds = (now - s.lastSeen) / 1000.0
            if (ageSeconds > settings.staleHoldSeconds) return@forEach
            val label = labels[s.key]
            val c = classified[s.key]?.second
            val presence = presenceOf(s.key, label)
            val others = placeNames[s.key].orEmpty().filter { it != placeName }
            val assessment = AttentionEngine.assess(
                AttentionEngine.Context(
                    userState = label?.userState ?: UserEntityState.UNCLASSIFIED,
                    family = c?.family,
                    familyConfidence = c?.guessConfidence ?: Confidence.LOW,
                    notableSignature = c?.notable == true,
                    signatureName = c?.signatureName,
                    otherPlaces = others,
                    presentMinutes = (windowPresence[s.key] ?: 0L) / 60_000.0,
                    windowMinutes = windowMinutes,
                    newToPlace = presence == PresenceState.NEW || presence == PresenceState.SEEN_ELSEWHERE,
                    lastHeardAgeSeconds = ageSeconds,
                    staleAfterSeconds = settings.staleHoldSeconds.toDouble(),
                    displayName = label?.userName ?: s.name.ifBlank { c?.signatureName ?: "" },
                ),
            ) ?: return@forEach
            if (!assessment.needsAttention) return@forEach
            val reasons = AttentionCodec.encodeReasons(assessment.reasons)
            val inputs = AttentionCodec.encodeInputs(assessment.inputs)
            val known = attentionIds[s.key]?.let { id -> attention.findOpenFor(s.key, visitId, session?.id, now - REPEAT_WINDOW_MS)?.takeIf { it.id == id } }
                ?: attention.findOpenFor(s.key, visitId, session?.id, now - REPEAT_WINDOW_MS)
            if (known != null) {
                attentionIds[s.key] = known.id
                if (assessment.score > known.score + 0.02f || known.reasonsJson != reasons) {
                    attention.update(known.copy(updatedAtMs = now, score = maxOf(known.score, assessment.score), headline = assessment.headline, reasonsJson = reasons, inputsJson = inputs))
                }
            } else {
                val id = attention.insert(
                    AttentionEventEntity(
                        entityId = s.key, createdAtMs = now, updatedAtMs = now, placeId = currentPlace?.id, sessionId = session?.id,
                        visitId = visitId, score = assessment.score, headline = assessment.headline, reasonsJson = reasons, inputsJson = inputs,
                    ),
                )
                attentionIds[s.key] = id
                if (session != null) event(session.id, now, SessionEventKind.ATTENTION, s.key, assessment.headline.name)
            }
        }
    }

    // --- Snapshot -------------------------------------------------------------------------

    private fun presenceOf(id: String, label: EntityLabelRow?): PresenceState {
        val state = label?.userState
        if (state == UserEntityState.MINE || state == UserEntityState.EXPECTED) return PresenceState.FAMILIAR
        val place = currentPlace
        val elsewhere = placeNames[id].orEmpty().any { it != place?.name }
        if (place == null) {
            val first = rows[id]?.firstSeenMs ?: return PresenceState.UNKNOWN
            return when {
                first < runStartMs - PlaceTracker.CONTINUE_MS -> PresenceState.FAMILIAR
                elsewhere -> PresenceState.SEEN_ELSEWHERE
                else -> PresenceState.NEW
            }
        }
        val earlier = baseline?.visitsByEntity?.get(id) ?: 0
        return when {
            earlier > 0 -> PresenceState.FAMILIAR
            elsewhere -> PresenceState.SEEN_ELSEWHERE
            else -> PresenceState.NEW
        }
    }

    private fun publish(now: Long, status: RadioStatus, access: RadioAccess?, settings: SotreusSettings, observing: Boolean) {
        val staleBleMs = settings.staleHoldSeconds * 1000L
        val wifiWindow = status.wifiScanIntervalMs * 2 + 5_000
        val list = synchronized(lock) { sightings.live.values.toList() }
        val radios = list.mapNotNull { s ->
            val kind = if (s.kind == FwKind.WIFI) RadioKind.WIFI else RadioKind.BLE
            val age = now - s.lastSeen
            if (age > if (kind == RadioKind.BLE) BLE_DROP_MS else WIFI_DROP_MS) return@mapNotNull null
            val stale = if (kind == RadioKind.BLE) {
                age > staleBleMs
            } else {
                age > wifiWindow || (status.wifiLastFreshMs > 0 && s.lastSeen < status.wifiLastFreshMs - 5_000)
            }
            val label = labels[s.key]
            val c = classified[s.key]?.second
            val row = rows[s.key]
            LiveRadio(
                entityId = s.key,
                kind = kind,
                address = s.mac,
                advertisedName = s.name.trim().ifBlank { null },
                userName = label?.userName,
                family = c?.family ?: row?.family,
                signatureName = c?.signatureName,
                guess = c?.guess ?: row?.guess,
                userState = label?.userState ?: UserEntityState.UNCLASSIFIED,
                presence = presenceOf(s.key, label),
                otherPlaces = placeNames[s.key].orEmpty().count { it != currentPlace?.name },
                avgRssi30 = s.averageRssi(30_000, now),
                lastRssi = s.rssi,
                lastHeardMs = s.lastSeen,
                stale = stale,
                security = s.facts.security ?: s.facts.capabilities,
                frequencyMhz = s.frequencyMhz.takeIf { kind == RadioKind.WIFI },
                randomAddress = s.randomized,
                needsAttention = s.key in attentionIds,
                simulated = s.key in simulatedIds,
            )
        }
        _snapshot.value = LiveSnapshot(
            atMs = now,
            observing = observing,
            simulated = status.simulated,
            radios = radios,
            status = status,
            access = access,
            placeId = currentPlace?.id,
            placeName = currentPlace?.name,
            // While a fix is pending the earlier place is unconfirmed, so it isn't shown as picked by location.
            placeByLocation = placeByLocation && currentPlace != null && !location.locating.value,
            locating = location.locating.value,
            lastFixMs = location.latest.value?.atMs,
            baselineVisits = baseline?.completedVisits ?: 0,
            activeSessionId = activeSession?.id,
        )
    }

    /** Forget simulated radios (when the user turns simulation off). */
    fun clearSimulated() = synchronized(lock) {
        simulatedIds.forEach { sightings.live.remove(it); trails.remove(it); dirty.remove(it) }
        simulatedIds.clear()
    }

    /** Forget cached rows after the user deletes data. */
    suspend fun forgetAll() = mutex.withLock {
        synchronized(lock) {
            sightings.live.clear()
            trails.clear()
            dirty.clear()
        }
        rows.clear()
        classified.clear()
        openEncounters.clear()
        visitFirstSeen.clear()
        attentionIds.clear()
        sessionRows.clear()
        sessionEventKeys.clear()
        lastObservationWrite.clear()
    }

    companion object {
        const val SIMULATED_PREFIX = "sim:"
        private const val TRAIL_STEP_MS = 250L
        private const val TRAIL_WINDOW_MS = 60_000L
        private const val EVICT_MS = 15 * 60_000L
        private const val BLE_DROP_MS = 2 * 60_000L
        private const val WIFI_DROP_MS = 5 * 60_000L
        private const val BLE_SAMPLE_MS = 15_000L
        private const val WIFI_SAMPLE_MS = 20_000L
        private const val RAW_HEX_LIMIT = 256
        private const val ENCOUNTER_GAP_MS = 5 * 60_000L
        private const val ENCOUNTER_PERSIST_MS = 30_000L
        private const val PRESENCE_STEP_CAP_MS = 10_000L
        private const val NEW_EVENT_SPACING_MS = 30_000L
        private const val WIFI_EVENT_SPACING_MS = 2 * 60_000L
        private const val REPEAT_WINDOW_MS = 6 * 60 * 60_000L
        private const val BASELINE_REFRESH_MS = 5 * 60_000L

        fun isSimulated(entityId: String) = entityId.startsWith(SIMULATED_PREFIX)
    }
}
