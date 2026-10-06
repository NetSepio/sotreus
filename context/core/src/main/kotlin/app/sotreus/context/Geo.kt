package app.sotreus.context

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

/** A bounding box. The only location data a context provider ever receives. */
data class GeoArea(val latMin: Double, val lonMin: Double, val latMax: Double, val lonMax: Double) {
    val center: GeoPoint get() = GeoPoint((latMin + latMax) / 2, (lonMin + lonMax) / 2)
}

object Geo {
    private const val EARTH_KM = 6371.0088

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_KM * asin(sqrt(h))
    }

    /** Box of [radiusKm] around [p]. */
    fun around(p: GeoPoint, radiusKm: Double): GeoArea {
        val dLat = radiusKm / 111.32
        val dLon = radiusKm / (111.32 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.01))
        return GeoArea(p.lat - dLat, p.lon - dLon, p.lat + dLat, p.lon + dLon)
    }

    /**
     * Coarse privacy mode: the 1°×1° grid cell containing [p], padded by one cell on each side
     * (about 330 km across at the equator). Many places share the same request, and the query
     * never narrows the location below a degree.
     */
    fun coarseArea(p: GeoPoint): GeoArea {
        val lat0 = floor(p.lat)
        val lon0 = floor(p.lon)
        return GeoArea((lat0 - 1).coerceAtLeast(-90.0), (lon0 - 1).coerceAtLeast(-180.0), (lat0 + 2).coerceAtMost(90.0), (lon0 + 2).coerceAtMost(180.0))
    }

}
