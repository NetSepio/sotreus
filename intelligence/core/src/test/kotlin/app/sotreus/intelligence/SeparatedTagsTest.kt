package app.sotreus.intelligence

import app.sotreus.core.model.AttentionHeadline
import app.sotreus.core.model.AttentionReasonKind
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.UserEntityState
import app.sotreus.intelligence.fieldwatch.MfgRecord
import app.sotreus.intelligence.fieldwatch.Observation
import app.sotreus.intelligence.fieldwatch.RadioFacts
import app.sotreus.intelligence.fieldwatch.RadioKind
import app.sotreus.intelligence.fieldwatch.ServiceDataRecord
import app.sotreus.intelligence.fieldwatch.Sighting
import app.sotreus.intelligence.fieldwatch.TrackerWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeparatedTagsTest {
    private val tags = SeparatedTags().apply { turn(true) }
    private val home = TrackerWatch.Spot(40.0000, -80.0000)
    private val street = TrackerWatch.Spot(40.0020, -80.0000) // ~220 m
    private val shop = TrackerWatch.Spot(40.0040, -80.0000)

    private fun dult(separated: Boolean) = RadioFacts(serviceData = listOf(ServiceDataRecord("FCB2", "01" + if (separated) "00" else "01")))
    private fun findMy(status: String) = RadioFacts(mfgRecords = listOf(MfgRecord(0x004C, "1219" + status + "11".repeat(24))))
    private fun smartTag(state: Int, id: String) =
        RadioFacts(serviceData = listOf(ServiceDataRecord("FD5A", "%02X".format(0x10 or state) + "000000" + id + "07")))

    private fun hear(min: Int, at: TrackerWatch.Spot?, facts: RadioFacts, key: String = "ble:AA", mac: String = "AA:AA:AA:AA:AA:AA", mine: Set<String> = emptySet()) {
        val now = min * 60_000L
        tags.hear(key, Observation(RadioKind.BLE, mac, "", -60, 0, 0, false, emptyList(), null, "", "", "", now, facts = facts))
        tags.pass(listOf(sighting(key, mac, now, facts)), mine, at, now)
    }

    private fun sighting(key: String, mac: String, now: Long, facts: RadioFacts) = Sighting(
        key = key, kind = RadioKind.BLE, mac = mac, name = "", rssi = -60, rssiMin = -60, rssiMax = -60, channel = 0, frequencyMhz = 0,
        vendor = null, randomized = true, hiddenSsid = false, serviceUuids = emptyList(), manufacturerId = null, manufacturerDataHex = "",
        rawHex = "", extras = "", firstSeen = 0, lastSeen = now, hitCount = 1, fleetIds = emptySet(), rssiHistory = emptyList(),
        presence = emptyList(), facts = facts,
    )

    @Test fun thirtyMinutesAtThreePlaces() {
        hear(0, home, dult(true))
        hear(15, street, dult(true))
        assertNull(tags.evidence("ble:AA"))
        hear(31, shop, dult(true))
        assertEquals(SeparatedTags.Evidence(31, 3), tags.evidence("ble:AA"))
    }

    @Test fun standingStillOrWithoutAnAccurateFixIsNotEvidence() {
        listOf(0, 15, 31, 45).forEach { hear(it, home, dult(true)) }
        assertNull(tags.evidence("ble:AA"))
        listOf(50, 60, 70).forEach { hear(it, null, dult(true)) }
        assertNull(tags.evidence("ble:AA"))
    }

    @Test fun withItsOwnerStartsOver() {
        hear(0, home, dult(true))
        hear(15, street, dult(true))
        hear(20, street, dult(false))
        hear(31, shop, dult(true))
        assertNull(tags.evidence("ble:AA"))
    }

    @Test fun aQuietGapStartsOver() {
        hear(0, home, findMy("00"))
        hear(15, street, findMy("00"))
        hear(36, shop, findMy("00"))
        assertNull(tags.evidence("ble:AA"))
    }

    @Test fun ownTagsStayOut() {
        hear(0, home, dult(true), mine = setOf("ble:AA"))
        hear(15, street, dult(true), mine = setOf("ble:AA"))
        hear(31, shop, dult(true), mine = setOf("ble:AA"))
        assertNull(tags.evidence("ble:AA"))
    }

    @Test fun aSmartTagIsFollowedAcrossAddresses() {
        val id = "AABBCCDDEEFF0011"
        hear(0, home, smartTag(1, id), key = "ble:01", mac = "01:01:01:01:01:01")
        hear(15, street, smartTag(1, id), key = "ble:02", mac = "02:02:02:02:02:02")
        hear(32, shop, smartTag(3, id), key = "ble:03", mac = "03:03:03:03:03:03")
        assertEquals(SeparatedTags.Evidence(32, 3), tags.evidence("ble:03"))
        assertNull(tags.evidence("ble:01"))
    }

    @Test fun aRecentOwnerFindMyTagStaysOut() {
        hear(0, home, findMy("04"))
        hear(15, street, findMy("04"))
        hear(31, shop, findMy("04"))
        assertNull(tags.evidence("ble:AA"))
    }

    @Test fun turningItOffForgetsEverything() {
        hear(0, home, dult(true))
        hear(15, street, dult(true))
        tags.turn(false)
        tags.turn(true)
        hear(31, shop, dult(true))
        assertNull(tags.evidence("ble:AA"))
    }

    @Test fun attentionSaysWhatTheTagSaidAndHowLong() {
        val c = AttentionEngine.Context(
            userState = UserEntityState.UNCLASSIFIED, family = null, familyConfidence = Confidence.LOW, notableSignature = false,
            signatureName = null, otherPlaces = emptyList(), presentMinutes = 5.0, windowMinutes = 40.0, newToPlace = false,
            lastHeardAgeSeconds = 1.0, staleAfterSeconds = 30.0, displayName = "", separated = SeparatedTags.Evidence(34, 3),
        )
        val a = AttentionEngine.assess(c)!!
        assertTrue(a.needsAttention)
        assertEquals(AttentionHeadline.SEPARATED_TAG, a.headline)
        assertEquals(AttentionReasonKind.SEPARATED_TAG, a.reasons.first().kind)
        assertEquals(listOf("34", "3"), a.reasons.first().args)
        // Not for a radio the owner set aside, and not without the evidence.
        assertNull(AttentionEngine.assess(c.copy(userState = UserEntityState.MINE)))
        assertFalse(AttentionEngine.assess(c.copy(separated = null))?.needsAttention == true)
    }
}
