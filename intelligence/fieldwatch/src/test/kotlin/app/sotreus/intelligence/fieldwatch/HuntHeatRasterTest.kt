package app.sotreus.intelligence.fieldwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuntHeatRasterTest {
    private val lat0 = 47.6
    private val lon0 = -122.3

    /** About [m] meters east of the start. */
    private fun east(m: Double) = lon0 + m / (111_320.0 * kotlin.math.cos(Math.toRadians(lat0)))

    @Test
    fun noMeasuredHearDrawsNothing() {
        assertNull(rasterHuntHeat(emptyList()))
        assertNull(rasterHuntHeat(listOf(GpsSample(1L, lat0, lon0, 127))))
    }

    @Test
    fun theHotSpotSitsAtTheLoudEndOfAWalk() {
        val walk = (0..20).map { i -> GpsSample(i * 1_000L, lat0, east(i * 2.0), -95 + i * 2) }
        val field = rasterHuntHeat(walk)!!
        assertEquals(field.cols * field.rows, field.pixels.size)
        assertTrue(field.north > field.south && field.east > field.west)
        val hot = field.hotLon!!
        val metersFromLoudEnd = Geo.meters(lat0, hot, lat0, east(40.0))
        assertTrue("hot spot $metersFromLoudEnd m from the loud end", metersFromLoudEnd < 6.0)
    }

    @Test
    fun theSameWalkAlwaysDrawsTheSamePicture() {
        val walk = (0..12).map { i -> GpsSample(i * 1_000L, lat0 + i * 1e-5, east(i * 1.5), -80 + (i % 4) * 5) }
        val a = rasterHuntHeat(walk)!!
        val b = rasterHuntHeat(walk)!!
        assertTrue(a.pixels.contentEquals(b.pixels))
        assertEquals(a.hotLat, b.hotLat)
    }
}
