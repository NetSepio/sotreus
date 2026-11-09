package app.sotreus.core.data.pipeline

import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.DatabaseTransactions
import app.sotreus.core.database.dao.EncounterDao
import app.sotreus.core.database.dao.FiledLocatedRow
import app.sotreus.core.database.dao.LocatedRow
import app.sotreus.core.database.dao.ObservationDao
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.entity.PlaceVisitEntity
import app.sotreus.core.database.entity.VisitEntityEntity
import app.sotreus.core.model.LocationSource
import app.sotreus.intelligence.AutoPlaces
import app.sotreus.intelligence.PlaceGeofence
import app.sotreus.intelligence.PlaceVisits
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files stored located rows that have no place under automatic places, with the same rules as
 * live fixes: settling makes a place, moving leaves rows without one. A stationary Edge's rows go
 * to the place at its position. Runs once over existing history when automatic places are turned
 * on, then over whatever arrived since (Edge syncs bring older rows), never twice over one row.
 *
 * Then records the visits that stored sightings make ([PlaceVisits]): sightings filed under a
 * place outside the time this phone observed there live (sorted history, synced Edge records)
 * get visits, merged into any visit they touch, so a place made from history shows its visits
 * and feeds its baseline.
 */
@Singleton
class AutoPlaceSorter @Inject constructor(
    private val settings: SettingsRepository,
    private val observations: ObservationDao,
    private val encounters: EncounterDao,
    private val places: PlaceDao,
    private val tracker: AutoPlaceTracker,
    private val transactions: DatabaseTransactions,
) {
    private val mutex = Mutex()

    suspend fun sort() = mutex.withLock {
        val s = settings.current()
        if (s.autoPlaces != true) return@withLock
        // Rules changed since the last full pass: go over every unplaced row once more.
        val version = settings.autoPlacesSortVersion()
        if (version < SORT_VERSION) {
            if (version < 4) unfileFar()
            settings.restartAutoPlacesSort(SORT_VERSION)
        }
        // Rows up to here are final once sorted: filed, or left without a place for good.
        val through = observations.maxId()
        val (obsFrom, encFrom) = settings.autoPlacesSortedThrough()
        val obsTo = sortRows(obsFrom, s.autoPlaceRadiusM, { after, limit -> observations.locatedUnplaced(after, limit, AutoPlaces.FIX_MAX_AGE_MS) }, observations::fileUnder)
        val encTo = sortRows(encFrom, s.autoPlaceRadiusM, encounters::locatedUnplaced, encounters::fileUnder)
        settings.setAutoPlacesSortedThrough(obsTo, encTo)
        recordVisits(settings.autoPlacesVisitsThrough(), through)
    }

    /**
     * Before sort version 4 a place could hold across a gap in stored history, filing the first
     * sightings of a later day under a place far away. Unfiles located rows more than [FAR_FILED_M]
     * from their automatic place (live jitter never reaches that far), and drops ended visits of
     * those places that no longer contain a sighting. The re-sort then judges the rows again.
     */
    private suspend fun unfileFar() {
        val affected = HashSet<Long>()
        suspend fun pass(page: suspend (Long, Int) -> List<FiledLocatedRow>, unfile: suspend (List<Long>) -> Unit) {
            var last = 0L
            while (true) {
                val rows = page(last, PAGE)
                if (rows.isEmpty()) return
                last = rows.last().id
                val far = rows.filter { PlaceGeofence.distanceM(it.placeLat, it.placeLon, it.lat, it.lon) > FAR_FILED_M }
                far.map { it.id }.chunked(500).forEach { unfile(it) }
                far.mapTo(affected) { it.placeId }
                if (rows.size < PAGE) return
            }
        }
        pass(observations::filedInAutoPlaces, observations::unfile)
        pass(encounters::filedInAutoPlaces, encounters::unfile)
        transactions.run {
            affected.forEach { placeId ->
                places.deleteEmptyStoredVisitEntities(placeId)
                places.deleteEmptyStoredVisitRows(placeId)
            }
        }
    }

    /** Visits for filed observations with ids in ([afterId], [throughId]], a page at a time. */
    private suspend fun recordVisits(afterId: Long, throughId: Long) {
        var last = afterId
        while (last < throughId) {
            val rows = observations.filedBetween(last, throughId, PAGE)
            if (rows.isEmpty()) break
            transactions.run {
                rows.groupBy { it.placeId }.forEach { (placeId, filed) ->
                    PlaceVisits.from(filed.map { PlaceVisits.Sighting(it.atMs, it.entityId) }, PlaceTracker.CONTINUE_MS)
                        .forEach { visit -> record(placeId, visit) }
                }
            }
            last = rows.last().id
            settings.setAutoPlacesVisitsThrough(last)
            if (rows.size < PAGE) break
        }
        settings.setAutoPlacesVisitsThrough(throughId)
    }

    /**
     * A new visit, or the visits it touches joined into one: the open (live) one if any, so the
     * place tracker's visit is never deleted, else the earliest. Their radios move with them.
     */
    private suspend fun record(placeId: Long, visit: PlaceVisits.Visit) {
        val near = places.visitsNear(placeId, visit.startMs, visit.endMs, PlaceTracker.CONTINUE_MS)
        val keep = near.firstOrNull { it.endedAtMs == null } ?: near.firstOrNull()
        val visitId = if (keep == null) {
            places.insertVisit(PlaceVisitEntity(placeId = placeId, startedAtMs = visit.startMs, endedAtMs = visit.endMs))
        } else {
            val start = minOf(visit.startMs, near.minOf { it.startedAtMs })
            val end = if (keep.endedAtMs == null) null else maxOf(visit.endMs, near.maxOf { it.endedAtMs ?: it.startedAtMs })
            places.setVisitSpan(keep.id, start, end)
            near.filter { it.id != keep.id }.forEach { other ->
                mergeRadios(keep.id, places.visitEntitiesOf(other.id).associate { it.entityId to it.firstSeenMs..it.lastSeenMs })
                places.deleteVisitEntities(other.id)
                places.deleteVisit(other.id)
            }
            keep.id
        }
        mergeRadios(visitId, visit.entities)
    }

    private suspend fun mergeRadios(visitId: Long, spans: Map<String, LongRange>) {
        spans.keys.chunked(500).forEach { ids ->
            val known = places.visitEntities(visitId, ids).associateBy { it.entityId }
            places.upsertVisitEntities(
                ids.map { id ->
                    val span = spans.getValue(id)
                    val had = known[id]
                    VisitEntityEntity(
                        visitId, id,
                        minOf(span.first, had?.firstSeenMs ?: span.first),
                        maxOf(span.last, had?.lastSeenMs ?: span.last),
                    )
                },
            )
        }
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

        /**
         * 2: V1 geotagged rows take the accuracy of their session fix. 3: visits for filed rows.
         * 4: a place never holds across a long gap; rows filed far from their place are unfiled.
         * 5: visits a stored run touches are joined into one.
         * 6: once a restart no longer ends a visit at its start (PlaceTracker), the visits it split
         * are joined again.
         */
        const val SORT_VERSION = 6

        /** Further than this from its automatic place, a filed row was misfiled, not jitter. */
        const val FAR_FILED_M = 1_000.0
    }
}
