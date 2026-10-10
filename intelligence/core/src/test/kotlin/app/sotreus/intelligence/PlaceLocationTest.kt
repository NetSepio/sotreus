package app.sotreus.intelligence

import app.sotreus.intelligence.PlaceGeofence.Area
import app.sotreus.intelligence.PlaceGeofence.Decision
import app.sotreus.intelligence.PlaceGeofence.Fix
import app.sotreus.intelligence.PlaceGeofence.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceLocationTest {

    // --- Plus codes ---------------------------------------------------------------------------

    @Test
    fun plusCodeMatchesReferenceVectors() {
        assertEquals("7FG49QCJ+2V", PlusCode.encode(20.3700625, 2.7821875))
        assertEquals("4VCPPQGP+Q9", PlusCode.encode(-41.2730625, 174.7859375))
        assertEquals("8FVC2222+22", PlusCode.encode(47.0000625, 8.0000625))
        assertEquals("22220000+", PlusCode.encode(-89.5, -179.5, 4))
        assertEquals("6VGX0000+", PlusCode.encode(0.5, 179.5, 4))
    }

    @Test
    fun plusCodeClipsLatitudeAndWrapsLongitude() {
        assertEquals("CFX30000+", PlusCode.encode(90.0, 1.0, 4))
        assertEquals("CFX30000+", PlusCode.encode(92.0, 1.0, 4))
        assertEquals("62H20000+", PlusCode.encode(1.0, 180.0, 4))
        assertEquals("62H30000+", PlusCode.encode(1.0, 181.0, 4))
        assertEquals(PlusCode.encode(10.0, -170.0), PlusCode.encode(10.0, 190.0))
    }

    @Test
    fun plusCodeShortensConsistently() {
        val full = PlusCode.encode(20.3700625, 2.7821875)
        assertEquals("7FG49QCJ+", PlusCode.encode(20.3700625, 2.7821875, 8))
        assertEquals("7FG49QCJ+", PlusCode.coarsen(full, 8))
        assertEquals("7FG49Q00+", PlusCode.coarsen(full, PlusCode.COARSE_LENGTH))
        assertEquals("7FG49Q00+", PlusCode.coarsen("7FG49Q00+", 8))
    }

    @Test
    fun plusCodeIsStableWithinItsCell() {
        // Two fixes a few metres apart inside one ~14 m cell share a code.
        assertEquals(PlusCode.encode(20.37007, 2.78221), PlusCode.encode(20.37010, 2.78224))
    }

    // --- Geofence -----------------------------------------------------------------------------

    private val office = Area(1, 12.9716, 77.5946, 150)
    private val home = Area(2, 12.9352, 77.6245, 150)
    private val areas = listOf(office, home)

    /** A fix [northM] metres north of [a]'s centre. */
    private fun near(a: Area, northM: Double, accuracy: Float? = 10f, at: Long = NOW) =
        Fix(at, a.lat + northM / 111_195.0, a.lon, accuracy)

    @Test
    fun distanceIsMetres() {
        val d = PlaceGeofence.distanceM(0.0, 0.0, 0.001, 0.0)
        assertTrue("got $d", d in 110.8..111.6)
        assertEquals(0.0, PlaceGeofence.distanceM(office.lat, office.lon, office.lat, office.lon), 1e-9)
    }

    @Test
    fun entersInsideRadiusOnly() {
        assertEquals(1L, PlaceGeofence.match(areas, near(office, 100.0), currentId = null))
        assertNull(PlaceGeofence.match(areas, near(office, 180.0), currentId = null))
    }

    @Test
    fun smallMovesDoNotLeaveThePlace() {
        // Inside the exit margin the current place holds; well beyond it, the phone has left.
        assertEquals(1L, PlaceGeofence.match(areas, near(office, 190.0), currentId = 1))
        assertNull(PlaceGeofence.match(areas, near(office, 230.0), currentId = 1))
        // A less accurate fix widens the margin rather than flipping the place.
        assertEquals(1L, PlaceGeofence.match(areas, near(office, 260.0, accuracy = 120f), currentId = 1))
    }

    @Test
    fun overlappingPlacesPreferTheCurrentThenTheNearest() {
        val annex = Area(3, office.lat + 120 / 111_195.0, office.lon, 150)
        val both = listOf(office, annex)
        // 100 m north: inside both, annex centre is nearer.
        assertEquals(3L, PlaceGeofence.match(both, near(office, 100.0), currentId = null))
        assertEquals(1L, PlaceGeofence.match(both, near(office, 100.0), currentId = 1))
    }

    @Test
    fun reopeningSomewhereElseDropsTheEarlierPlace() {
        // The issue: the app reopens away from the place it last picked.
        val earlier = Selection(placeId = 1, auto = true)
        assertEquals(Decision.Select(null), PlaceGeofence.decide(earlier, areas, near(office, 5_000.0), NOW))
        assertEquals(Decision.Select(2L), PlaceGeofence.decide(earlier, areas, near(home, 20.0), NOW))
        assertEquals(Decision.Keep, PlaceGeofence.decide(earlier, areas, near(office, 60.0), NOW))
    }

    @Test
    fun walkingIntoASavedPlacePicksIt() {
        val unsaved = Selection(placeId = null, auto = true)
        assertEquals(Decision.Select(1L), PlaceGeofence.decide(unsaved, areas, near(office, 40.0), NOW))
        assertEquals(Decision.Keep, PlaceGeofence.decide(unsaved, areas, near(office, 2_000.0), NOW))
    }

    @Test
    fun inaccurateFixesChangeNothing() {
        val earlier = Selection(placeId = 1, auto = true)
        assertEquals(Decision.Keep, PlaceGeofence.decide(earlier, areas, near(home, 0.0, accuracy = 1_500f), NOW))
    }

    @Test
    fun aHandPickHoldsNearWhereItWasMade() {
        // Picked "Home" by hand while standing inside the office geofence.
        val pick = Selection(placeId = 2, auto = false, anchorLat = office.lat, anchorLon = office.lon, selectedAtMs = NOW - 3_600_000)
        assertEquals(Decision.Keep, PlaceGeofence.decide(pick, areas, near(office, 80.0), NOW))
        // Once the phone has moved away, location decides again.
        assertEquals(Decision.Select(null), PlaceGeofence.decide(pick, areas, near(office, 3_000.0), NOW))
        assertEquals(Decision.Select(2L), PlaceGeofence.decide(pick, areas, near(home, 10.0), NOW))
    }

    @Test
    fun aHandPickWithoutAFixAdoptsTheNextOneButNotAnOldOne() {
        val fresh = Selection(placeId = 2, auto = false, selectedAtMs = NOW - 60_000)
        assertEquals(Decision.Anchor, PlaceGeofence.decide(fresh, areas, near(office, 0.0), NOW))
        // A pick restored from before an update, never anchored, can't be confirmed.
        val legacy = Selection(placeId = 2, auto = false, selectedAtMs = 0)
        assertEquals(Decision.Select(1L), PlaceGeofence.decide(legacy, areas, near(office, 0.0), NOW))
    }

    @Test
    fun aCoarseFixDoesNotReplaceARecentPreciseOne() {
        val gps = Fix(NOW, 12.0, 77.0, 5f)
        assertFalse(PlaceGeofence.isBetter(Fix(NOW + 5_000, 12.0, 77.0, 60f), gps))
        assertTrue(PlaceGeofence.isBetter(Fix(NOW + 5_000, 12.0, 77.0, 20f), gps))
        assertTrue(PlaceGeofence.isBetter(Fix(NOW + 40_000, 12.0, 77.0, 60f), gps))
        assertFalse(PlaceGeofence.isBetter(Fix(NOW - 5_000, 12.0, 77.0, 5f), gps))
        assertTrue(PlaceGeofence.isBetter(Fix(NOW - 5_000, 12.0, 77.0, 3f), gps))
        assertTrue(PlaceGeofence.isBetter(gps, null))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
