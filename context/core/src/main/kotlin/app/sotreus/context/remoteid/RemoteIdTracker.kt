package app.sotreus.context.remoteid

import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.ContextDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.ContextEventEntity
import app.sotreus.core.database.entity.SessionEventEntity
import app.sotreus.core.model.ContextKind
import app.sotreus.core.model.Provenance
import app.sotreus.core.model.SessionEventKind
import app.sotreus.intelligence.fieldwatch.FrenchRid
import app.sotreus.intelligence.fieldwatch.Observation
import app.sotreus.intelligence.fieldwatch.OpenDroneId
import app.sotreus.intelligence.fieldwatch.PayloadLocation
import app.sotreus.intelligence.fieldwatch.RadioKind
import app.sotreus.intelligence.fieldwatch.Rssi
import app.sotreus.sensing.RadioHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** A Remote ID broadcast heard by this phone (SENSED). Positions are what the aircraft broadcast. */
data class LiveRemoteId(
    /** UAS ID when broadcast, else the radio address (BLE addresses rotate). */
    val subjectId: String,
    val uasId: String?,
    val maker: String?,
    val selfId: String?,
    val lat: Double?,
    val lon: Double?,
    val altM: Double?,
    val operatorLat: Double?,
    val operatorLon: Double?,
    val operatorKind: Int? = null,
    val operatorAltM: Double? = null,
    val heightM: Double? = null,
    val heightReference: Int? = null,
    val euCategory: String? = null,
    val euClass: String? = null,
    val speedMps: Double?,
    val courseDeg: Double?,
    val radio: RadioKind,
    val address: String,
    val rssi: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** ASTM operational status broadcast: 0 undeclared, 1 ground, 2 airborne, 3 emergency, 4 Remote ID system failure. */
    val status: Int? = null,
    /** ASTM authentication type the aircraft claims. The signature is never checked. */
    val authType: Int? = null,
    /** While it declares an emergency; null otherwise. */
    val emergency: RemoteIdEmergency? = null,
    /** The words of its emergency self-ID; empty when it sent none. */
    val emergencyText: String? = null,
    /** It broadcasts the French Direct Remote ID plate. */
    val french: Boolean = false,
)

/**
 * An emergency an aircraft declares in its own broadcast (operational status or self-ID). The messages
 * take turns, so it holds for [HOLD_MS] after the last one that said so.
 */
data class RemoteIdEmergency(val sinceMs: Long, val lastMs: Long) {
    companion object {
        const val HOLD_MS = 10_000L

        fun next(prev: RemoteIdEmergency?, declared: Boolean, atMs: Long): RemoteIdEmergency? {
            val held = prev?.takeIf { atMs - it.lastMs <= HOLD_MS }
            return if (declared) RemoteIdEmergency(held?.sinceMs ?: atMs, atMs) else held
        }

        /** Operational status 3, or a self-ID of the emergency kind. */
        fun declared(p: PayloadLocation): Boolean = p.status == OpenDroneId.STATUS_EMERGENCY || p.selfKind == OpenDroneId.SELF_EMERGENCY
    }
}

/**
 * Decodes ASTM F3411 Remote ID (BLE service data FFFA, Wi-Fi beacon vendor IE FA:0B:BC type 0x0D) and
 * the French Direct Remote ID plate from the phone's own radio observations with the ported Fieldwatch
 * [OpenDroneId] decoder.
 * Nothing is sent anywhere. Broadcast tracks are kept as SENSED context records.
 */
