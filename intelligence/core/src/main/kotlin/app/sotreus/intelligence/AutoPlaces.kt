package app.sotreus.intelligence

import kotlin.math.max

/**
 * Automatic places (docs/plans/auto-places.md): pure rules, no Android. The data layer feeds good
 * location fixes in order and applies each [Action].
 *
 * - Only good fixes count ([GOOD_ACCURACY_M] or better); anything coarser changes nothing.
 * - Inside a place, it holds until two fixes at least [LEAVE_CONFIRM_MS] apart are both beyond its
 *   radius plus the exit margin, so one jump of the phone's location does nothing.
 * - Outside every place, a fix inside an existing place enters it (a user-created place wins over
 *   an automatic one). Otherwise fixes that stay within the radius of the first one for
 *   [SETTLE_MS], with at least [SETTLE_MIN_FIXES] of them, make a new automatic place at their
 *   accuracy-weighted centre. While moving, nothing is made.
 */
object AutoPlaces {
    const val GOOD_ACCURACY_M = 25f

    /** A sighting keeps a phone fix only if the fix was taken at most this long before it. */
    const val FIX_MAX_AGE_MS = 10_000L
    const val SETTLE_MS = 3 * 60_000L
    const val SETTLE_MIN_FIXES = 3
    const val LEAVE_CONFIRM_MS = 20_000L

    /**
     * After this long without a good fix, where the listener was no longer counts: the next good
     * fix is judged afresh, so a place never holds across a gap (stored history spans days).
     */
    const val STATE_GAP_MS = 30 * 60_000L
    const val DEFAULT_RADIUS_M = 50
    val RADIUS_CHOICES_M = listOf(25, 50, 100, 200)

    data class Fix(val atMs: Long, val lat: Double, val lon: Double, val accuracyM: Float?)

    data class Area(val placeId: Long, val lat: Double, val lon: Double, val radiusM: Int, val auto: Boolean)

    /** Settling fixes outside every place, all within the radius of the first. */
    data class Candidate(val fixes: List<Fix>) {
        val startMs: Long get() = fixes.first().atMs
    }

    data class State(
        /** The place the listener is in, or null while moving or before the first fix. */
        val placeId: Long? = null,
        /** First good fix beyond the current place's exit distance, while still counted inside. */
        val outsideSinceMs: Long? = null,
        val candidate: Candidate? = null,
        /** The last good fix's time, for [STATE_GAP_MS]. */
        val lastAtMs: Long? = null,
    )

    sealed interface Action {
        /** Nothing changes. */
        data object Keep : Action

        /** The listener is now in [placeId]. */
        data class Enter(val placeId: Long) : Action

        /** The listener left its place and is between places. */
        data object Leave : Action

        /** Make an automatic place here; then report its id with [created]. */
        data class Create(val lat: Double, val lon: Double, val accuracyM: Float, val radiusM: Int) : Action
    }

    fun good(fix: Fix): Boolean = fix.accuracyM != null && fix.accuracyM <= GOOD_ACCURACY_M

    /** Where [fix] takes [state], given the places that exist and the radius for new ones. */
    fun step(state: State, areas: List<Area>, fix: Fix, radiusM: Int): Pair<State, Action> {
        if (!good(fix)) return state to Action.Keep
        val last = state.lastAtMs
        val (next, action) = if (last != null && fix.atMs - last > STATE_GAP_MS) {
            // A long gap: judged as a fix outside every place, leaving the old one if it lands elsewhere.
            val (n, a) = outside(State(), areas, fix, radiusM)
            n to if (a == Action.Keep && state.placeId != null) Action.Leave else a
        } else {
            stepGood(state, areas, fix, radiusM)
        }
        return next.copy(lastAtMs = fix.atMs) to action
    }

    private fun stepGood(state: State, areas: List<Area>, fix: Fix, radiusM: Int): Pair<State, Action> {
        val current = state.placeId?.let { id -> areas.firstOrNull { it.placeId == id } }
        if (current != null) {
            val d = PlaceGeofence.distanceM(current.lat, current.lon, fix.lat, fix.lon)
            if (d <= current.radiusM + exitMargin(fix)) return state.copy(outsideSinceMs = null, candidate = null) to Action.Keep
            val since = state.outsideSinceMs ?: return state.copy(outsideSinceMs = fix.atMs) to Action.Keep
            if (fix.atMs - since < LEAVE_CONFIRM_MS) return state to Action.Keep
            // Left for sure: this fix is then judged as one outside every place.
            val (next, action) = outside(State(), areas, fix, radiusM)
            return next to if (action == Action.Keep) Action.Leave else action
        }
        return outside(state.copy(placeId = null, outsideSinceMs = null), areas, fix, radiusM)
    }

    /** The automatic place made for a [Action.Create] got [placeId]. */
    fun created(state: State, placeId: Long): State = State(placeId = placeId, lastAtMs = state.lastAtMs)

    private fun outside(state: State, areas: List<Area>, fix: Fix, radiusM: Int): Pair<State, Action> {
        match(areas, fix)?.let { return State(placeId = it) to Action.Enter(it) }
        val c = state.candidate
        val first = c?.fixes?.first()
        if (first == null || PlaceGeofence.distanceM(first.lat, first.lon, fix.lat, fix.lon) > radiusM) {
            return state.copy(candidate = Candidate(listOf(fix))) to Action.Keep
        }
        val grown = Candidate(c.fixes + fix)
        if (fix.atMs - grown.startMs >= SETTLE_MS && grown.fixes.size >= SETTLE_MIN_FIXES) {
            val centre = centre(grown.fixes)
            return state.copy(candidate = null) to Action.Create(centre.lat, centre.lon, centre.accuracyM ?: GOOD_ACCURACY_M, radiusM)
        }
        return state.copy(candidate = grown) to Action.Keep
    }

    /** The place containing [fix]: user-created places first, then the nearest. */
    fun match(areas: List<Area>, fix: Fix): Long? =
        areas.map { it to PlaceGeofence.distanceM(it.lat, it.lon, fix.lat, fix.lon) }
            .filter { (a, d) -> d <= a.radiusM }
            .minWithOrNull(compareBy<Pair<Area, Double>> { it.first.auto }.thenBy { it.second })
            ?.first?.placeId

    /** Accuracy-weighted mean (weight 1/accuracy²); its accuracy is the best single fix's. */
    fun centre(fixes: List<Fix>): Fix {
        var w = 0.0
        var lat = 0.0
        var lon = 0.0
        for (f in fixes) {
            val a = max(1f, f.accuracyM ?: GOOD_ACCURACY_M).toDouble()
            val wi = 1.0 / (a * a)
            w += wi
            lat += f.lat * wi
            lon += f.lon * wi
        }
        return Fix(fixes.last().atMs, lat / w, lon / w, fixes.mapNotNull { it.accuracyM }.minOrNull())
    }

    private fun exitMargin(fix: Fix): Float = max(PlaceGeofence.EXIT_MARGIN_M, fix.accuracyM ?: 0f)
}
