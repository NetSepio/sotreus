package app.sotreus.core.data.field

import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.dao.TrackerStopDao
import app.sotreus.core.database.entity.SessionEventEntity
import app.sotreus.core.database.entity.TrackerStopEntity
import app.sotreus.core.model.BleAddressType
import app.sotreus.core.model.SessionEventKind
import app.sotreus.core.model.UserEntityState
import app.sotreus.intelligence.Fingerprint
import app.sotreus.intelligence.fieldwatch.*
import app.sotreus.sensing.LocationSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded flood evidence and explicitly enabled, seven-day hear-location history. */
@Singleton
class FieldInsights @Inject constructor(private val stops: TrackerStopDao, private val sessions: SessionDao, private val classifier: app.sotreus.intelligence.SignatureClassifier) {
    private val pairing = PairingFlood()
    private val wifi = WifiBeaconFlood()
    private val seen = LinkedHashMap<String, Long>()
    private val mutex = Mutex()
    private var lastEvent = 0L
    private var lastPrune = 0L
    private var boundSession: Long? = null
    private var sessionSince = 0L
    private val marked = mutableSetOf<String>()
    private val _notice = MutableStateFlow<PairingFlood.Notice?>(null)
    val notice = _notice.asStateFlow()
    @Volatile var hidden: Set<String> = emptySet(); private set
    @Volatile var quiet: Set<String> = emptySet(); private set

    @Synchronized fun hear(obs: Observation, simulated: Boolean, speedMps: Float?) {
        if (simulated || obs.kind != RadioKind.BLE || !obs.fresh) return
        if (seen.put(obs.mac, obs.at) == null) pairing.consider(obs, obs.at, speedMps?.toDouble())
        while (seen.size > 4_096) seen.remove(seen.keys.first())
    }
    fun wifiScan(rows: List<Observation>, simulated: Boolean) { if (!simulated) wifi.scan(rows, System.currentTimeMillis()) }

    suspend fun tick(now: Long, sessionId: Long?, speedMps: Float?) {
        if (sessionId != boundSession) { boundSession = sessionId; sessionSince = now; marked.clear() }
        pairing.setSitOpen(sessionId != null); wifi.setSitOpen(sessionId != null)
        pairing.tick(now, speedMps?.toDouble())
        _notice.value = pairing.notice.value ?: wifi.notice.value
        hidden = (pairing.hide.value.keys + wifi.hide.value.keys).map(::sotreusKey).toSet()
        val cutoff = if (sessionId == null) now - 15 * 60_000 else minOf(sessionSince, now - 15 * 60_000)
        quiet = (pairing.bursts() + wifi.bursts()).filter { it.at >= cutoff }.flatMap { it.keys }.map(::sotreusKey).toSet()
        if (sessionId != null) {
            (quiet - marked).toList().chunked(500).forEach { sessions.markFlood(sessionId, it) }
            marked.addAll(quiet)
        }
        val n = _notice.value
        if (n != null && sessionId != null && now - lastEvent >= 60_000) {
            sessions.insertEvent(SessionEventEntity(sessionId = sessionId, atMs = now, kind = SessionEventKind.SYSTEM_NOTE,
                text = n.line() + ". This can be many nearby radios or changing advertisements; it does not identify a tool."))
            lastEvent = now
        }
        if (now - lastPrune >= 60_000) {
            stops.prune(now - PlaceMemory.WINDOW_MS); stops.pruneMemory(now - PlaceMemory.WINDOW_MS); stops.capAll(); lastPrune = now
            synchronized(this) { seen.entries.removeAll { now - it.value > 15 * 60_000 } }
        }
    }

    fun hideBurst() { pairing.setHideBurst(true); wifi.setHideBurst(true) }

    suspend fun stop(s: Sighting, fix: LocationSource.Fix?, sessionId: Long?, state: UserEntityState, enabled: Boolean, simulated: Boolean) = mutex.withLock {
        if (!enabled || simulated || state in setOf(UserEntityState.MINE, UserEntityState.EXPECTED, UserEntityState.IGNORE)) return@withLock
        val rid = s.payloadUasId?.takeIf { it.isNotBlank() }
        val type = Fingerprint.addressType(app.sotreus.core.model.RadioKind.BLE, s.facts.addressType, s.mac)
        val stable = when {
            rid != null -> "rid:$rid"
            s.kind == RadioKind.BLE && type == BleAddressType.PUBLIC && (TrackerMatch.isFindMyPayload(s) || TrackerMatch.fleetHits(s, classifier.fleets.associate { it.id to it.name }).isNotEmpty()) -> "ble:${s.mac}"
            else -> return@withLock
        }
        val heard = stops.lastHeard(stable)
        if (heard != null && s.lastSeen - heard < 10_000) return@withLock
        val gap = heard?.let { (s.lastSeen - it).coerceAtLeast(0) } ?: 0L
        stops.remember(stable, s.lastSeen)
        if (fix == null || !fix.lat.isFinite() || !fix.lon.isFinite() || fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0 || fix.mock || fix.accuracyM?.let { it in 0f..50f } != true || kotlin.math.abs(s.lastSeen - fix.atMs) > 20_000) return@withLock
        val previous = stops.latest(stable)
        val last = previous.firstOrNull()
        val near = previous.firstOrNull { it.sessionId == sessionId && Geo.meters(it.lat, it.lon, fix.lat, fix.lon) <= PlaceMemory.SEPARATION_M }
        if (near != null) {
            if (s.lastSeen - near.lastMs < 10_000) return@withLock
            stops.update(near.copy(lastMs = s.lastSeen, hits = near.hits + 1,
                returnGapMs = if (gap >= PlaceMemory.RETURN_MS) gap else near.returnGapMs))
        } else if (last == null || gap >= PlaceMemory.QUIET_MS || last.sessionId != sessionId) {
            stops.insert(TrackerStopEntity(entityId = s.key, stableId = stable, sessionId = sessionId, lat = fix.lat, lon = fix.lon,
                firstMs = s.lastSeen, lastMs = s.lastSeen, hits = 1, returnGapMs = gap.takeIf { it >= PlaceMemory.RETURN_MS } ?: 0))
            stops.cap(stable)
        }
    }

    suspend fun clearStops() = mutex.withLock { stops.clear(); stops.clearMemory() }
    private fun sotreusKey(key: String) = key.substringBefore(':').lowercase() + ":" + key.substringAfter(':').uppercase()
}
