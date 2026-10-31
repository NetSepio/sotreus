package app.sotreus.context.environment

import app.sotreus.context.Geo
import app.sotreus.context.GeoPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A human name for where the phone is. Any part can be missing (open sea, a place far from towns). */
data class AreaName(val town: String?, val region: String?, val country: String?)

/**
 * Names the area around a point on the phone, from Natural Earth (assets/regions/countries.bin and
 * places.bin, built by tools/geodata/build_place_names.py). The country comes from its border; the
 * town is the nearest listed town in that country within [TOWN_KM], with its state or province.
 * Nothing is sent to a geocoding service.
 */
class AreaNames(countriesBin: ByteArray, placesBin: ByteArray) {
    private val countries = RegionLookup(countriesBin)
    private val countryNames: Map<String, String>
    private val lats: IntArray
    private val lons: IntArray
    private val codes: Array<String>
    private val names: Array<String>
    private val regions: Array<String>

    init {
        val b = ByteBuffer.wrap(placesBin).order(ByteOrder.LITTLE_ENDIAN)
        fun str() = ByteArray(b.get().toInt() and 0xff).also { b.get(it) }.toString(Charsets.UTF_8)
        countryNames = (0 until b.int).associate { str() to str() }
        val n = b.int
        lats = IntArray(n)
        lons = IntArray(n)
        codes = Array(n) { "" }
        names = Array(n) { "" }
        regions = Array(n) { "" }
        for (i in 0 until n) {
            lats[i] = b.int
            lons[i] = b.int
            b.int // population, kept in the file for later ranking
            codes[i] = str()
            names[i] = str()
            regions[i] = str()
        }
    }

    fun at(lat: Double, lon: Double): AreaName? {
        val code = countries.codeAt(lat, lon)
        val here = GeoPoint(lat, lon)
        val window = (TOWN_KM / 111.0 * 1e5).toInt()
        val y = (lat * 1e5).toInt()
        var best = -1
        var bestKm = Double.MAX_VALUE
        var i = lower(y - window)
        while (i < lats.size && lats[i] <= y + window) {
            if (code == null || codes[i] == code) {
                val km = Geo.distanceKm(here, GeoPoint(lats[i] / 1e5, lons[i] / 1e5))
                if (km < bestKm) {
                    bestKm = km
                    best = i
                }
            }
            i++
        }
        val town = best.takeIf { it >= 0 && bestKm <= TOWN_KM }
        val countryCode = code ?: town?.let { codes[it] }
        if (town == null && countryCode == null) return null
        return AreaName(
            town = town?.let { names[it] },
            region = town?.let { regions[it] }?.takeIf { it.isNotBlank() && it != names[town] },
            country = countryCode?.let { countryNames[it] },
        )
    }

    private fun lower(v: Int): Int {
        var lo = 0
        var hi = lats.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lats[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val TOWN_KM = 50.0
    }
}
