package app.sotreus.core.data.pipeline

import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.entity.PlaceEntity
import app.sotreus.intelligence.AutoPlaces
import app.sotreus.intelligence.PlaceGeofence
import app.sotreus.intelligence.PlusCode
import app.sotreus.sensing.LocationSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies [AutoPlaces] to this phone's fixes while automatic places are on: enters existing places,
 * leaves them only on confirmed moves, and makes a new place where the phone settles. The place it
 * picks is the current place selection, so the pipeline files sightings under it as before.
 */
@Singleton
class AutoPlaceTracker @Inject constructor(
    private val settings: SettingsRepository,
    private val places: PlaceDao,
) {
    private val mutex = Mutex()
    private var state = AutoPlaces.State()

    suspend fun enabled(): Boolean = settings.current().autoPlaces == true

    /**
     * A stationary Edge as the sensor: its place is where the sensing happens, so this phone's own
     * fixes don't move the current place. Set by the observation controller.
     */
    @Volatile var pinnedPlaceId: Long? = null

    /** A new good-or-not fix; coarse ones change nothing. */
    suspend fun onFix(fix: LocationSource.Fix, now: Long = System.currentTimeMillis()) = mutex.withLock {
        val s = settings.current()
        if (s.autoPlaces != true || fix.mock || pinnedPlaceId != null) return@withLock
        val selection = settings.placeSelection()
        val located = places.located()
        // A hand pick holds while the phone stays near where it was made (PlaceGeofence's rule).
        if (!selection.auto) {
            val geo = PlaceGeofence.Fix(fix.atMs, fix.lat, fix.lon, fix.accuracyM)
            val areas = located.mapNotNull { it.area()?.let { a -> PlaceGeofence.Area(a.placeId, a.lat, a.lon, a.radiusM) } }
            when (PlaceGeofence.decide(selection, areas, geo, now)) {
                PlaceGeofence.Decision.Keep -> return@withLock
                PlaceGeofence.Decision.Anchor -> { settings.anchorPlaceSelection(fix.lat, fix.lon, selection); return@withLock }
                is PlaceGeofence.Decision.Select -> Unit // moved away from the pick: automatic places take over
            }
        }
        // Another path (a hand pick, a deleted place) may have changed the selection.
        val selected = selection.placeId.takeIf { selection.auto }
        if (state.placeId != selected) state = AutoPlaces.State(placeId = selected)
        val (next, action) = AutoPlaces.step(state, located.mapNotNull { it.area() }, AutoPlaces.Fix(fix.atMs, fix.lat, fix.lon, fix.accuracyM), s.autoPlaceRadiusM)
        state = next
        when (action) {
            AutoPlaces.Action.Keep -> Unit
            is AutoPlaces.Action.Enter -> settings.selectPlaceByLocation(action.placeId, now, selection)
            AutoPlaces.Action.Leave -> settings.selectPlaceByLocation(null, now, selection)
            is AutoPlaces.Action.Create -> {
                val id = create(action.lat, action.lon, action.radiusM, now)
                state = AutoPlaces.created(state, id)
                settings.selectPlaceByLocation(id, now, selection)
            }
        }
    }

    /**
     * The place for a fixed spot the owner set (a stationary Edge): the place that contains it, or a
     * new automatic place there at once (no settling: the owner chose the spot).
     */
    suspend fun placeAt(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Long = mutex.withLock {
        val fix = AutoPlaces.Fix(now, lat, lon, AutoPlaces.GOOD_ACCURACY_M)
        AutoPlaces.match(places.located().mapNotNull { it.area() }, fix) ?: create(lat, lon, settings.current().autoPlaceRadiusM, now)
    }

    /** Places made from stored history (sorting past data in). */
    suspend fun createFromHistory(lat: Double, lon: Double, radiusM: Int, atMs: Long): Long = mutex.withLock { create(lat, lon, radiusM, atMs) }

    private suspend fun create(lat: Double, lon: Double, radiusM: Int, now: Long): Long =
        places.insert(PlaceEntity(name = PlusCode.encode(lat, lon), createdAtMs = now, updatedAtMs = now, lat = lat, lon = lon, radiusM = radiusM, auto = true))

    private fun PlaceEntity.area(): AutoPlaces.Area? {
        val la = lat ?: return null
        val lo = lon ?: return null
        return AutoPlaces.Area(id, la, lo, radiusM ?: PlaceGeofence.DEFAULT_RADIUS_M, auto)
    }
}
