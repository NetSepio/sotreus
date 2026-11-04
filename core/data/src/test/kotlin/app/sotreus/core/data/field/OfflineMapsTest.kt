package app.sotreus.core.data.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OfflineMapsTest {
    @Test fun roundingHidesThePreciseFixButStaysInsideTheSmallestArea() {
        val lat = 37.774_93; val lon = -122.419_42
        val rLat = OfflineMaps.round(lat); val rLon = OfflineMaps.round(lon)
        assertEquals(37.75, rLat, 1e-9); assertEquals(-122.40, rLon, 1e-9)
        assertTrue(abs(rLat - lat) <= OfflineMaps.ROUND_DEG / 2 + 1e-9)
        val (s, w, n, e) = OfflineMaps.box(rLat, rLon, OfflineAreaSize.SMALL.km)
        assertTrue(lat in s..n && lon in w..e)
    }

    @Test fun boxSpansTheChosenDistanceEachWay() {
        val (s, _, n, _) = OfflineMaps.box(0.0, 0.0, 25)
        assertEquals(50.0, (n - s) * 111.32, 0.01)
        val (_, w, _, e) = OfflineMaps.box(60.0, 10.0, 25)
        // A degree of longitude is half as long at 60°, so the same 50 km needs twice the degrees.
        assertEquals(2 * 50.0 / 111.32, e - w, 0.001)
    }

    @Test fun tileCountGrowsWithSizeAndStaysUnderTheLimitAtMidLatitudes() {
        val sizes = OfflineAreaSize.entries.map { OfflineMaps.tiles(45.0, 7.0, it.km) }
        assertEquals(sizes.sorted(), sizes)
        assertTrue(sizes.all { it <= OfflineMaps.TILE_LIMIT })
        // Every zoom from 0 to 14 needs at least one tile.
        assertTrue(OfflineMaps.tiles(0.0, 0.0, 1) >= OfflineMaps.MAX_ZOOM + 1L)
    }

    @Test fun boxIsClampedAtThePolesAndTheDateLine() {
        val (s, w, n, e) = OfflineMaps.box(84.9, 179.9, 50)
        assertTrue(n <= 85.0 && e <= 180.0 && s < n && w < e)
    }
}
