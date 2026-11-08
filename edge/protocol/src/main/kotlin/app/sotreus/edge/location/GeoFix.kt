package app.sotreus.edge.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One position fix, from this phone or (future) from an Edge with GNSS. Every optional field is
 * null when the source did not provide it; nothing is estimated to fill it (HANDOFF_V2_EDGE.md §4.5).
 */
data class GeoFix(
    val atMs: Long,
    val lat: Double,
    val lon: Double,
    /** Horizontal accuracy, metres (68 %). Null = unknown, and such a fix is never used. */
    val accuracyM: Float?,
    /** WGS84 ellipsoid altitude. */
    val altitudeM: Double? = null,
    val verticalAccuracyM: Float? = null,
    /** Mean-sea-level altitude (Android 14+). */
    val mslAltitudeM: Double? = null,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    /** `gps`, `fused`, `network`, `edge`… */
    val provider: String? = null,
    val satellitesUsed: Int? = null,
    val satellitesInView: Int? = null,
    val mock: Boolean = false,
    /** Phone barometer, when it has one. */
    val pressureHpa: Float? = null,
)

/** Where the location of an Edge sighting came from (HANDOFF_V2_EDGE.md §4.2). */
enum class EdgeLocationSource {
    /** The Edge's own GNSS fix. */
    EDGE_GNSS,

    /** A stationary Edge's set position. */
    EDGE_FIXED,

    /** This phone's fix while phone and Edge were together (travel). */
    PHONE_WITH_EDGE,

    NONE,
}

object Geo {
    private const val EARTH_M = 6_371_008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun distanceM(a: GeoFix, b: GeoFix): Double = distanceM(a.lat, a.lon, b.lat, b.lon)
}