@Singleton
class RemoteIdTracker @Inject constructor(
    private val radios: RadioHub,
    private val edge: app.sotreus.core.data.edge.EdgeHub,
    private val settings: SettingsRepository,
    private val dao: ContextDao,
    private val sessions: SessionDao,
) {
    private val mutex = Mutex()
    private val catalog = app.sotreus.intelligence.StockCatalog.fleets.filter { it.id == REMOTE_ID_FLEET }

    private val byAddress = HashMap<String, PayloadLocation>()
    private val drones = LinkedHashMap<String, LiveRemoteId>()
    private val lastWrite = HashMap<String, Pair<Long, Long>>() // subject → (written at, row id)
    private val _live = MutableStateFlow<List<LiveRemoteId>>(emptyList())
    val live: StateFlow<List<LiveRemoteId>> = _live.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        val activeSession = sessions.observeActive().map { it?.id }.stateIn(scope, SharingStarted.Eagerly, null)
        scope.launch {
            settings.settings.map { it.contextEnabled && it.remoteIdEnabled }.distinctUntilChanged()
                // V2: with an Edge as the sensor, Remote ID comes from the Edge (BLE service data and Wi-Fi beacon elements).
                .flatMapLatest { on -> if (on) kotlinx.coroutines.flow.merge(radios.observations, edge.observations) else emptyFlow<Observation>().also { clear() } }
                .collect { obs -> onObservation(obs, activeSession.value) }
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                prune(System.currentTimeMillis())
            }
        }
    }

    private suspend fun clear() = mutex.withLock {
        byAddress.clear()
        drones.clear()
        _live.value = emptyList()
    }

    private suspend fun prune(now: Long) = mutex.withLock {
        val before = drones.size
        drones.values.removeAll { now - it.lastSeenMs > STALE_MS }
        if (drones.size != before) _live.value = drones.values.sortedByDescending { it.lastSeenMs }
        byAddress.keys.retainAll(drones.values.map { it.address }.toSet())
    }

    internal suspend fun onObservation(obs: Observation, sessionId: Long?) {
        val decoded = OpenDroneId.fromFacts(obs.facts)
        if (decoded == PayloadLocation()) return
        val plate = FrenchRid.fromFacts(obs.facts)
        var started: LiveRemoteId? = null
        val record = mutex.withLock {
            val merged = decoded.mergeSticky(byAddress[obs.mac])
            byAddress[obs.mac] = merged
            val subject = merged.uasId ?: obs.mac
            // The address-keyed entry becomes the UAS ID one, with what it said so far.
            val unnamed = if (subject != obs.mac) drones.remove(obs.mac) else null
            val prev = drones[subject] ?: unnamed
            val sighting = app.sotreus.intelligence.fieldwatch.observationSighting(obs).copy(fleetIds = setOf(REMOTE_ID_FLEET))
            val fields = app.sotreus.intelligence.fieldwatch.SignatureFieldDecoder.decodeSighting(sighting, catalog)
            fun number(id: String) = fields.firstOrNull { it.id == id }?.number
            fun label(id: String) = fields.firstOrNull { it.id == id }?.display

            val pin = merged.pin()
            val emergency = RemoteIdEmergency.next(prev?.emergency, RemoteIdEmergency.declared(decoded), obs.at)
            val next = LiveRemoteId(
                subjectId = subject, uasId = merged.uasId, maker = merged.aircraft, selfId = merged.selfId,
                lat = pin?.first, lon = pin?.second, altM = merged.alt,
                operatorLat = merged.opLat, operatorLon = merged.opLon,
                operatorKind = merged.opKind, operatorAltM = merged.opAlt,
                heightM = number("height")?.takeIf { it > -1000 } ?: plate.heightM?.toDouble() ?: prev?.heightM,
                // The French plate gives its height above takeoff (0), ASTM says which.
                heightReference = number("height_ref")?.toInt() ?: plate.heightM?.let { 0 } ?: prev?.heightReference,
                euCategory = if (decoded.opKind != null) label("eu_category") else prev?.euCategory,
                euClass = if (decoded.opKind != null) label("eu_class") else prev?.euClass,
                speedMps = merged.speedMps, courseDeg = merged.headingDeg,
                radio = obs.kind, address = obs.mac, rssi = if (Rssi.measured(obs.rssi)) obs.rssi else prev?.rssi ?: obs.rssi,
                firstSeenMs = prev?.firstSeenMs ?: obs.at, lastSeenMs = obs.at,
                status = decoded.status ?: prev?.status,
                authType = merged.auth?.type ?: prev?.authType,
                emergency = emergency,
                emergencyText = if (decoded.selfKind == OpenDroneId.SELF_EMERGENCY) decoded.selfId.orEmpty() else prev?.emergencyText?.takeIf { emergency != null },
                french = plate.present || prev?.french == true,
            )
            val flipped = (prev?.emergency == null) != (next.emergency == null)
            if (flipped && next.emergency != null) started = next
            drones[subject] = next
            _live.value = drones.values.sortedByDescending { it.lastSeenMs }
            val last = lastWrite[subject]
            val moved = prev == null || prev.lat != next.lat || prev.lon != next.lon || prev.altM != next.altM
            when {
                last == null || flipped || (moved && obs.at - last.first >= TRACK_INTERVAL_MS) -> Write.Insert(next)
                obs.at - last.first >= TRACK_INTERVAL_MS -> Write.Extend(last.second, next)
                else -> null
            }
        }
        started?.let { d -> if (sessionId != null) sessions.insertEvent(emergencyNote(d, sessionId)) }
        if (record == null) return
        when (record) {
            is Write.Insert -> {
                val id = dao.insert(record.d.toEntity(sessionId, radios.status.value.simulated))
                mutex.withLock { lastWrite[record.d.subjectId] = record.d.lastSeenMs to id }
            }
            is Write.Extend -> {
                val row = dao.latest(ContextKind.REMOTE_ID, record.d.subjectId)
                if (row != null && row.id == record.rowId) dao.update(row.copy(endMs = record.d.lastSeenMs))
                mutex.withLock { lastWrite[record.d.subjectId] = record.d.lastSeenMs to record.rowId }
            }
        }
    }

    /** The session's timeline notes what the aircraft broadcast. */
    private fun emergencyNote(d: LiveRemoteId, sessionId: Long) = SessionEventEntity(
        sessionId = sessionId, atMs = d.lastSeenMs, kind = SessionEventKind.SYSTEM_NOTE,
        text = "Remote ID " + listOfNotNull(d.maker, d.uasId).ifEmpty { listOf("aircraft") }.first() + " declared an emergency",
    )

    private sealed interface Write {
        val d: LiveRemoteId
        data class Insert(override val d: LiveRemoteId) : Write
        data class Extend(val rowId: Long, override val d: LiveRemoteId) : Write
    }

    private fun LiveRemoteId.toEntity(sessionId: Long?, simulated: Boolean) = ContextEventEntity(
        kind = ContextKind.REMOTE_ID,
        provenance = Provenance.SENSED,
        source = when {
            simulated -> ContextDao.SOURCE_SIMULATED
            edge.running && radio == RadioKind.WIFI -> SOURCE_EDGE_WIFI
            edge.running -> SOURCE_EDGE_BLE
            radio == RadioKind.WIFI -> SOURCE_WIFI
            else -> SOURCE_BLE
        },
        subjectId = subjectId,
        title = maker ?: selfId ?: uasId ?: "",
        atMs = lastSeenMs,
        lat = lat, lon = lon, altM = altM, speedMps = speedMps, courseDeg = courseDeg,
        operatorLat = operatorLat, operatorLon = operatorLon,
        operatorKind = operatorKind, operatorAltM = operatorAltM, heightM = heightM,
        heightReference = heightReference, euCategory = euCategory, euClass = euClass,
        status = status, authType = authType, emergencyText = emergencyText?.takeIf { emergency != null },
        sessionId = sessionId,
    )

    companion object {
        private const val REMOTE_ID_FLEET = "fleet-remote-id"
        const val SOURCE_BLE = "Phone Bluetooth"
        const val SOURCE_WIFI = "Phone Wi-Fi"
        const val SOURCE_EDGE_BLE = "Edge Bluetooth"
        const val SOURCE_EDGE_WIFI = "Edge Wi-Fi"
        /** A broadcast not heard for this long leaves the live list. */
        const val STALE_MS = 60_000L
        /** At most one track point per aircraft per interval. */
        const val TRACK_INTERVAL_MS = 5_000L
    }
}
