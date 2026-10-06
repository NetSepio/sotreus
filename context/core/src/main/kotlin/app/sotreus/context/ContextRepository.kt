package app.sotreus.context

import app.sotreus.context.aircraft.AircraftReport
import app.sotreus.context.aircraft.AircraftResult
import app.sotreus.context.aircraft.OpenSkyProvider
import app.sotreus.context.remoteid.RemoteIdTracker
import app.sotreus.context.satellite.CelesTrakCatalog
import app.sotreus.context.satellite.OrbitalElements
import app.sotreus.context.satellite.SatelliteLook
import app.sotreus.context.satellite.SatellitePass
import app.sotreus.context.satellite.SatellitePredictor
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.ContextDao
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.ContextEventEntity
import app.sotreus.core.model.AircraftMode
import app.sotreus.core.model.ContextKind
import app.sotreus.core.model.Provenance
import app.sotreus.core.model.SatelliteGroup
import app.sotreus.sensing.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Where context is computed for. Resolved on the phone; providers only ever see an area. */
data class ContextLocation(val point: GeoPoint, val source: Source, val placeName: String? = null) {
    enum class Source { PLACE, SESSION, PHONE }
}

data class NearbyAircraft(val report: AircraftReport, val distanceKm: Double)

sealed interface AircraftState {
    data object Off : AircraftState
    data object NoLocation : AircraftState
    data object Loading : AircraftState
    data class Ready(
        val aircraft: List<NearbyAircraft>,
        val fetchedAtMs: Long,
        val providerTimeMs: Long,
        val mode: AircraftMode,
        val radiusKm: Int,
        val provider: String,
        val location: ContextLocation,
        /** The last request failed or was rate limited; [aircraft] is from [fetchedAtMs]. */
        val problem: Problem? = null,
    ) : AircraftState
    data class Unavailable(val problem: Problem) : AircraftState

    enum class Problem { RATE_LIMITED, NETWORK }
}

data class SatelliteNow(val elements: OrbitalElements, val look: SatelliteLook)

sealed interface SatelliteState {
    data object Off : SatelliteState
    data object Loading : SatelliteState
    data object NoLocation : SatelliteState
    data object NoCatalog : SatelliteState
    data class Ready(
        val catalogCount: Int,
        /** Every catalog object, for drawing. */
        val elements: List<OrbitalElements>,
        val catalogFetchedAtMs: Long?,
        val refreshFailed: Boolean,
        val location: ContextLocation,
        /** Moving satellites above the horizon now. */
        val overhead: List<SatelliteNow>,
        /** Geosynchronous satellites above the horizon: always there, so listed apart. */
        val geosynchronous: List<SatelliteNow>,
        /** Next 24 h, peak ≥ [SatellitePredictor.MIN_MAX_ELEVATION_DEG]. Null while computing. */
        val passes: List<SatellitePass>?,
        val atMs: Long,
    ) : SatelliteState
}

data class SatelliteDetail(
    val elements: OrbitalElements,
    val look: SatelliteLook,
    val passes: List<SatellitePass>,
    val location: ContextLocation,
    val atMs: Long,
)

/** Context records that overlap a session in time. Overlap only: nothing here says one caused another. */
data class SessionContext(
    val aircraft: List<ContextEventEntity>,
    val remoteId: List<ContextEventEntity>,
    val satellitePasses: List<SatellitePass>,
)

/**
 * Context sources (architecture handoff §14): aircraft reported by a network provider, satellite
 * passes predicted on the phone and Remote ID broadcasts sensed by the phone. Every source is off
 * when the master switch is off. Aircraft polling happens only while the Context tab is visible or
 * a session is running.
 */
