package app.sotreus.context.satellite

import uk.me.g4dpz.satellite.GroundStationPosition
import uk.me.g4dpz.satellite.PassPredictor
import uk.me.g4dpz.satellite.SatelliteFactory
import uk.me.g4dpz.satellite.TLE
import java.util.Date
import kotlin.math.max

/** A predicted pass over the observer. Provenance PREDICTED. */
data class SatellitePass(
    val elements: OrbitalElements,
    val startMs: Long,
    val endMs: Long,
    val maxElevationDeg: Double,
    val riseAzimuthDeg: Int,
    val setAzimuthDeg: Int,
)

/** Where a satellite is predicted to be right now, as seen from the observer. */
data class SatelliteLook(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val subLat: Double,
    val subLon: Double,
    val altitudeKm: Double,
) {
    val aboveHorizon: Boolean get() = elevationDeg > 0
}

/**
 * SGP4/SDP4 prediction with predict4java (MIT, a maintained Java port of the PREDICT algorithms).
 * Verified against Skyfield/Vallado reference values in SatellitePredictorTest.
 */
object SatellitePredictor {
    /** Passes below this peak elevation are ignored: too low to be meaningful overhead. */
    const val MIN_MAX_ELEVATION_DEG = 10.0

    private fun tle(e: OrbitalElements) = TLE(arrayOf(e.name, e.line1, e.line2))

    private fun station(lat: Double, lon: Double, altM: Double) = GroundStationPosition(lat, lon, altM)

    fun look(e: OrbitalElements, lat: Double, lon: Double, atMs: Long, altM: Double = 0.0): SatelliteLook {
        val sat = SatelliteFactory.createSatellite(tle(e))
        val p = sat.getPosition(station(lat, lon, altM), Date(atMs))
        return SatelliteLook(
            azimuthDeg = Math.toDegrees(p.azimuth),
            elevationDeg = Math.toDegrees(p.elevation),
            rangeKm = p.range,
            subLat = Math.toDegrees(p.latitude),
            subLon = Math.toDegrees(p.longitude).let { if (it > 180) it - 360 else it },
            altitudeKm = p.altitude,
        )
    }

    /**
     * Sub-satellite points at each of [times]: (latitude °, longitude °, altitude km) triples.
     * One propagator for all times, for animating and drawing orbits.
     */
    fun track(e: OrbitalElements, times: LongArray): DoubleArray {
        val out = DoubleArray(times.size * 3) { Double.NaN }
        runCatching {
            val sat = SatelliteFactory.createSatellite(tle(e))
            val origin = station(0.0, 0.0, 0.0)
            times.forEachIndexed { i, t ->
                val p = sat.getPosition(origin, Date(t))
                out[i * 3] = Math.toDegrees(p.latitude)
                out[i * 3 + 1] = Math.toDegrees(p.longitude).let { if (it > 180) it - 360 else it }
                out[i * 3 + 2] = p.altitude
            }
        }
        return out
    }

    /** Passes starting within [hours] of [fromMs] whose peak reaches [MIN_MAX_ELEVATION_DEG]. */
    fun passes(e: OrbitalElements, lat: Double, lon: Double, fromMs: Long, hours: Int, altM: Double = 0.0): List<SatellitePass> =
        runCatching {
            PassPredictor(tle(e), station(lat, lon, altM)).getPasses(Date(fromMs), max(1, hours), false)
                .filter { it.maxEl >= MIN_MAX_ELEVATION_DEG }
                .map { p -> SatellitePass(e, p.startTime.time, p.endTime.time, p.maxEl, p.aosAzimuth, p.losAzimuth) }
        }.getOrDefault(emptyList())
}
