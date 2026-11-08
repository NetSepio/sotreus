package app.sotreus.edge.location

import app.sotreus.edge.protocol.DeploymentMode
import app.sotreus.edge.protocol.EdgeCapabilities
import app.sotreus.edge.protocol.EdgePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeLocationTest {
    private val limits = QualityLimits()

    /** About 1.11 m per 1e-5 degree of latitude. */
    private fun fix(at: Long, dLatE5: Int = 0, acc: Float? = 6f, speed: Float? = null, mock: Boolean = false, alt: Double? = null, vAcc: Float? = null) =
        GeoFix(at, 12.97 + dLatE5 * 1e-5, 77.59, acc, altitudeM = alt, verticalAccuracyM = vAcc, speedMps = speed, mock = mock)

    // ---- FixQuality ------------------------------------------------------------------------------

    @Test fun everyCheckRejectsItsCase() {
        val at = 100_000L
        assertNull(FixQuality.check(fix(at - 2_000), at, together = true, spoofingWarning = false))
        assertEquals(FixRejection.NOT_TOGETHER, FixQuality.check(fix(at), at, together = false, spoofingWarning = false))
        assertEquals(FixRejection.NO_ACCURACY, FixQuality.check(fix(at, acc = null), at, true, false))
        assertEquals(FixRejection.TOO_INACCURATE, FixQuality.check(fix(at, acc = 25.1f), at, true, false))
        assertNull(FixQuality.check(fix(at, acc = 25f), at, true, false))
        assertEquals(FixRejection.TOO_OLD, FixQuality.check(fix(at - 10_001), at, true, false))
        assertNull(FixQuality.check(fix(at - 10_000), at, true, false))
        assertEquals(FixRejection.IN_FUTURE, FixQuality.check(fix(at + 5_001), at, true, false))
        assertEquals(FixRejection.MOCK, FixQuality.check(fix(at, mock = true), at, true, false))
        assertEquals(FixRejection.SPOOFING_WARNING, FixQuality.check(fix(at), at, true, true))
    }

    @Test fun limitsAreClampedNotRemoved() {
        val s = QualityLimits(maxAccuracyM = 10_000f, togetherMinDbm = -120, maxFixAgeMs = 0).sane()
        assertEquals(100f, s.maxAccuracyM)
        assertEquals(-90, s.togetherMinDbm)
        assertEquals(2_000L, s.maxFixAgeMs)
    }

    // ---- TogetherTracker -------------------------------------------------------------------------

    @Test fun togetherNeedsAStrongRecentLink() {
        val t = TogetherTracker(limits)
        assertFalse(t.isTogether(0))
        t.linkRssi(0, -60); t.linkRssi(5_000, -62); t.linkRssi(10_000, -64)
        assertTrue(t.isTogether(10_000))
        // Average below −75 over 15 s: apart.
        t.linkRssi(15_000, -88); t.linkRssi(20_000, -90)
        assertFalse(t.isTogether(20_000))
        // Silence for longer than the stale limit: not together.
        val u = TogetherTracker(limits)
        u.linkRssi(0, -50)
        assertTrue(u.isTogether(11_000))
        assertFalse(u.isTogether(12_001))
    }

    @Test fun aLinkDropBreaksTogetherUntilTheWindowPasses() {
        val t = TogetherTracker(limits)
        t.linkRssi(0, -55); t.linkDown(4_000)
        assertFalse(t.isTogether(4_500))
        t.linkRssi(6_000, -55)
        assertFalse(t.isTogether(6_000))
        t.linkRssi(11_000, -55); t.linkRssi(16_000, -55); t.linkRssi(19_100, -55)
        assertTrue(t.isTogether(19_100))
    }

    // ---- Live samples ----------------------------------------------------------------------------

    @Test fun liveSampleTakesTheNearestFixWithDrift() {
        val fixes = listOf(fix(0, acc = 5f, speed = 2f), fix(4_000, dLatE5 = 1, acc = 8f, speed = 2f))
        val p = SightingLocator.live(1_000, fixes, EdgeLocationSource.PHONE_WITH_EDGE) as SightingLocation.Point
        assertEquals(12.97, p.lat, 1e-9)
        assertEquals(5.0 + 2.0, p.radiusM, 1e-9)
        assertTrue(SightingLocator.live(20_000, fixes, EdgeLocationSource.PHONE_WITH_EDGE) is SightingLocation.NotKnown)
    }

    // ---- Windows ---------------------------------------------------------------------------------

    @Test fun stillWindowIsAPointWithAltitude() {
        val fixes = (0..30).map { fix(it * 2_000L, dLatE5 = it % 3, acc = 6f, alt = 900.0, vAcc = 4f) }
        val p = SightingLocator.window(0, 60_000, fixes, EdgeLocationSource.PHONE_WITH_EDGE) as SightingLocation.Point
        assertEquals(1.0, p.coverage, 1e-9)
        assertEquals(6.0, p.radiusM, 1e-9)
        assertEquals(900.0, p.altitudeM!!, 1e-9)
        assertEquals(4f, p.verticalAccuracyM!!)
        assertEquals(EdgeLocationSource.PHONE_WITH_EDGE, p.source)
    }

    @Test fun movingWindowIsAStretchOfRoute() {
        // 10 m per 2 s along latitude for 60 s: about 300 m.
        val fixes = (0..30).map { fix(it * 2_000L, dLatE5 = it * 9) }
        val r = SightingLocator.window(0, 60_000, fixes, EdgeLocationSource.PHONE_WITH_EDGE) as SightingLocation.AlongRoute
        assertEquals(300.0, r.pathM, 5.0)
        assertEquals(12.97, r.startLat, 1e-9)
    }

    @Test fun boundariesBetweenPointRouteAndSpan() {
        // Span just under 50 m: still a point.
        val near = listOf(fix(0), fix(30_000, dLatE5 = 44), fix(60_000)) + (1..29).map { fix(it * 2_000L) }
        assertTrue(SightingLocator.window(0, 60_000, near, EdgeLocationSource.PHONE_WITH_EDGE) is SightingLocation.Point)
        val far = near + fix(31_000, dLatE5 = 50)
        assertTrue(SightingLocator.window(0, 60_000, far, EdgeLocationSource.PHONE_WITH_EDGE) is SightingLocation.AlongRoute)
    }

    @Test fun gapsBecomePartialAndThenNotKnown() {
        // Fixes only in the first 30 s of a 100 s window: 35 % covered.
        val half = (0..12).map { fix(it * 2_500L) }
        val p = SightingLocator.window(0, 100_000, half, EdgeLocationSource.PHONE_WITH_EDGE) as SightingLocation.Partial
        assertEquals(0.35, p.coverage, 0.01)
        assertEquals(100_000L, p.windowMs)
        // One fix in a 5-minute window: 10 s of 300 s.
        val one = listOf(fix(150_000))
        val n = SightingLocator.window(0, 300_000, one, EdgeLocationSource.PHONE_WITH_EDGE)
        assertTrue(n is SightingLocation.NotKnown)
        assertEquals(10_000.0 / 300_000, n.coverage, 1e-9)
        assertEquals(EdgeLocationSource.NONE, n.source)
        // No fixes at all.
        assertTrue(SightingLocator.window(0, 300_000, emptyList(), EdgeLocationSource.PHONE_WITH_EDGE) is SightingLocation.NotKnown)
    }

    @Test fun zeroLengthWindowNeedsAFixNearby() {
        assertTrue(SightingLocator.window(5_000, 5_000, listOf(fix(7_000)), EdgeLocationSource.PHONE_WITH_EDGE) is SightingLocation.Point)
        assertTrue(SightingLocator.window(5_000, 5_000, listOf(fix(11_000)), EdgeLocationSource.PHONE_WITH_EDGE) is SightingLocation.NotKnown)
    }

    @Test fun moreAccurateFixesPullTheCentre() {
        val fixes = listOf(fix(0, dLatE5 = 0, acc = 3f), fix(2_000, dLatE5 = 20, acc = 24f))
        val p = SightingLocator.window(0, 2_000, fixes, EdgeLocationSource.PHONE_WITH_EDGE) as SightingLocation.Point
        assertTrue(p.lat - 12.97 < 0.5 * 20e-5)
    }

    // ---- Resolver --------------------------------------------------------------------------------

    @Test fun resolverOrder() {
        val noGnss = EdgeCapabilities(mapOf("gnss" to false))
        val gnss = EdgeCapabilities(mapOf("gnss" to true))
        val travel = EdgePlacement(DeploymentMode.TRAVEL)
        val home = EdgePlacement(DeploymentMode.STATIONARY, 12.9, 77.5, 8f, 1, "phone")
        val unset = EdgePlacement(DeploymentMode.STATIONARY)
        assertEquals(EdgeLocationSource.EDGE_GNSS, LocationResolver.resolve(gnss, home, edgeHasFix = true, placementHeld = false, phoneTagging = true))
        assertEquals(EdgeLocationSource.EDGE_FIXED, LocationResolver.resolve(noGnss, home, false, false, true))
        assertEquals(EdgeLocationSource.NONE, LocationResolver.resolve(noGnss, home, false, placementHeld = true, phoneTagging = true))
        assertEquals(EdgeLocationSource.NONE, LocationResolver.resolve(noGnss, unset, false, false, true))
        assertEquals(EdgeLocationSource.PHONE_WITH_EDGE, LocationResolver.resolve(noGnss, travel, false, false, true))
        assertEquals(EdgeLocationSource.PHONE_WITH_EDGE, LocationResolver.resolve(noGnss, null, false, false, true))
        assertEquals(EdgeLocationSource.NONE, LocationResolver.resolve(noGnss, travel, false, false, phoneTagging = false))
        assertEquals(EdgeLocationSource.PHONE_WITH_EDGE, LocationResolver.resolve(gnss, travel, edgeHasFix = false, placementHeld = false, phoneTagging = true))
        assertEquals(EdgeLocationSource.NONE, LocationResolver.resolve(gnss, travel, false, false, true, gnssPhoneFallback = false))
        assertTrue(LocationResolver.needsPhoneLocation(EdgeLocationSource.PHONE_WITH_EDGE))
        assertFalse(LocationResolver.needsPhoneLocation(EdgeLocationSource.EDGE_FIXED))
    }

    @Test fun fixedPositionAppliesItsOwnAccuracy() {
        val p = SightingLocator.fixed(12.9, 77.5, 8f) as SightingLocation.Point
        assertEquals(EdgeLocationSource.EDGE_FIXED, p.source)
        assertEquals(8.0, p.radiusM, 1e-9)
    }

    // ---- Placement and moved checks --------------------------------------------------------------

    @Test fun averagerNeverClaimsMoreThanTheFixesSupport() {
        val a = PlacementAverager(limits)
        assertNull(a.estimate())
        a.add(fix(0, acc = 60f)); a.add(fix(1_000, dLatE5 = 1, acc = 45f))
        assertFalse(a.estimate()!!.accepted)
        assertNull(a.placement(5))
        repeat(10) { a.add(fix(2_000L + it * 1_000, acc = 5f)) }
        val e = a.estimate()!!
        assertTrue(e.accuracyM >= 5f)
        assertTrue(e.accepted)
        assertEquals(DeploymentMode.STATIONARY, a.placement(5)!!.mode)
        // Spread larger than the reported accuracy wins.
        val b = PlacementAverager(limits)
        b.add(fix(0, dLatE5 = 0, acc = 3f)); b.add(fix(1_000, dLatE5 = 40, acc = 3f))
        assertTrue(b.estimate()!!.accuracyM > 15f)
        // Mock fixes are ignored.
        val c = PlacementAverager(limits)
        c.add(fix(0, mock = true))
        assertNull(c.estimate())
    }

    @Test fun anUnacceptedEstimateIsKeptAndSavedOnlyWithItsRealAccuracy() {
        val a = PlacementAverager(limits)
        repeat(8) { a.add(fix(it * 1_000L, dLatE5 = it * 5, acc = 40f)) }
        val e = a.estimate()!!
        assertFalse(e.accepted)
        assertEquals(30f, e.limitM, 0f)
        assertTrue(e.accuracyM > e.limitM)
        assertNull(e.toPlacement(9))
        val saved = e.toPlacement(9, anyway = true)!!
        assertEquals(e.accuracyM, saved.accuracyM!!, 0f)
        assertEquals(PlacementSources.PHONE, saved.source)
        assertEquals(e.lat, saved.lat!!, 0.0)
        // A tighter limit from Field tools turns the same fixes down; a looser one accepts them.
        val strict = PlacementAverager(limits.copy(stationaryAcceptM = 15f))
        repeat(10) { strict.add(fix(it * 1_000L, acc = 20f)) }
        assertFalse(strict.estimate()!!.accepted)
        val loose = PlacementAverager(limits.copy(stationaryAcceptM = 50f).sane())
        repeat(8) { loose.add(fix(it * 1_000L, dLatE5 = it * 5, acc = 40f)) }
        assertTrue(loose.estimate()!!.accepted)
    }

    @Test fun mapPickAccuracyFollowsTheZoom() {
        assertEquals(ManualPosition.MIN_ACCURACY_M, ManualPosition.mapAccuracyM(52.37, 18.0), 0f)
        val street = ManualPosition.mapAccuracyM(52.37, 15.0)
        val city = ManualPosition.mapAccuracyM(52.37, 11.0)
        assertTrue(street in 10f..20f)
        assertTrue(city > 150f)
        assertTrue(ManualPosition.mapAccuracyM(0.0, 15.0) > street) // wider ground per pixel at the equator
    }

    @Test fun typedCoordinatesParseAndWidenWithFewerDecimals() {
        val t = ManualPosition.parseTyped(" 52.37021, 4.89517 ")!!
        assertEquals(52.37021, t.lat, 0.0)
        assertEquals(4.89517, t.lon, 0.0)
        assertEquals(ManualPosition.TYPED_MIN_ACCURACY_M, t.accuracyM, 0f)
        assertEquals(t.lat, ManualPosition.parseTyped("52.37021 4.89517")!!.lat, 0.0)
        assertEquals(t.lon, ManualPosition.parseTyped("52.37021;4.89517")!!.lon, 0.0)
        assertEquals(557f, ManualPosition.parseTyped("52.37, 4.89")!!.accuracyM, 0f)
        assertEquals(56f, ManualPosition.parseTyped("-33.868, 151.209")!!.accuracyM, 0f)
        assertNull(ManualPosition.parseTyped("95.0, 4.9"))
        assertNull(ManualPosition.parseTyped("52.3"))
        assertNull(ManualPosition.parseTyped("0, 0"))
        assertNull(ManualPosition.parseTyped("north, east"))
    }

    @Test fun neighbourhoodCheck() {
        val heard = (1..30).associate { "AA:00:00:00:00:%02X".format(it) to -40 - it }
        val base = MovedChecks.baseline(heard)
        assertEquals(20, base.size)
        assertTrue("AA:00:00:00:00:01" in base)
        assertEquals(1.0, MovedChecks.overlap(base, base)!!, 1e-9)
        assertFalse(MovedChecks.neighbourhoodChanged(base, base.take(7).toSet()))
        assertTrue(MovedChecks.neighbourhoodChanged(base, base.take(5).toSet()))
        assertNull(MovedChecks.overlap(setOf("A", "B"), emptySet()))
        assertFalse(MovedChecks.neighbourhoodChanged(setOf("A", "B"), emptySet()))
    }

    @Test fun phoneCrossCheck() {
        val home = EdgePlacement(DeploymentMode.STATIONARY, 12.97, 77.59, 8f, 1, "phone")
        assertFalse(MovedChecks.phoneDisagrees(home, fix(0, dLatE5 = 100), together = true))
        assertTrue(MovedChecks.phoneDisagrees(home, fix(0, dLatE5 = 300), together = true))
        assertFalse(MovedChecks.phoneDisagrees(home, fix(0, dLatE5 = 300), together = false))
        assertFalse(MovedChecks.phoneDisagrees(home, fix(0, dLatE5 = 300, acc = 60f), together = true))
    }
}
