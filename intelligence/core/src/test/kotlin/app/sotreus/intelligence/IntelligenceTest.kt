package app.sotreus.intelligence

import app.sotreus.core.model.AttentionHeadline
import app.sotreus.core.model.AttentionReasonKind
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.model.ProximityBand
import app.sotreus.core.model.ProximityCue
import app.sotreus.core.model.UserEntityState
import app.sotreus.intelligence.fieldwatch.RadioKind
import app.sotreus.intelligence.fieldwatch.Sighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligenceTest {

    private fun context(
        state: UserEntityState = UserEntityState.UNCLASSIFIED,
        family: DeviceFamily? = null,
        notable: Boolean = false,
        otherPlaces: List<String> = emptyList(),
        present: Double = 1.0,
        window: Double = 40.0,
        newHere: Boolean = false,
        age: Double = 1.0,
    ) = AttentionEngine.Context(
        userState = state, family = family, familyConfidence = Confidence.HIGH, notableSignature = notable,
        signatureName = null, otherPlaces = otherPlaces, presentMinutes = present, windowMinutes = window,
        newToPlace = newHere, lastHeardAgeSeconds = age, staleAfterSeconds = 30.0, displayName = "Grey tag",
    )

    @Test
    fun taggedFinderSeenElsewhereNeedsAttentionWithReasons() {
        val a = AttentionEngine.assess(
            context(UserEntityState.TAGGED, DeviceFamily.FINDER_TAG, otherPlaces = listOf("Home", "Café"), present = 38.0, window = 41.0),
        )!!
        assertTrue("score ${a.score}", a.score in 0.70f..0.85f)
        assertTrue(a.needsAttention)
        assertEquals(AttentionHeadline.CROSS_LOCATION_REENCOUNTER, a.headline)
        val kinds = a.reasons.map { it.kind }
        assertTrue(AttentionReasonKind.TAGGED_BY_YOU in kinds)
        assertTrue(AttentionReasonKind.SEEN_AT_OTHER_PLACES in kinds)
        assertTrue(AttentionReasonKind.REPEATED_THIS_SESSION in kinds)
    }

    @Test
    fun anOrdinaryNewRadioDoesNotNeedAttention() {
        val a = AttentionEngine.assess(context(newHere = true, present = 40.0))!!
        assertFalse("score ${a.score}", a.needsAttention)
    }

    @Test
    fun aTagAloneDoesNotCrossTheThreshold() {
        val a = AttentionEngine.assess(context(UserEntityState.TAGGED))!!
        assertFalse("score ${a.score}", a.needsAttention)
    }

    @Test
    fun persistentNewCameraFamilyNeedsAttention() {
        val a = AttentionEngine.assess(context(family = DeviceFamily.CAMERA, notable = true, newHere = true, present = 30.0))!!
        assertTrue("score ${a.score}", a.needsAttention)
        assertEquals(AttentionHeadline.FAMILY_SIGNATURE_OBSERVED, a.headline)
    }

    @Test
    fun mineExpectedAndIgnoredAreNeverRaised() {
        listOf(UserEntityState.MINE, UserEntityState.EXPECTED, UserEntityState.IGNORE).forEach {
            assertNull(AttentionEngine.assess(context(it, otherPlaces = listOf("Home", "Café"))))
        }
    }

    @Test
    fun staleDataLowersTheScore() {
        val fresh = AttentionEngine.assess(context(UserEntityState.TAGGED, otherPlaces = listOf("Home")))!!
        val stale = AttentionEngine.assess(context(UserEntityState.TAGGED, otherPlaces = listOf("Home"), age = 120.0))!!
        assertTrue(stale.score < fresh.score)
    }

    @Test
    fun baselineStanding() {
        assertEquals(Baseline.Standing.NORMAL, Baseline.standing(13, 14))
        assertEquals(Baseline.Standing.OCCASIONAL, Baseline.standing(5, 14))
        assertEquals(Baseline.Standing.RARE, Baseline.standing(1, 14))
        assertEquals(Baseline.Standing.NEW, Baseline.standing(0, 14))
        assertEquals(setOf("ns-guest"), Baseline.missing(setOf("ns-guest", "ns-office"), setOf("ns-office")))
    }

    @Test
    fun bandsUseHandoffThresholdsAndStableAngles() {
        assertEquals(ProximityBand.NEAR, BandLayout.band(-60.0))
        assertEquals(ProximityBand.MID, BandLayout.band(-61.0))
        assertEquals(ProximityBand.MID, BandLayout.band(-75.0))
        assertEquals(ProximityBand.FAR, BandLayout.band(-75.5))
        assertEquals(BandLayout.angleRadians("ble:AA"), BandLayout.angleRadians("ble:AA"), 0.0)
        assertTrue(BandLayout.angleRadians("ble:AA") != BandLayout.angleRadians("ble:AB"))
    }

    @Test
    fun proximityCuesFollowHunt() {
        val now = 100_000L
        val louder = (0..9).map { now - 8_000 + it * 400L to -70 } + (0..4).map { now - 1_800 + it * 400L to -58 }
        assertEquals(ProximityCue.LOUDER, Proximity.cue(louder, now, now, gone = false))
        assertEquals(ProximityCue.VERY_STRONG, Proximity.cue(listOf(now - 100 to -40, now - 50 to -42), now, now, false))
        assertEquals(ProximityCue.QUIET, Proximity.cue(louder, now + 20_000, now, false))
        assertEquals(ProximityCue.GONE, Proximity.cue(louder, now, now, gone = true))
        assertEquals(90L, Proximity.tickIntervalMs(-40, ProximityCue.LOUDER))
        assertEquals(1400L, Proximity.tickIntervalMs(-90, ProximityCue.SAME))
        assertNull(Proximity.tickIntervalMs(-60, ProximityCue.QUIET))
    }

    @Test
    fun compareFindsOnlyAndChanged() {
        val a = listOf(SessionCompare.Radio("x", 60_000, -60), SessionCompare.Radio("y", 60_000, -70))
        val b = listOf(SessionCompare.Radio("x", 50_000, -61), SessionCompare.Radio("y", 1_000, -70), SessionCompare.Radio("z", 9_000, -64))
        val r = SessionCompare.compare(a, b)
        assertEquals(listOf("x", "y"), r.both)
        assertEquals(listOf("z"), r.onlyB.map { it.entityId })
        assertEquals(listOf("y"), r.changed.map { it.a.entityId })
    }

    @Test
    fun calmCopyGuard() {
        assertTrue(CalmCopy.isCalm("Not a threat score. It ranks what may be worth a look."))
        assertFalse(CalmCopy.isCalm("Threat detected"))
        assertEquals("", CalmCopy.orFallback("You are being followed", ""))
    }

    @Test
    fun fingerprintLinkConfidence() {
        assertEquals(Confidence.HIGH, Fingerprint.linkConfidence(app.sotreus.core.model.RadioKind.WIFI, app.sotreus.core.model.BleAddressType.PUBLIC, "00:11:22:33:44:55"))
        assertEquals(Confidence.MEDIUM, Fingerprint.linkConfidence(app.sotreus.core.model.RadioKind.BLE, app.sotreus.core.model.BleAddressType.RANDOM, "C1:22:33:44:55:66"))
        assertEquals(Confidence.LOW, Fingerprint.linkConfidence(app.sotreus.core.model.RadioKind.BLE, app.sotreus.core.model.BleAddressType.RANDOM, "41:22:33:44:55:66"))
    }

    @Test
    fun classifierMapsTileToFinderTag() {
        val tile = sighting(mac = "C1:22:33:44:55:66", name = "Tile", uuids = listOf("FEED"))
        val c = SignatureClassifier().classify(listOf(tile), 0L).getValue(tile.key)
        assertEquals(DeviceFamily.FINDER_TAG, c.family)
        assertNotNull(c.signatureName)
        assertTrue(c.guess.isNotBlank())
    }

    private fun sighting(mac: String, name: String, uuids: List<String>) = Sighting(
        key = "BLE:$mac", kind = RadioKind.BLE, mac = mac, name = name, rssi = -60, rssiMin = -60, rssiMax = -60,
        channel = 0, frequencyMhz = 2402, vendor = null, randomized = true, hiddenSsid = false, serviceUuids = uuids,
        manufacturerId = null, manufacturerDataHex = "", rawHex = "", extras = "", firstSeen = 0, lastSeen = 0,
        hitCount = 1, fleetIds = emptySet(), rssiHistory = emptyList(), presence = emptyList(),
    )
}
