package app.sotreus.edge.location

import app.sotreus.edge.protocol.DeploymentMode
import app.sotreus.edge.protocol.EdgeCapabilities
import app.sotreus.edge.protocol.EdgePlacement
import kotlin.math.max
import kotlin.math.sqrt

/** Which source tags this Edge's sightings right now (HANDOFF_V2_EDGE.md §4.2). */
object LocationResolver {
    /**
     * @param edgeHasFix an Edge with GNSS currently has a fix.
     * @param placementHeld a moved check is waiting for the owner's answer.
     * @param phoneTagging the owner's "Tag sightings with this phone's location" switch.
     * @param gnssPhoneFallback decision 11: a GNSS Edge without a fix may use the phone, labelled.
     */
    fun resolve(
        caps: EdgeCapabilities,
        placement: EdgePlacement?,
        edgeHasFix: Boolean,
        placementHeld: Boolean,
        phoneTagging: Boolean,
        gnssPhoneFallback: Boolean = true,
    ): EdgeLocationSource = when {
        caps.gnss && edgeHasFix -> EdgeLocationSource.EDGE_GNSS
        placement?.mode == DeploymentMode.STATIONARY ->
            if (placement.positioned && !placementHeld) EdgeLocationSource.EDGE_FIXED else EdgeLocationSource.NONE
        caps.gnss && gnssPhoneFallback && phoneTagging -> EdgeLocationSource.PHONE_WITH_EDGE
        caps.gnss -> EdgeLocationSource.NONE
        phoneTagging -> EdgeLocationSource.PHONE_WITH_EDGE
        else -> EdgeLocationSource.NONE
    }

    /** True when the phone's location is in use for Edge data, so location updates should run. */
    fun needsPhoneLocation(source: EdgeLocationSource): Boolean = source == EdgeLocationSource.PHONE_WITH_EDGE
}

/**
 * "Use this phone's location" for a stationary Edge (HANDOFF_V2_EDGE.md §4.3): averages fixes for
 * up to 30 s. The accuracy claimed is the worse of the best single fix and the spread of the
 * fixes, so averaging never claims more than the fixes support.
 */
class PlacementAverager(private val limits: QualityLimits = QualityLimits()) {
    private val fixes = ArrayList<GeoFix>()

    /** [accepted]: within [limitM]. An estimate outside it may still be saved, with this same accuracy. */
    data class Estimate(val lat: Double, val lon: Double, val accuracyM: Float, val fixes: Int, val accepted: Boolean, val limitM: Float = 0f) {
        /**
         * The placement to store: accepted, or [anyway] when the owner saves it regardless. Always
         * with the accuracy measured, never a better one.
         */
        fun toPlacement(nowMs: Long, anyway: Boolean = false): EdgePlacement? =
            if (accepted || anyway) EdgePlacement(DeploymentMode.STATIONARY, lat, lon, accuracyM, nowMs, PlacementSources.PHONE) else null
    }

    fun add(fix: GeoFix) {
        if (fix.mock || fix.accuracyM == null || fix.accuracyM > 100f) return
        fixes += fix
    }

    fun estimate(): Estimate? {
        if (fixes.isEmpty()) return null
        val w = fixes.map { 1.0 / (it.accuracyM!!.toDouble().coerceAtLeast(1.0).let { a -> a * a }) }
        val sum = w.sum()
        val lat = fixes.indices.sumOf { fixes[it].lat * w[it] } / sum
        val lon = fixes.indices.sumOf { fixes[it].lon * w[it] } / sum
        val spread = sqrt(fixes.sumOf { Geo.distanceM(lat, lon, it.lat, it.lon).let { d -> d * d } } / fixes.size)
        val best = fixes.minOf { it.accuracyM!!.toDouble() }
        val acc = max(best, spread).toFloat()
        return Estimate(lat, lon, acc, fixes.size, acc <= limits.stationaryAcceptM, limits.stationaryAcceptM)
    }

    /** The placement to store on the Edge, once [estimate] is accepted. */
    fun placement(nowMs: Long): EdgePlacement? = estimate()?.toPlacement(nowMs)

