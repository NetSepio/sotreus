package app.sotreus.core.data.pipeline

import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.EncounterDao
import app.sotreus.core.database.dao.LocatedRow
import app.sotreus.core.database.dao.ObservationDao
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.model.LocationSource
import app.sotreus.intelligence.AutoPlaces
import app.sotreus.intelligence.PlaceGeofence
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files stored located rows that have no place under automatic places, with the same rules as
 * live fixes: settling makes a place, moving leaves rows without one. A stationary Edge's rows go
 * to the place at its position. Runs once over existing history when automatic places are turned
 * on, then over whatever arrived since (Edge syncs bring older rows), never twice over one row.
 */
@Singleton
class AutoPlaceSorter @Inject constructor(
    private val settings: SettingsRepository,
    private val observations: ObservationDao,
    private val encounters: EncounterDao,
    private val places: PlaceDao,
    private val tracker: AutoPlaceTracker,
) {
    private val mutex = Mutex()

    suspend fun sort() = mutex.withLock {
        val s = settings.current()
        if (s.autoPlaces != true) return@withLock
        val (obsFrom, encFrom) = settings.autoPlacesSortedThrough()
        val obsTo = sortRows(obsFrom, s.autoPlaceRadiusM, observations::locatedUnplaced, observations::fileUnder)
        val encTo = sortRows(encFrom, s.autoPlaceRadiusM, encounters::locatedUnplaced, encounters::fileUnder)
        settings.setAutoPlacesSortedThrough(obsTo, encTo)
    }

    private suspend fun sortRows(
        afterId: Long,
        radiusM: Int,
        page: suspend (Long, Int) -> List<LocatedRow>,
        file: suspend (List<Long>, Long) -> Unit,
    ): Long {
        var last = afterId
        var state = AutoPlaces.State()
        while (true) {
            val rows = page(last, PAGE)
            if (rows.isEmpty()) return last
            last = rows.last().id
            val filed = HashMap<Long, MutableList<Long>>()
            var areas = areas()
            // In time order within a page, so settling follows how the listener actually moved.
            for (r in rows.sortedBy { it.atMs }) {
                if (r.source == LocationSource.EDGE_FIXED) {
                    filed.getOrPut(tracker.placeAt(r.lat, r.lon, r.atMs)) { ArrayList() } += r.id
                    continue
                }
                val (next, action) = AutoPlaces.step(state, areas, AutoPlaces.Fix(r.atMs, r.lat, r.lon, r.accuracyM), radiusM)
                state = next
                if (action is AutoPlaces.Action.Create) {
                    val id = tracker.createFromHistory(action.lat, action.lon, action.radiusM, r.atMs)
                    state = AutoPlaces.created(state, id)
                    areas = areas()
                }
                state.placeId?.takeIf { AutoPlaces.good(AutoPlaces.Fix(r.atMs, r.lat, r.lon, r.accuracyM)) }
                    ?.let { filed.getOrPut(it) { ArrayList() } += r.id }
            }
            filed.forEach { (placeId, ids) -> ids.chunked(500).forEach { file(it, placeId) } }
            if (rows.size < PAGE) return last
        }
    }

    private suspend fun areas() = places.located().mapNotNull { p ->
        val lat = p.lat ?: return@mapNotNull null
        val lon = p.lon ?: return@mapNotNull null
        AutoPlaces.Area(p.id, lat, lon, p.radiusM ?: PlaceGeofence.DEFAULT_RADIUS_M, p.auto)
    }

    private companion object {
        const val PAGE = 2_000
    }
}
