package app.sotreus.edge.location

import kotlin.math.abs
import kotlin.math.max

/**
 * The location of one Edge sighting: where the Edge was, never where the radio was
 * (HANDOFF_V2_EDGE.md §4.1). Every located result carries its source and accuracy.
 */
sealed interface SightingLocation {
    val source: EdgeLocationSource

    /** Share of the window with an accepted fix, 0..1 (1 for a fixed position). */
    val coverage: Double

    /** Edge location ± [radiusM]. */
    data class Point(
        val lat: Double,
        val lon: Double,
        val radiusM: Double,
        val altitudeM: Double?,
        val verticalAccuracyM: Float?,
        override val coverage: Double,
        override val source: EdgeLocationSource,
    ) : SightingLocation

    /** The Edge moved more than a point allows during the window: a stretch of route. */
    data class AlongRoute(
        val startLat: Double,
        val startLon: Double,
        val endLat: Double,
        val endLon: Double,
        val pathM: Double,
        val accuracyM: Double,
        override val coverage: Double,
        override val source: EdgeLocationSource,
    ) : SightingLocation

    /** Location known for part of the window only ("location known for 2 of 5 minutes"). */
    data class Partial(
        val lat: Double,
        val lon: Double,
        val radiusM: Double,
        val coveredMs: Long,
        val windowMs: Long,
        override val coverage: Double,
        override val source: EdgeLocationSource,
    ) : SightingLocation

    data class NotKnown(override val coverage: Double = 0.0) : SightingLocation {
        override val source: EdgeLocationSource get() = EdgeLocationSource.NONE
    }
}

object SightingLocator {
    /**
     * Location for a live sample observed at [atMs]: the nearest accepted fix within ±liveJoin.
     * Uncertainty = fix accuracy + speed × |Δt|. [fixes] must already have passed [FixQuality]
     * when they arrived (together, accurate, real).
     */
    fun live(atMs: Long, fixes: List<GeoFix>, source: EdgeLocationSource, limits: QualityLimits = QualityLimits()): SightingLocation {
        val best = fixes.filter { abs(it.atMs - atMs) <= limits.liveJoinMs }.minByOrNull { abs(it.atMs - atMs) }
            ?: return SightingLocation.NotKnown()
        val acc = best.accuracyM ?: return SightingLocation.NotKnown()
        val drift = (best.speedMps ?: 0f) * abs(best.atMs - atMs) / 1000.0
        return SightingLocation.Point(best.lat, best.lon, acc + drift, best.altitudeM, best.verticalAccuracyM, 1.0, source)
    }

    /**
     * Location for a sighting window `[firstMs, lastMs]` (an Edge record, up to 5 minutes) from
     * accepted [fixes] (HANDOFF_V2_EDGE.md §4.6). Each fix covers ± liveJoin around its time;
     * coverage is the share of the window covered. No interpolation across gaps.
     */
    fun window(firstMs: Long, lastMs: Long, fixes: List<GeoFix>, source: EdgeLocationSource, limits: QualityLimits = QualityLimits()): SightingLocation {
        val half = limits.liveJoinMs
        val inWindow = fixes.filter { it.atMs in (firstMs - half)..(lastMs + half) && it.accuracyM != null }.sortedBy { it.atMs }
        if (inWindow.isEmpty()) return SightingLocation.NotKnown()
        val windowMs = lastMs - firstMs
        val covered = coveredMs(firstMs, lastMs, inWindow.map { it.atMs }, half)
        val coverage = if (windowMs <= 0) 1.0 else covered.toDouble() / windowMs
        if (coverage < limits.partialCoverage) return SightingLocation.NotKnown(coverage)

        val accuracy = median(inWindow.map { it.accuracyM!!.toDouble() })
        val span = span(inWindow)
        val (cLat, cLon) = centroid(inWindow)
        if (coverage < limits.fullCoverage) {
            return SightingLocation.Partial(cLat, cLon, max(accuracy, span / 2), covered, windowMs, coverage, source)
        }
        if (span <= limits.pointMaxSpanM) {
            val alts = inWindow.filter { it.altitudeM != null && it.verticalAccuracyM != null }
            return SightingLocation.Point(
                cLat, cLon, max(accuracy, span / 2),
                alts.takeIf { it.isNotEmpty() }?.let { median(it.map { f -> f.altitudeM!! }) },
                alts.takeIf { it.isNotEmpty() }?.let { median(it.map { f -> f.verticalAccuracyM!!.toDouble() }).toFloat() },
                coverage, source,
            )
        }
        val path = inWindow.zipWithNext { a, b -> Geo.distanceM(a, b) }.sum()
        val first = inWindow.first()
        val last = inWindow.last()
        return SightingLocation.AlongRoute(first.lat, first.lon, last.lat, last.lon, path, accuracy, coverage, source)
    }

    /** A stationary Edge's set position for any sighting. */
    fun fixed(lat: Double, lon: Double, accuracyM: Float): SightingLocation =
        SightingLocation.Point(lat, lon, accuracyM.toDouble(), null, null, 1.0, EdgeLocationSource.EDGE_FIXED)

    internal fun coveredMs(firstMs: Long, lastMs: Long, times: List<Long>, half: Long): Long {
        var covered = 0L
        var cursor = firstMs
        for (t in times.sorted()) {
            val start = maxOf(cursor, t - half, firstMs)
            val end = minOf(t + half, lastMs)
            if (end > start) {
                covered += end - start
                cursor = end
            }
        }
        return covered
    }

    /** Largest distance between any two fixes (fixes thinned to at most 60 for speed). */
    internal fun span(fixes: List<GeoFix>): Double {
        val step = (fixes.size + 59) / 60
        val thin = fixes.filterIndexed { i, _ -> i % step.coerceAtLeast(1) == 0 } + fixes.last()
        var best = 0.0
        for (i in thin.indices) for (j in i + 1 until thin.size) best = max(best, Geo.distanceM(thin[i], thin[j]))
        return best
    }

    private fun centroid(fixes: List<GeoFix>): Pair<Double, Double> {
        // Inverse-variance weights: a ±5 m fix counts more than a ±20 m one.
        val w = fixes.map { 1.0 / (it.accuracyM!!.toDouble().coerceAtLeast(1.0).let { a -> a * a }) }
        val sum = w.sum()
        return fixes.indices.sumOf { fixes[it].lat * w[it] } / sum to fixes.indices.sumOf { fixes[it].lon * w[it] } / sum
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
