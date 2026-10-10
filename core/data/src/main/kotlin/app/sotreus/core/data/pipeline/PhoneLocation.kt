package app.sotreus.core.data.pipeline

import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.intelligence.PlaceGeofence
import app.sotreus.sensing.LocationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's location while Sotreus observes in the foreground, or a geotagged session runs. It
 * picks the saved place the phone is in ([PlaceGeofence]) and supplies the optional plus-code tag.
 * Only the latest fix is held, in memory; the one coordinate written to storage is where a hand
 * pick was made, so that pick holds only near there.
 */
@Singleton
class PhoneLocation @Inject constructor(
    private val source: LocationSource,
    private val settings: SettingsRepository,
    private val places: PlaceDao,
) {
    private val mutex = Mutex()

    private val _latest = MutableStateFlow<LocationSource.Fix?>(null)
    val latest: StateFlow<LocationSource.Fix?> = _latest.asStateFlow()

    private val _locating = MutableStateFlow(false)

    /** True while observation waits for a fix before the radios start. */
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    fun canLocate(): Boolean = source.hasPermission() && source.isEnabled()

    /** The latest fix if it was taken at most [maxAgeMs] before [now]. An older fix is never passed off as current. */
    fun fresh(now: Long, maxAgeMs: Long = FRESH_MS): LocationSource.Fix? = _latest.value?.takeIf { now - it.atMs <= maxAgeMs }

    /** Foreground fixes, frequent enough to follow the phone between places. */
    fun updates(): Flow<LocationSource.Fix> = source.updates(UPDATE_INTERVAL_MS, minDistanceM = 0f)

    /** Every fix comes through here: foreground updates, a geotagged session, or a one-off fix. */
    suspend fun offer(fix: LocationSource.Fix) {
        if (accept(fix)) resolve()
    }

    private fun accept(fix: LocationSource.Fix): Boolean {
        if (!PlaceGeofence.isBetter(fix.geo(), _latest.value?.geo())) return false
        _latest.value = fix
        return true
    }

    /**
     * Location first (issue #1): before the radios start, make sure the selected place reflects where
     * the phone is now, so nothing is tagged to where the app was last used. Reuses this run's own fix
     * up to [REUSE_MS] old, else waits up to [timeoutMs] for a new one; the platform's cached fix from
     * before the app opened doesn't count. A place that location picked earlier and can't be confirmed
     * now is dropped to Unsaved place; a hand pick stays.
     */
    suspend fun settleBeforeScan(timeoutMs: Long = START_TIMEOUT_MS) {
        val s = settings.current()
        if (!s.placeByLocation) return
        if (s.currentPlaceId == null && places.located().isEmpty()) return
        if (fresh(System.currentTimeMillis(), REUSE_MS) == null) {
            if (!canLocate()) {
                dropUnconfirmed()
                return
            }
            _locating.value = true
            try {
                source.freshFix(timeoutMs)?.let(::accept)
            } finally {
                _locating.value = false
            }
        }
        if (fresh(System.currentTimeMillis(), RESOLVE_MAX_AGE_MS) != null) resolve() else dropUnconfirmed()
    }

    /** Re-checks the place with the latest fix, e.g. after a place's location or radius changed. */
    suspend fun recheck() = resolve()

    private suspend fun resolve() = mutex.withLock {
        val now = System.currentTimeMillis()
        val fix = fresh(now, RESOLVE_MAX_AGE_MS) ?: return@withLock
        if (!settings.current().placeByLocation) return@withLock
        val selection = settings.placeSelection()
        val areas = places.located().mapNotNull { p ->
            val lat = p.lat ?: return@mapNotNull null
            val lon = p.lon ?: return@mapNotNull null
            PlaceGeofence.Area(p.id, lat, lon, p.radiusM ?: PlaceGeofence.DEFAULT_RADIUS_M)
        }
        when (val d = PlaceGeofence.decide(selection, areas, fix.geo(), now)) {
            PlaceGeofence.Decision.Keep -> Unit
            PlaceGeofence.Decision.Anchor -> settings.anchorPlaceSelection(fix.lat, fix.lon, selection)
            is PlaceGeofence.Decision.Select -> settings.selectPlaceByLocation(d.placeId, now, selection)
        }
    }

    private suspend fun dropUnconfirmed() = mutex.withLock {
        val selection = settings.placeSelection()
        if (selection.auto && selection.placeId != null) settings.selectPlaceByLocation(null, System.currentTimeMillis(), selection)
    }

    private fun LocationSource.Fix.geo() = PlaceGeofence.Fix(atMs, lat, lon, accuracyM)

    companion object {
        /** A fix this recent is "now": anchors a hand pick and tags observations with a plus code. */
        const val FRESH_MS = 60_000L
        private const val REUSE_MS = 15_000L
        private const val RESOLVE_MAX_AGE_MS = 2 * 60_000L
        private const val START_TIMEOUT_MS = 10_000L
        private const val UPDATE_INTERVAL_MS = 10_000L
    }
}
