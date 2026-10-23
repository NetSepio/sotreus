package app.sotreus.intelligence

import app.sotreus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class JourneyMovementTest {
    private fun fix(at: Long, lon: Double, accuracy: Float? = 10f) = JourneyMovement.Fix(at, 0.0, lon, accuracy)
    private fun drive(detector: JourneyMovement, id: (Int) -> String = { "a" }, start: Long = 0) {
        for (i in 0..20) {
            val at = start + i * 30_000L
            detector.observe(id(i), at, fix(at, i * 0.0004))
        }
    }

    @Test fun requiresSustainedObservationsAtSeparatedLocations() {
        val d = JourneyMovement()
        drive(d)
        val evidence = d.evidence("a", 600_000)!!
        assertEquals(3, evidence.locations)
        assertTrue(evidence.separationM > 600)
        assertEquals(600_000L, evidence.durationMs)
        assertEquals(21, evidence.samples)
    }

    @Test fun stationaryRadioAndGpsJitterDoNotEstablishMovement() {
        val d = JourneyMovement()
        for (i in 0..40) d.observe("a", i * 30_000L, fix(i * 30_000L, (i % 2) * 0.0001))
        assertNull(d.evidence("a", 1_200_000))
    }

    @Test fun rotatingAddressesAndSameModelNamesAreNeverMerged() {
        val d = JourneyMovement()
        drive(d, id = { "address-${it / 5}" })
        for (i in 0..4) assertNull(d.evidence("address-$i", 600_000))
    }

    @Test fun missingInaccurateOrStaleLocationCannotContribute() {
        for (accuracy in listOf(null, 100f, Float.NaN)) {
            val d = JourneyMovement()
            for (i in 0..20) d.observe("a", i * 30_000L, fix(i * 30_000L, i * 0.0004, accuracy))
            assertNull(d.evidence("a", 600_000))
        }
        val d = JourneyMovement()
        for (i in 0..20) d.observe("a", i * 30_000L, fix(i * 30_000L - 30_000, i * 0.0004))
        assertNull(d.evidence("a", 600_000))
    }

    @Test fun outagesAndSessionChangesResetContinuity() {
        val d = JourneyMovement()
        drive(d)
        assertNull(d.evidence("a", 800_000))
        d.observe("a", 900_000, fix(900_000, 0.02))
        assertNull(d.evidence("a", 900_000))
        drive(d, start = 1_000_000)
        d.clear()
        assertNull(d.evidence("a", 1_600_000))
    }

    @Test fun repeatingOnePacketCannotMeetDurationOrSampleRequirements() {
        val d = JourneyMovement()
        repeat(100) { d.observe("a", 1_000, fix(1_000, it * 0.001)) }
        assertNull(d.evidence("a", 1_000))
    }

    @Test fun movementRaisesUnknownRadiosIndependentlyOfClassification() {
        val c = AttentionEngine.Context(UserEntityState.UNCLASSIFIED, null, Confidence.LOW, false, null,
            emptyList(), 1.0, 20.0, false, 0.0, 30.0, "Unknown radio",
            JourneyMovement.Evidence(3, 600_000, 800, 20), Confidence.LOW)
        val result = AttentionEngine.assess(c)!!
        assertTrue(result.needsAttention)
        assertEquals(AttentionHeadline.JOURNEY_MOVEMENT, result.headline)
        assertTrue(result.reasons.any { it.kind == AttentionReasonKind.ADDRESS_CONTINUITY })
        assertNull(AttentionEngine.assess(c.copy(movement = null)))
        assertNull(AttentionEngine.assess(c.copy(userState = UserEntityState.MINE)))
    }

    @Test fun privateAndUnknownAddressesCannotBeSentToVendorApis() {
        assertNull(Fingerprint.vendorLookupPrefix(RadioKind.WIFI, BleAddressType.PUBLIC, "02:11:22:33:44:55"))
        assertNull(Fingerprint.vendorLookupPrefix(RadioKind.BLE, BleAddressType.UNKNOWN, "00:11:22:33:44:55"))
        assertNull(Fingerprint.vendorLookupPrefix(RadioKind.BLE, BleAddressType.RANDOM, "C0:11:22:33:44:55"))
        assertEquals("001122", Fingerprint.vendorLookupPrefix(RadioKind.BLE, BleAddressType.PUBLIC, "00:11:22:33:44:55"))
        assertEquals(Confidence.LOW, Fingerprint.linkConfidence(RadioKind.WIFI, BleAddressType.PUBLIC, "02:11:22:33:44:55"))
    }
}
