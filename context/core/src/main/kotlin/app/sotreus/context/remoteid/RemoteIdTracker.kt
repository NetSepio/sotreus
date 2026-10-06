package app.sotreus.context.remoteid

import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.ContextDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.ContextEventEntity
import app.sotreus.core.model.ContextKind
import app.sotreus.core.model.Provenance
import app.sotreus.intelligence.fieldwatch.Observation
import app.sotreus.intelligence.fieldwatch.OpenDroneId
import app.sotreus.intelligence.fieldwatch.PayloadLocation
import app.sotreus.intelligence.fieldwatch.RadioKind
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
    val speedMps: Double?,
    val courseDeg: Double?,
    val radio: RadioKind,
    val address: String,
    val rssi: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

/**
 * Decodes ASTM F3411 Remote ID (BLE service data FFFA, Wi-Fi beacon vendor IE FA:0B:BC type 0x0D)
 * from the phone's own radio observations with the ported Fieldwatch [OpenDroneId] decoder.
 * Nothing is sent anywhere. Broadcast tracks are kept as SENSED context records.
 */
@Singleton
class RemoteIdTracker @Inject constructor(
    private val radios: RadioHub,
    private val settings: SettingsRepository,
    private val dao: ContextDao,
    private val sessions: SessionDao,
) {
    private val mutex = Mutex()
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
                .flatMapLatest { on -> if (on) radios.observations else emptyFlow<Observation>().also { clear() } }
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
        if (decoded.uasId == null && decoded.pin() == null && decoded.opLat == null && decoded.selfId == null) return
        val record = mutex.withLock {
            val merged = decoded.mergeSticky(byAddress[obs.mac])
            byAddress[obs.mac] = merged
            val subject = merged.uasId ?: obs.mac
            if (merged.uasId != null) drones.remove(obs.mac) // the address-keyed entry becomes the UAS ID one
            val prev = drones[subject]
            val pin = merged.pin()
            val next = LiveRemoteId(
                subjectId = subject, uasId = merged.uasId, maker = merged.aircraft, selfId = merged.selfId,
                lat = pin?.first, lon = pin?.second, altM = merged.alt,
                operatorLat = merged.opLat, operatorLon = merged.opLon,
                speedMps = merged.speedMps, courseDeg = merged.headingDeg,
                radio = obs.kind, address = obs.mac, rssi = obs.rssi,
                firstSeenMs = prev?.firstSeenMs ?: obs.at, lastSeenMs = obs.at,
            )
            drones[subject] = next
            _live.value = drones.values.sortedByDescending { it.lastSeenMs }
            val last = lastWrite[subject]
            val moved = prev == null || prev.lat != next.lat || prev.lon != next.lon || prev.altM != next.altM
            when {
                last == null || (moved && obs.at - last.first >= TRACK_INTERVAL_MS) -> Write.Insert(next)
                obs.at - last.first >= TRACK_INTERVAL_MS -> Write.Extend(last.second, next)
                else -> null
            }
        } ?: return
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
            radio == RadioKind.WIFI -> SOURCE_WIFI
            else -> SOURCE_BLE
        },
        subjectId = subjectId,
        title = maker ?: selfId ?: uasId ?: "",
        atMs = lastSeenMs,
        lat = lat, lon = lon, altM = altM, speedMps = speedMps, courseDeg = courseDeg,
        operatorLat = operatorLat, operatorLon = operatorLon,
        sessionId = sessionId,
    )

    companion object {
        const val SOURCE_BLE = "Phone Bluetooth"
        const val SOURCE_WIFI = "Phone Wi-Fi"
        /** A broadcast not heard for this long leaves the live list. */
        const val STALE_MS = 60_000L
        /** At most one track point per aircraft per interval. */
        const val TRACK_INTERVAL_MS = 5_000L
    }
}
