package app.sotreus.intelligence

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which saved place the phone is at, from a phone location fix and each place's saved centre and
 * radius. Pure rules, no Android: the data layer feeds fixes in and applies the [Decision].
 *
 * - A place is entered when the fix is within its radius, and only left once the fix is clearly
 *   outside (radius + [EXIT_MARGIN_M] or the fix's accuracy), so small moves and GPS jitter don't
 *   change how observations are tagged.
 * - A place the user picks by hand holds while the phone stays near where it was picked; once the
 *   phone moves away, location takes over again.
 * - Fixes too inaccurate to tell places apart change nothing.
 */
object PlaceGeofence {
    /** A saved place with a location. */
    data class Area(val placeId: Long, val lat: Double, val lon: Double, val radiusM: Int)

    /** A phone location fix. [atMs] is when the fix was taken, not when it was read. */
    data class Fix(val atMs: Long, val lat: Double, val lon: Double, val accuracyM: Float?)

    /**
     * The current place selection. [auto] means location picked it. For a hand pick, [anchorLat]/[anchorLon]
     * is where the phone was when the user picked it (null if no fix was available then).
     */
    data class Selection(
        val placeId: Long?,
        val auto: Boolean,
        val anchorLat: Double? = null,
        val anchorLon: Double? = null,
        val selectedAtMs: Long = 0,
    )

    sealed interface Decision {
        /** Nothing changes. */
        data object Keep : Decision

        /** A hand pick made without a fix: remember this fix as where it was made. */
        data object Anchor : Decision

        /** Location picks [placeId]; null means no saved place (Unsaved place). */
        data class Select(val placeId: Long?) : Decision
    }

    const val DEFAULT_RADIUS_M = 150
    const val EXIT_MARGIN_M = 50f

    /** Fixes less accurate than this can't tell places apart; they change nothing. */
    const val MAX_ACCURACY_M = 150f

    /** A hand pick made without a fix adopts the first fix that arrives within this window. */
    const val ANCHOR_ADOPT_MS = 10 * 60_000L

    /** Radii the user can choose for a place. */
    val RADIUS_CHOICES_M = listOf(50, 100, 150, 300)

    fun usable(fix: Fix): Boolean = (fix.accuracyM ?: 0f) <= MAX_ACCURACY_M

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun margin(fix: Fix): Float = max(EXIT_MARGIN_M, fix.accuracyM ?: 0f)

    /**
     * The saved place containing [fix], or null. [currentId] keeps its place until the fix is beyond
     * its radius plus the exit margin; otherwise the nearest place whose radius contains the fix wins.
     */
    fun match(areas: List<Area>, fix: Fix, currentId: Long?): Long? {
        areas.firstOrNull { it.placeId == currentId }?.let { cur ->
            if (distanceM(cur.lat, cur.lon, fix.lat, fix.lon) <= cur.radiusM + margin(fix)) return cur.placeId
        }
        return areas.map { it to distanceM(it.lat, it.lon, fix.lat, fix.lon) }
            .filter { (a, d) -> d <= a.radiusM }
            .minByOrNull { it.second }
            ?.first?.placeId
    }

    /** What a new [fix] means for the current [selection]. */
    fun decide(selection: Selection, areas: List<Area>, fix: Fix, now: Long): Decision {
        if (!usable(fix)) return Decision.Keep
        if (!selection.auto) {
            val aLat = selection.anchorLat
            val aLon = selection.anchorLon
            if (aLat == null || aLon == null) {
                if (now - selection.selectedAtMs <= ANCHOR_ADOPT_MS) return Decision.Anchor
                // An older hand pick with no recorded spot can't be confirmed: location decides.
            } else {
                val hold = (areas.firstOrNull { it.placeId == selection.placeId }?.radiusM ?: DEFAULT_RADIUS_M) + margin(fix)
                if (distanceM(aLat, aLon, fix.lat, fix.lon) <= hold) return Decision.Keep
            }
        }
        val placeId = match(areas, fix, selection.placeId)
        return if (selection.auto && placeId == selection.placeId) Decision.Keep else Decision.Select(placeId)
    }

    /**
     * Whether [new] should replace [old] as the phone's location: anything clearly newer, otherwise
     * only a fix that is not much less accurate (so a coarse network fix doesn't undo a GPS one).
     */
    fun isBetter(new: Fix, old: Fix?): Boolean {
        if (old == null) return true
        val dt = new.atMs - old.atMs
        if (dt > NEWER_MS) return true
        if (dt < -NEWER_MS) return false
        val na = new.accuracyM ?: Float.MAX_VALUE
        val oa = old.accuracyM ?: Float.MAX_VALUE
        return if (dt >= 0) na <= oa + ACCURACY_SLACK_M else na < oa
    }

    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val NEWER_MS = 30_000L
    private const val ACCURACY_SLACK_M = 25f
}
