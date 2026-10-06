package app.sotreus.context

import app.sotreus.context.satellite.CelesTrakCatalog
import app.sotreus.context.satellite.OrbitalElements
import app.sotreus.context.satellite.SatellitePredictor
import app.sotreus.core.model.SatelliteGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * SGP4 verification (architecture handoff §14.3). Expected values were computed independently with
 * Skyfield (Rhodes' implementation of Vallado's SGP4, WGS84 subpoints) for these exact element sets,
 * observer at 1.2834° N, 103.8607° E, 0 m.
 */
class SatellitePredictorTest {
    private val lat = 1.2834
    private val lon = 103.8607

    private val iss = OrbitalElements(
        "ISS (ZARYA)",
        "1 25544U 98067A   26278.82086582  .00005030  00000+0  10025-3 0  9992",
        "2 25544  51.6314 112.5837 0006860 227.0704 132.9710 15.48745232588905",
        SatelliteGroup.SPACE_STATIONS,
    )
    private val landsat8 = OrbitalElements(
        "LANDSAT 8",
        "1 39084U 13008A   26278.92660379  .00000169  00000+0  47528-4 0  9998",
        "2 39084  98.2194 347.6818 0001312  94.5046 265.6302 14.57108643714028",
        SatelliteGroup.EARTH_OBSERVATION,
    )

    private data class Ref(val atMs: Long, val subLat: Double, val subLon: Double, val altKm: Double, val az: Double, val el: Double, val rangeKm: Double)

    private val issRefs = listOf(
        Ref(1791229322806, 0.0001, 162.5774, 424.32, 90.77, -26.088, 6473.48),
        Ref(1791231122806, 44.8517, -76.6033, 426.54, 0.459, -66.25, 12128.5),
        Ref(1791234722806, -8.8835, 132.6381, 425.15, 109.953, -8.443, 3480.49),
    )
    private val landsatRefs = listOf(
        Ref(1791238458567, 0.0, -0.4944, 705.58, 270.336, -49.846, 10642.25),
        Ref(1791240258567, 69.1124, -165.8782, 713.57, 21.004, -41.429, 9433.02),
        Ref(1791243858567, -32.112, -17.8211, 715.16, 234.289, -56.77, 11500.04),
    )

    private fun angleDiff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

    private fun check(e: OrbitalElements, refs: List<Ref>) {
        refs.forEach { r ->
            val look = SatellitePredictor.look(e, lat, lon, r.atMs)
            assertEquals("${e.name} subLat @${r.atMs}", r.subLat, look.subLat, 0.2)
            assertTrue("${e.name} subLon @${r.atMs}: ${look.subLon} vs ${r.subLon}", angleDiff(look.subLon, r.subLon) < 0.2)
            assertEquals("${e.name} altitude", r.altKm, look.altitudeKm, 3.0)
            assertTrue("${e.name} azimuth ${look.azimuthDeg} vs ${r.az}", angleDiff(look.azimuthDeg, r.az) < 0.5)
            assertEquals("${e.name} elevation", r.el, look.elevationDeg, 0.5)
            assertEquals("${e.name} range", r.rangeKm, look.rangeKm, 15.0)
        }
    }

    @Test fun issPositionsMatchSkyfield() = check(iss, issRefs)

    @Test fun landsatPositionsMatchSkyfield() = check(landsat8, landsatRefs)

    @Test
    fun passPeaksMatchSkyfieldCulminations() {
        // (culmination time, peak elevation) from Skyfield find_events over 24 h from each epoch.
        val expected = mapOf(
            iss to listOf(1791240381527L to 14.97, 1791246178965L to 17.43, 1791282131179L to 12.83, 1791287928617L to 19.66),
            landsat8 to listOf(1791253314465L to 16.48, 1791259162093L to 24.88, 1791297838407L to 32.93, 1791303685813L to 12.14),
        )
        expected.forEach { (e, culms) ->
            val passes = SatellitePredictor.passes(e, lat, lon, e.epochMs, 24)
            culms.forEach { (t, peak) ->
                val p = passes.firstOrNull { t in it.startMs..it.endMs }
                    ?: throw AssertionError("${e.name}: no predicted pass contains culmination at $t; got ${passes.map { it.startMs to it.endMs }}")
                assertEquals("${e.name} peak elevation", peak, p.maxElevationDeg, 0.75)
            }
        }
    }

    @Test
    fun parsesThreeLineCatalogAndEpoch() {
        val text = "${iss.name}\n${iss.line1}\n${iss.line2}\n${landsat8.name}\n${landsat8.line1}\n${landsat8.line2}\n"
        val parsed = CelesTrakCatalog.parse(text, SatelliteGroup.SPACE_STATIONS)
        assertEquals(listOf(25544, 39084), parsed.map { it.noradId })
        // 26278.82086582 = 2026 day 278.82086582 = 5 Oct 2026 19:42:02.806 UTC.
        assertTrue("epoch ${iss.epochMs}", abs(iss.epochMs - 1791229322806L) <= 1L)
    }
}