@Singleton
class ContextRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val places: PlaceDao,
    private val sessions: SessionDao,
    private val location: LocationSource,
    private val openSky: OpenSkyProvider,
    private val catalog: CelesTrakCatalog,
    private val dao: ContextDao,
    val remoteId: RemoteIdTracker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val viewers = MutableStateFlow(0)
    private val _aircraft = MutableStateFlow<AircraftState>(AircraftState.Off)
    private var phoneFix: Pair<Long, GeoPoint>? = null
    private val passCache = Mutex()
    private var cachedPasses: Pair<String, List<SatellitePass>>? = null
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        remoteId.start(scope)
        scope.launch { pollAircraft() }
    }

    /** Aircraft near the context location. Collecting this keeps the 60 s poll running. */
    fun aircraft(): Flow<AircraftState> = _aircraft.asStateFlow()
        .onStart { viewers.update { it + 1 } }
        .onCompletion { viewers.update { it - 1 } }

    fun refreshAircraftNow() { refreshRequests.update { it + 1 } }

    private val refreshRequests = MutableStateFlow(0)

    private suspend fun pollAircraft() {
        combine(
            settings.settings.map { Triple(it.contextEnabled, it.aircraftMode, it.aircraftRadiusKm) }.distinctUntilChanged(),
            viewers.map { it > 0 }.distinctUntilChanged(),
            sessions.observeActive().map { it?.id }.distinctUntilChanged(),
            refreshRequests,
        ) { cfg, visible, sessionId, _ -> Triple(cfg, visible, sessionId) }
            .collectLatest { (cfg, visible, sessionId) ->
                val (enabled, mode, radius) = cfg
                if (!enabled || mode == AircraftMode.OFF) {
                    _aircraft.value = AircraftState.Off
                    return@collectLatest
                }
                if (!visible && sessionId == null) return@collectLatest
                if (_aircraft.value !is AircraftState.Ready) _aircraft.value = AircraftState.Loading
                while (true) {
                    val located = pollOnce(mode, radius, sessionId)
                    delay(if (!located) NO_LOCATION_RETRY_MS else if (visible) VISIBLE_POLL_MS else SESSION_POLL_MS)
                }
            }
    }

    /** Returns false when there was no location to ask for. */
    private suspend fun pollOnce(mode: AircraftMode, radiusKm: Int, sessionId: Long?): Boolean {
        val loc = resolveLocation() ?: run {
            _aircraft.value = AircraftState.NoLocation
            return false
        }
        val area = queryArea(mode, loc.point, radiusKm.toDouble())
        val now = System.currentTimeMillis()
        when (val r = openSky.aircraftIn(area)) {
            is AircraftResult.Ok -> {
                val nearby = withinRadius(r.reports, loc.point, radiusKm.toDouble())
                _aircraft.value = AircraftState.Ready(nearby, now, r.providerTimeMs, mode, radiusKm, openSky.name, loc)
                if (sessionId != null) record(nearby, sessionId, now)
            }
            AircraftResult.RateLimited -> _aircraft.update { prevOr(it, AircraftState.Problem.RATE_LIMITED) }
            is AircraftResult.Failed -> _aircraft.update { prevOr(it, AircraftState.Problem.NETWORK) }
        }
        return true
    }

    private fun prevOr(prev: AircraftState, p: AircraftState.Problem): AircraftState =
        if (prev is AircraftState.Ready) prev.copy(problem = p) else AircraftState.Unavailable(p)

    private suspend fun record(nearby: List<NearbyAircraft>, sessionId: Long, now: Long) {
        nearby.forEach { (a, d) ->
            val last = dao.latest(ContextKind.AIRCRAFT, a.icao24)
            if (last != null && last.sessionId == sessionId && now - (last.endMs ?: last.atMs) < CONTINUE_MS) {
                val closer = d < (last.distanceKm ?: Double.MAX_VALUE)
                dao.update(
                    if (closer) last.copy(endMs = now, lat = a.lat, lon = a.lon, altM = a.altitudeM, speedMps = a.speedMps, courseDeg = a.courseDeg, distanceKm = d)
                    else last.copy(endMs = now),
                )
            } else {
                dao.insert(
                    ContextEventEntity(
                        kind = ContextKind.AIRCRAFT, provenance = Provenance.NETWORK, source = openSky.name,
                        subjectId = a.icao24, title = a.callsign ?: a.icao24, atMs = now, endMs = now,
                        lat = a.lat, lon = a.lon, altM = a.altitudeM, speedMps = a.speedMps, courseDeg = a.courseDeg,
                        distanceKm = d, sessionId = sessionId,
                    ),
                )
            }
        }
    }

    /** Current place's saved coordinates, then the running session's last fix, then one phone fix. */
    suspend fun resolveLocation(): ContextLocation? {
        val s = settings.settings.first()
        s.currentPlaceId?.let { places.get(it) }?.let { p ->
            if (p.lat != null && p.lon != null) return ContextLocation(GeoPoint(p.lat!!, p.lon!!), ContextLocation.Source.PLACE, p.name)
        }
        sessions.observeActive().first()?.let { sessions.lastLocation(it.id) }?.let { l ->
            if (System.currentTimeMillis() - l.atMs < FIX_MAX_AGE_MS) return ContextLocation(GeoPoint(l.lat, l.lon), ContextLocation.Source.SESSION)
        }
        val now = System.currentTimeMillis()
        phoneFix?.let { (at, p) -> if (now - at < FIX_MAX_AGE_MS) return ContextLocation(p, ContextLocation.Source.PHONE) }
        val fix = location.currentFix(timeoutMs = 10_000) ?: return null
        val p = GeoPoint(fix.lat, fix.lon)
        phoneFix = now to p
        return ContextLocation(p, ContextLocation.Source.PHONE)
    }

    /** The context location, re-resolved every minute (place, session or phone). */
    fun locationUpdates(): Flow<ContextLocation?> = flow {
        while (true) {
            emit(resolveLocation())
            delay(60_000)
        }
    }

    // ---- Satellites ----

    fun satellites(): Flow<SatelliteState> = settings.settings
        .map { Triple(it.contextEnabled && it.satellitesEnabled, it.satelliteGroups, it.currentPlaceId) }
        .distinctUntilChanged()
        .let { cfg ->
            channelFlow {
                cfg.collectLatest { (on, groups, _) -> emitSatellites(on, groups) { send(it) } }
            }
        }

    private suspend fun emitSatellites(on: Boolean, groups: Set<SatelliteGroup>, emit: suspend (SatelliteState) -> Unit) {
        if (!on || groups.isEmpty()) return emit(SatelliteState.Off)
        emit(SatelliteState.Loading)
        // Cached elements show at once; a stale catalog is refreshed alongside.
        var state = catalog.cached(groups)
        var refreshFailed = false
        if (state.elements.isEmpty()) {
            refreshFailed = !catalog.refresh(groups, CATALOG_MAX_AGE_MS)
            state = catalog.cached(groups)
            if (state.elements.isEmpty()) return emit(SatelliteState.NoCatalog)
        }
        var loc = resolveLocation()
        while (loc == null) {
            emit(SatelliteState.NoLocation)
            delay(NO_LOCATION_RETRY_MS)
            loc = resolveLocation()
        }
        val here = loc
        coroutineScope {
            var passes: List<SatellitePass>? = null
            var passJob = launch { passes = passesFor(state.elements.filterNot { it.geosynchronous }, here.point, state.fetchedAtMs) }
            launch {
                if (catalog.refresh(groups, CATALOG_MAX_AGE_MS)) {
                    val fresh = catalog.cached(groups)
                    if (fresh.fetchedAtMs != state.fetchedAtMs) {
                        state = fresh
                        passJob.cancel()
                        passes = null
                        passJob = launch { passes = passesFor(fresh.elements.filterNot { it.geosynchronous }, here.point, fresh.fetchedAtMs) }
                    }
                } else {
                    refreshFailed = true
                }
            }
            while (true) {
                val now = System.currentTimeMillis()
                val elements = state.elements
                val overhead = withContext(Dispatchers.Default) {
                    elements.mapNotNull { e ->
                        runCatching { SatellitePredictor.look(e, here.point.lat, here.point.lon, now) }.getOrNull()
                            ?.takeIf { it.aboveHorizon }?.let { SatelliteNow(e, it) }
                    }.sortedByDescending { it.look.elevationDeg }
                }
                val (geo, moving) = overhead.partition { it.elements.geosynchronous }
                val p = passes
                emit(SatelliteState.Ready(elements.size, elements, state.fetchedAtMs, refreshFailed, here, moving, geo, p?.filter { it.endMs >= now }, now))
                delay(if (p == null) 2_000 else OVERHEAD_TICK_MS)
            }
        }
    }

    /** Passes over the next 24 h for every catalog object, cached per catalog, location cell and hour. */
    private suspend fun passesFor(elements: List<OrbitalElements>, p: GeoPoint, fetchedAtMs: Long?): List<SatellitePass> {
        val now = System.currentTimeMillis()
        val key = "${elements.size}:$fetchedAtMs:${(p.lat * 10).roundToInt()}:${(p.lon * 10).roundToInt()}:${now / 3_600_000}"
        passCache.withLock { cachedPasses?.takeIf { it.first == key }?.let { return it.second } }
        val fromMs = now - 30 * 60_000L
        val all = withContext(Dispatchers.Default) {
            elements.chunked(16).map { chunk ->
                async { chunk.flatMap { e -> runCatching { SatellitePredictor.passes(e, p.lat, p.lon, fromMs, 24) }.getOrDefault(emptyList()) } }
            }.awaitAll().flatten().sortedBy { it.startMs }
        }
        passCache.withLock { cachedPasses = key to all }
        return all
    }

    suspend fun satellite(noradId: Int): SatelliteDetail? {
        val s = settings.settings.first()
        val e = catalog.cached(s.satelliteGroups.ifEmpty { SatelliteGroup.entries.toSet() }).elements.firstOrNull { it.noradId == noradId }
            ?: catalog.cached(SatelliteGroup.entries.toSet()).elements.firstOrNull { it.noradId == noradId }
            ?: return null
        val loc = resolveLocation() ?: return null
        val now = System.currentTimeMillis()
        return withContext(Dispatchers.Default) {
            SatelliteDetail(
                e, SatellitePredictor.look(e, loc.point.lat, loc.point.lon, now),
                SatellitePredictor.passes(e, loc.point.lat, loc.point.lon, now - 30 * 60_000L, 48).filter { it.endMs >= now },
                loc, now,
            )
        }
    }

    // ---- Remote ID history ----

    fun remoteIdTrack(subjectId: String): Flow<List<ContextEventEntity>> = dao.observeRemoteId(subjectId)

    fun recentRemoteId(sinceMs: Long): Flow<List<ContextEventEntity>> = dao.observeSince(ContextKind.REMOTE_ID, sinceMs)

    fun recordCount(): Flow<Int> = dao.observeCount()

    // ---- Sessions ----

    /** Context records and predicted passes during the session. Overlap in time only. */
    suspend fun sessionContext(sessionId: Long): SessionContext {
        val session = sessions.get(sessionId) ?: return SessionContext(emptyList(), emptyList(), emptyList())
        val rows = dao.forSession(sessionId)
        val end = session.endedAtMs ?: System.currentTimeMillis()
        val s = settings.settings.first()
        val point = sessions.lastLocation(sessionId)?.let { GeoPoint(it.lat, it.lon) }
            ?: session.placeId?.let { places.get(it) }?.takeIf { it.lat != null && it.lon != null }?.let { GeoPoint(it.lat!!, it.lon!!) }
        val passes = if (point != null && s.contextEnabled && s.satellitesEnabled && end - session.startedAtMs <= 24 * 3_600_000L) {
            val elements = catalog.cached(s.satelliteGroups).elements.filterNot { it.geosynchronous }
            withContext(Dispatchers.Default) {
                val hours = ((end - session.startedAtMs) / 3_600_000L + 1).toInt()
                elements.chunked(16).map { chunk ->
                    async {
                        chunk.flatMap { e ->
                            runCatching { SatellitePredictor.passes(e, point.lat, point.lon, session.startedAtMs - 30 * 60_000L, hours + 1) }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll().flatten().filter { it.endMs >= session.startedAtMs && it.startMs <= end }.sortedBy { it.startMs }
            }
        } else {
            emptyList()
        }
        return SessionContext(
            aircraft = rows.filter { it.kind == ContextKind.AIRCRAFT }.sortedBy { it.distanceKm ?: Double.MAX_VALUE },
            remoteId = rows.filter { it.kind == ContextKind.REMOTE_ID }.groupBy { it.subjectId }.map { (_, track) -> track.first() },
            satellitePasses = passes,
        )
    }

    /** Deletes stored context records and the cached satellite catalog. */
    suspend fun clearAll() {
        dao.deleteAll()
        catalog.clear()
        passCache.withLock { cachedPasses = null }
        phoneFix = null
    }

    companion object {
        const val VISIBLE_POLL_MS = 60_000L
        // Anonymous OpenSky access is 400 credits a day (1 credit per box under 25 sq°): an 8 h
        // session at 5 min is 96 credits, leaving room for the Context tab at one per minute.
        const val SESSION_POLL_MS = 5 * 60_000L
        const val CATALOG_MAX_AGE_MS = 24 * 3_600_000L
        const val OVERHEAD_TICK_MS = 15_000L
        const val FIX_MAX_AGE_MS = 10 * 60_000L
        const val NO_LOCATION_RETRY_MS = 15_000L
        /** An aircraft reported again within this gap extends the same session record. */
        const val CONTINUE_MS = 5 * 60_000L

        fun queryArea(mode: AircraftMode, p: GeoPoint, radiusKm: Double): GeoArea =
            if (mode == AircraftMode.EXACT_AREA) Geo.around(p, radiusKm) else Geo.coarseArea(p)

        fun withinRadius(reports: List<AircraftReport>, p: GeoPoint, radiusKm: Double): List<NearbyAircraft> =
            reports.mapNotNull { a ->
                val lat = a.lat ?: return@mapNotNull null
                val lon = a.lon ?: return@mapNotNull null
                val d = Geo.distanceKm(p, GeoPoint(lat, lon))
                if (d <= radiusKm) NearbyAircraft(a, d) else null
            }.sortedBy { it.distanceKm }
    }
}