    fun clear() = fixes.clear()
}

/** `placement.source` values the app writes. */
object PlacementSources {
    const val PHONE = "phone"
    const val MAP = "map"
}

/**
 * A position picked on a map or typed in (HANDOFF_V2_EDGE.md §4.3, source "map"). The accuracy is
 * what the pick can support, never better than [MIN_ACCURACY_M] for a map or [TYPED_MIN_ACCURACY_M]
 * for typed coordinates.
 */
object ManualPosition {
    const val MIN_ACCURACY_M = 10f
    const val TYPED_MIN_ACCURACY_M = 25f

    /** Half the pin's halo: how far off a finger may place it. */
    const val PIN_RADIUS_DP = 13.0

    /**
     * A map pick at [zoom] (MapLibre, 512-px tiles): the ground covered by [pinRadiusDp] at [lat],
     * at least [MIN_ACCURACY_M].
     */
    fun mapAccuracyM(lat: Double, zoom: Double, pinRadiusDp: Double = PIN_RADIUS_DP): Float {
        val metersPerDp = EARTH_CIRCUMFERENCE_M * kotlin.math.cos(Math.toRadians(lat)) / (512.0 * Math.pow(2.0, zoom))
        return max(MIN_ACCURACY_M.toDouble(), kotlin.math.ceil(metersPerDp * pinRadiusDp)).toFloat()
    }

    data class Typed(val lat: Double, val lon: Double, val accuracyM: Float)

    /**
     * "52.37021, 4.89517" (comma, semicolon or space between). Null when it is not a latitude and a
     * longitude. Fewer decimals mean a wider accuracy: half of the last digit's step, at least
     * [TYPED_MIN_ACCURACY_M].
     */
    fun parseTyped(text: String): Typed? {
        val parts = text.trim().split(Regex("""\s*[,;]\s*|\s+""")).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        val decimals = minOf(decimals(parts[0]), decimals(parts[1]))
        val step = METERS_PER_DEGREE / Math.pow(10.0, decimals.toDouble())
        return Typed(lat, lon, max(TYPED_MIN_ACCURACY_M.toDouble(), kotlin.math.ceil(step / 2)).toFloat())
    }

    private fun decimals(s: String): Int = s.substringAfter('.', "").length

    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    private const val METERS_PER_DEGREE = 111_320.0
}

/** Moved checks for a stationary Edge (HANDOFF_V2_EDGE.md §4.3). */
object MovedChecks {
    /** The strong Wi-Fi neighbourhood: the top [n] access points by strongest signal heard. */
    fun baseline(strongestByBssid: Map<String, Int>, n: Int = 20): Set<String> =
        strongestByBssid.entries.sortedByDescending { it.value }.take(n).map { it.key.uppercase() }.toSet()

    /** Share of [baseline] still heard (any strength) in [recentBssids], or null if the baseline is too small to judge. */
    fun overlap(baseline: Set<String>, recentBssids: Set<String>, limits: QualityLimits = QualityLimits()): Double? {
        if (baseline.size < limits.movedBaselineMin) return null
        val recent = recentBssids.map { it.uppercase() }.toSet()
        return baseline.count { it in recent }.toDouble() / baseline.size
    }

    fun neighbourhoodChanged(baseline: Set<String>, recentBssids: Set<String>, limits: QualityLimits = QualityLimits()): Boolean =
        overlap(baseline, recentBssids, limits)?.let { it < limits.movedOverlap } ?: false

    /** A phone that is with the Edge and has a good fix far from the set position. */
    fun phoneDisagrees(placement: EdgePlacement, fix: GeoFix, together: Boolean, limits: QualityLimits = QualityLimits()): Boolean {
        if (!placement.positioned || !together || fix.mock) return false
        val acc = fix.accuracyM ?: return false
        if (acc > limits.maxAccuracyM) return false
        val d = Geo.distanceM(placement.lat!!, placement.lon!!, fix.lat, fix.lon)
        return d - acc - placement.accuracyM!! > limits.movedCrossCheckM
    }
}
