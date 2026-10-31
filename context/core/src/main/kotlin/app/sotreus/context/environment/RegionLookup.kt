package app.sotreus.context.environment

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Which official warning service covers a point, and the only thing sent to it: a region code. */
sealed interface WarningRegion {
    /** NWS area code: a US state, DC or territory, e.g. "CA", "PR". */
    data class Nws(val area: String) : WarningRegion
    /** MeteoAlarm country feed name, e.g. "germany". */
    data class MeteoAlarm(val country: String) : WarningRegion
}

/**
 * Point-in-polygon lookup over the bundled Natural Earth boundaries (assets/regions/warning_regions.bin,
 * built by tools/geodata/build_region_assets.py). Runs on the phone; boundaries are simplified to about
 * 1 km, and a coastal point takes the nearest region within 10 km, so a city on a border
 * (Ciudad Juárez, El Paso) can resolve to the neighbouring region.
 */
class RegionLookup(bytes: ByteArray) {
    private class Region(val code: String, val south: Double, val west: Double, val north: Double, val east: Double, val rings: List<IntArray>)

    private val regions: List<Region> = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { b ->
        List(b.int) {
            val code = ByteArray(b.get().toInt() and 0xff).also { b.get(it) }.toString(Charsets.UTF_8)
            val south = b.float.toDouble()
            val west = b.float.toDouble()
            val north = b.float.toDouble()
            val east = b.float.toDouble()
            val rings = List(b.int) { IntArray(b.int * 2) { b.int } }
            Region(code, south, west, north, east, rings)
        }
    }

    /**
     * The region code containing the point ("US-CA", "DE"). A point just off a simplified coastline
     * (a harbour, a beach) takes the nearest region within [COAST_KM]. Null outside every covered region.
     */
    fun codeAt(lat: Double, lon: Double): String? {
        val y = (lat * 1e5).toInt()
        val x = (lon * 1e5).toInt()
        regions.firstOrNull { r -> lat in r.south..r.north && lon in r.west..r.east && inside(r.rings, y, x) }?.let { return it.code }
        val pad = COAST_KM / 111.0 * 2
        return regions.filter { r -> lat in r.south - pad..r.north + pad && lon in r.west - pad * 3..r.east + pad * 3 }
            .map { it.code to edgeKm(it.rings, lat, lon) }
            .filter { it.second <= COAST_KM }
            .minByOrNull { it.second }?.first
    }

    /** Shortest distance from the point to any ring edge, in km (local flat approximation). */
    private fun edgeKm(rings: List<IntArray>, lat: Double, lon: Double): Double {
        val kx = 111.32 * kotlin.math.cos(Math.toRadians(lat))
        val ky = 110.57
        var best = Double.MAX_VALUE
        for (r in rings) {
            var i = 0
            while (i + 3 < r.size) {
                val ax = (r[i + 1] / 1e5 - lon) * kx
                val ay = (r[i] / 1e5 - lat) * ky
                val bx = (r[i + 3] / 1e5 - lon) * kx
                val by = (r[i + 2] / 1e5 - lat) * ky
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                best = minOf(best, kotlin.math.hypot(ax + t * dx, ay + t * dy))
                i += 2
            }
        }
        return best
    }

    fun warningRegion(lat: Double, lon: Double): WarningRegion? {
        val code = codeAt(lat, lon) ?: return null
        return when {
            code.startsWith("US-") -> WarningRegion.Nws(code.removePrefix("US-"))
            code in US_TERRITORIES -> WarningRegion.Nws(code)
            else -> WarningRegions.meteoAlarmFor(code)?.let { WarningRegion.MeteoAlarm(it) }
        }
    }

    /** Even-odd rule over every ring of the region, so holes count as outside. */
    private fun inside(rings: List<IntArray>, y: Int, x: Int): Boolean {
        var odd = false
        for (r in rings) {
            var j = r.size - 2
            var i = 0
            while (i < r.size) {
                val yi = r[i].toLong()
                val xi = r[i + 1].toLong()
                val yj = r[j].toLong()
                val xj = r[j + 1].toLong()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi).toDouble() + xi) odd = !odd
                j = i
                i += 2
            }
        }
        return odd
    }

    companion object {
        private val US_TERRITORIES = setOf("PR", "GU", "VI", "AS", "MP")
        const val COAST_KM = 10.0
    }
}
