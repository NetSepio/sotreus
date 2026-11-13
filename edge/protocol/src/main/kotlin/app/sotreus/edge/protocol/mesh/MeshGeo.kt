package app.sotreus.edge.protocol.mesh

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distance and bearing between this phone and a node's reported position (HANDOFF_V3_MESH.md
 * §3.2). Computed from the two positions only, never from RSSI or SNR. Rounded to what the
 * positions can support, and withheld when either is unknown or stale.
 */
object MeshGeo {
    /** A reported position older than this is stale: shown hollow, with no distance. */
    const val STALE_AFTER_MS = 2 * 60 * 60 * 1000L

    private const val EARTH_RADIUS_M = 6_371_000.0

    enum class Compass { N, NE, E, SE, S, SW, W, NW }

    /**
     * [metres] is rounded to [stepM]. [compass] is null when the node may be anywhere around this
     * phone (the uncertainty is larger than the distance): the UI then says "within ±…".
     */
    data class Between(val metres: Double, val stepM: Double, val compass: Compass?, val uncertaintyM: Double)

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from the first point to the second, 0–360° clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun compass(deg: Double): Compass = Compass.entries[(((deg % 360 + 360) % 360 + 22.5) / 45.0).toInt() % 8]

    /**
     * From this phone ([phoneLat], [phoneLon], ± [phoneAccuracyM]) to [node]. Null when either side is
     * missing or the report is stale at [nowMs] (a report without a time counts as stale only when
     * [nodeHeardAtMs] is also old).
     */
    fun between(
        phoneLat: Double?, phoneLon: Double?, phoneAccuracyM: Double?,
        node: MeshPosition?, nodeHeardAtMs: Long?, nowMs: Long,
    ): Between? {
        if (phoneLat == null || phoneLon == null || node == null) return null
        val reportedAtMs = node.reportedUnixS?.let { it * 1000 } ?: nodeHeardAtMs ?: return null
        if (nowMs - reportedAtMs > STALE_AFTER_MS) return null
        val d = distanceM(phoneLat, phoneLon, node.lat, node.lon)
        val uncertainty = (node.precisionM ?: 0.0) + (phoneAccuracyM ?: 0.0)
        val step = when {
            d >= 10_000 || uncertainty >= 1_000 -> 1_000.0
            d >= 1_000 || uncertainty >= 100 -> 100.0
            else -> 10.0
        }
        val rounded = max(step, (d / step).roundToLong() * step)
        val compass = if (d > uncertainty) compass(bearingDeg(phoneLat, phoneLon, node.lat, node.lon)) else null
        return Between(rounded, step, compass, uncertainty)
    }

    fun isStale(position: MeshPosition?, nodeHeardAtMs: Long?, nowMs: Long): Boolean {
        val at = position?.reportedUnixS?.let { it * 1000 } ?: nodeHeardAtMs ?: return true
        return nowMs - at > STALE_AFTER_MS
    }
}
