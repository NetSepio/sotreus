package app.sotreus.edge.protocol.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MeshGeoTest {
    private val now = 1_762_400_000_000L

    private fun pos(lat: Double, lon: Double, bits: Int? = null, agoMs: Long = 60_000) =
        MeshPosition(lat, lon, null, bits, (now - agoMs) / 1000)

    @Test fun haversineAndBearing() {
        // One degree of latitude is about 111.2 km.
        assertEquals(111_195.0, MeshGeo.distanceM(12.0, 77.0, 13.0, 77.0), 50.0)
        assertEquals(0.0, MeshGeo.bearingDeg(12.0, 77.0, 13.0, 77.0), 0.01)
        assertEquals(90.0, MeshGeo.bearingDeg(0.0, 77.0, 0.0, 78.0), 0.01)
        assertEquals(MeshGeo.Compass.NE, MeshGeo.compass(44.0))
        assertEquals(MeshGeo.Compass.N, MeshGeo.compass(359.0))
        assertEquals(MeshGeo.Compass.NW, MeshGeo.compass(-45.0))
    }

    @Test fun roundsToWhatThePositionsSupport() {
        // About 3.14 km northeast, reported to ±2.9 km: whole kilometres. The distance is just over
        // the uncertainty (2.9 km + 10 m), so the direction is kept.
        val b = MeshGeo.between(12.9716, 77.5946, 10.0, pos(12.9917, 77.6150, bits = 13), null, now)!!
        assertEquals(1_000.0, b.stepM, 0.0)
        assertEquals(3_000.0, b.metres, 0.0)
        assertEquals(MeshGeo.Compass.NE, b.compass)

        // Exact positions about 445 m apart, this phone within 5 m: 10 m steps.
        val near = MeshGeo.between(12.9716, 77.5946, 5.0, pos(12.9756, 77.5946), null, now)!!
        assertEquals(10.0, near.stepM, 0.0)
        assertEquals(440.0, near.metres, 0.0)
        assertEquals(MeshGeo.Compass.N, near.compass)
    }

    @Test fun noDirectionWhenTheNodeMayBeAnywhereAround() {
        // 1 km away but only known to ±2.9 km.
        val b = MeshGeo.between(12.9716, 77.5946, 10.0, pos(12.9806, 77.5946, bits = 13), null, now)!!
        assertNull(b.compass)
    }

    @Test fun staleOrUnknownGivesNothing() {
        assertNull(MeshGeo.between(null, null, null, pos(12.98, 77.6), null, now))
        assertNull(MeshGeo.between(12.97, 77.59, 10.0, null, null, now))
        assertNull(MeshGeo.between(12.97, 77.59, 10.0, pos(12.98, 77.6, agoMs = 3 * 60 * 60 * 1000L), null, now))
        // No report time: the node's last-heard time decides.
        val untimed = MeshPosition(12.98, 77.6, null, null, null)
        assertNotNull(MeshGeo.between(12.97, 77.59, 10.0, untimed, now - 60_000, now))
        assertNull(MeshGeo.between(12.97, 77.59, 10.0, untimed, null, now))
    }
}
