package app.sotreus.context

import app.sotreus.context.environment.EnvironmentGeo
import app.sotreus.context.environment.GdacsFeed
import app.sotreus.context.environment.OpenMeteoAir
import app.sotreus.context.environment.UsgsFeed
import app.sotreus.core.model.AirQualityMode
import app.sotreus.core.model.Hazard
import app.sotreus.core.model.HazardLevel
import app.sotreus.core.model.HazardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentParsersTest {
    private fun fixture(name: String) = javaClass.getResource("/environment/$name")!!.readText()

    @Test
    fun usgsKeepsEveryQuakeWithItsPointAndDepth() {
        val quakes = UsgsFeed.parse(fixture("usgs.json"))
        assertEquals(listOf("us7000tuht", "us7000tuhs", "pr71538018"), quakes.map { it.id })
        val q = quakes.first()
        assertEquals(4.9, q.magnitude, 0.0)
        assertEquals(-13.7894, q.lat, 1e-9)
        assertEquals(165.2951, q.lon, 1e-9)
        assertEquals(10.0, q.depthKm!!, 0.0)
        assertTrue(q.url!!.startsWith("https://earthquake.usgs.gov/"))
    }

    @Test
    fun gdacsKeepsCurrentEventsAndDropsDroughts() {
        val hazards = GdacsFeed.parse(fixture("gdacs.json"))
        assertEquals(listOf("EQ-1574916", "TC-1001343"), hazards.map { it.id })
        val tc = hazards[1]
        assertEquals(HazardType.TROPICAL_CYCLONE, tc.type)
        assertEquals(HazardLevel.GREEN, tc.level)
        assertTrue(tc.toMs >= tc.fromMs)
        assertTrue(tc.url!!.startsWith("https://www.gdacs.org/report.aspx"))
    }

    @Test
    fun openMeteoReadsCurrentValues() {
        val air = OpenMeteoAir.parse(fixture("air.json"))!!
        assertEquals(78, air.europeanAqi)
        assertEquals(88, air.usAqi)
        assertEquals(22.0, air.pm25!!, 0.0)
        assertEquals(1_793_440_800_000L, air.modelTimeMs)
        assertNull(OpenMeteoAir.parse("""{"latitude":1.3}"""))
    }

    @Test
    fun roundingSendsOnlyACellCentre() {
        val here = GeoPoint(1.352_083, 103.819_839)
        val coarse = EnvironmentGeo.round(here, AirQualityMode.COARSE)!!
        assertEquals(1.25, coarse.lat, 0.0)
        assertEquals(103.75, coarse.lon, 0.0)
        assertEquals("1.25", coarse.latText)
        val fine = EnvironmentGeo.round(here, AirQualityMode.FINE)!!
        assertEquals(1.35, fine.lat, 1e-9)
        assertEquals(103.85, fine.lon, 1e-9)
        assertNull(EnvironmentGeo.round(here, AirQualityMode.OFF))
        // A nearby phone in the same cell sends the same point.
        assertEquals(coarse, EnvironmentGeo.round(GeoPoint(1.49, 103.51), AirQualityMode.COARSE))
        val south = EnvironmentGeo.round(GeoPoint(-33.8688, 151.2093), AirQualityMode.COARSE)!!
        assertEquals(-33.75, south.lat, 0.0)
        assertEquals(151.25, south.lon, 0.0)
    }

    @Test
    fun hazardsUseAPerTypeRadiusAndListHigherLevelsFirst() {
        val here = GeoPoint(0.0, 0.0)
        fun h(id: String, type: HazardType, level: HazardLevel, lat: Double) = Hazard(id, type, level, id, null, 0, 0, lat, 0.0, null)
        val near = EnvironmentGeo.nearbyHazards(
            listOf(
                h("cyclone", HazardType.TROPICAL_CYCLONE, HazardLevel.GREEN, 3.0), // ~330 km, inside 400
                h("far-cyclone", HazardType.TROPICAL_CYCLONE, HazardLevel.RED, 7.8), // ~867 km, outside 400
                h("fire", HazardType.WILDFIRE, HazardLevel.RED, 1.5), // ~167 km, outside 100
                h("flood", HazardType.FLOOD, HazardLevel.ORANGE, 1.5), // ~167 km, inside 200
            ),
            here,
        )
        assertEquals(listOf("flood", "cyclone"), near.map { it.item.id })
    }

    @Test
    fun quakesOutsideTheRadiusAreLeftOff() {
        val quakes = UsgsFeed.parse(fixture("usgs.json"))
        val near = EnvironmentGeo.nearbyQuakes(quakes, GeoPoint(-13.8, 165.3), 100.0)
        assertEquals(setOf("us7000tuht", "us7000tuhs"), near.map { it.item.id }.toSet())
        assertTrue(near.all { it.distanceKm < 100 })
    }
}
