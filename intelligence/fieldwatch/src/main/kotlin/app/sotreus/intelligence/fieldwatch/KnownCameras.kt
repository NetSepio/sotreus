/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit 4f74cec).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed; adapted to Sotreus modules. The spoken "camera ahead"
 * warning (FlockLatch, FlockApproach.classify) and the canvas mark packing are not ported; Sotreus
 * draws marks with MapLibre and does not speak warnings.
 */
package app.sotreus.intelligence.fieldwatch

import kotlin.math.abs
import kotlin.math.cos

/**
 * One saved roadside sensor. The order is the compact-file byte. Do not reorder it.
 * [PLATE] is a license plate reader. [CAMERA] is every other mapped camera.
 */
enum class SensorKind {
    PLATE,
    SPEED,
    RED,
    GUNSHOT,
    CAMERA,
}

/**
 * One sensor from the saved list.
 * [key] is the OSM id. A way uses the negative id. A relation uses a different negative range.
 * [facings] are degrees clockwise from true north. Empty means the file had no direction.
 * [flock] is true when the brand or operator says Flock. [brand] is the company name to show.
 * [kind] says which mark applies. A gunshot detector stores no direction.
 */
data class FlockCamera(
    val key: Long,
    val lat: Double,
    val lon: Double,
    val facings: List<Int>,
    val brand: String = "",
    val flock: Boolean = false,
    val kind: SensorKind = SensorKind.PLATE,
) {
    fun forStore(): FlockCamera {
        if (kind != SensorKind.GUNSHOT || facings.isEmpty()) return this
        return copy(facings = emptyList())
    }
}

/** Fixed distances and angles shared by the parser and the path count. */
object FlockApproach {
    /** Cameras counted along a saved path. */
    const val PATH_M = 150.0
    const val LOOK_DEG = 60.0
    /** A list with fewer cameras than this is treated as broken and never replaces a saved one. */
    const val MIN_COUNT = 1_000
    const val MAX_FACINGS = 4

    fun delta(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    fun norm(deg: Double): Int? {
        if (!deg.isFinite()) return null
        var turned = deg % 360.0
        if (turned < 0.0) turned += 360.0
        val rounded = (turned + 0.5).toInt() % 360
        return if (rounded < 0) rounded + 360 else rounded
    }
}

/** Compiled credit. The camera locations are OpenStreetMap data (ODbL 1.0). */
object FlockCredit {
    const val LINE = "© OpenStreetMap contributors"
    const val URL = "https://www.openstreetmap.org/copyright"
}

/**
 * Cameras sorted by latitude for fast box and radius queries. Sotreus asks for the cameras around
 * the path on screen; MapLibre clusters what it is given.
 */
class CameraIndex(cameras: List<FlockCamera>) {
    private val sorted = cameras.sortedWith(compareBy({ it.lat }, { it.lon }, { it.key }))
    private val lats = DoubleArray(sorted.size) { sorted[it].lat }

    val size: Int get() = sorted.size

    /** Cameras inside the box, at most [cap]. West > east crosses the antimeridian. */
    fun inBox(south: Double, west: Double, north: Double, east: Double, cap: Int = Int.MAX_VALUE): List<FlockCamera> {
        if (sorted.isEmpty() || north < south) return emptyList()
        val out = ArrayList<FlockCamera>()
        for (i in lower(south) until upper(north)) {
            val c = sorted[i]
            val inLon = if (west <= east) c.lon in west..east else c.lon >= west || c.lon <= east
            if (!inLon) continue
            out.add(c)
            if (out.size >= cap) break
        }
        return out
    }

    fun near(lat: Double, lon: Double, radiusM: Double): List<FlockCamera> {
        if (sorted.isEmpty() || !lat.isFinite() || !lon.isFinite() || radiusM <= 0.0) return emptyList()
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return inBox(lat - dLat, lon - dLon, lat + dLat, lon + dLon).filter { Geo.meters(lat, lon, it.lat, it.lon) <= radiusM }
    }

    private fun lower(v: Double): Int {
        var lo = 0
        var hi = lats.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lats[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upper(v: Double): Int {
        var lo = 0
        var hi = lats.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lats[mid] <= v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY = CameraIndex(emptyList())
    }
}
