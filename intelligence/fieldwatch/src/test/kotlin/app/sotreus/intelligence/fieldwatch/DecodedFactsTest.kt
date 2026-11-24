package app.sotreus.intelligence.fieldwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodedFactsTest {
    private fun radio(kind: RadioKind, facts: RadioFacts) = Sighting(
        key = "k", kind = kind, mac = "00:11:22:33:44:55", name = "", rssi = -50, rssiMin = -50, rssiMax = -50, channel = 0,
        frequencyMhz = 0, vendor = null, randomized = false, hiddenSsid = false, serviceUuids = emptyList(), manufacturerId = null,
        manufacturerDataHex = "", rawHex = "", extras = "", firstSeen = 1, lastSeen = 1, hitCount = 1, fleetIds = emptySet(),
        rssiHistory = emptyList(), presence = emptyList(), facts = facts,
    )

    private fun rows(kind: RadioKind, facts: RadioFacts, masked: Boolean = false) =
        DecodedFacts.rows(radio(kind, facts), masked).associate { it.label to it.value }

    @Test
    fun homekitShowsCategoryPairingAndAMaskableId() {
        val facts = RadioFacts(homekit = HomekitFacts(false, 7, "AA:BB:CC:DD:EE:FF", 1, 2, 2, false))
        val open = rows(RadioKind.BLE, facts)
        assertEquals("Outlet", open["HomeKit category"])
        assertEquals("Not paired", open["HomeKit pairing"])
        assertEquals("AA:BB:CC:DD:EE:FF", open["HomeKit device ID"])
        assertFalse(rows(RadioKind.BLE, facts, masked = true).values.any { it.contains("DD:EE:FF") })
    }

    @Test
    fun accessPointFactsAndElementNames() {
        val facts = RadioFacts(
            interworking = Interworking(3, internet = true, emergencyReachable = false, venueGroup = 10, venueType = 6, hessid = "02:00:00:00:00:01"),
            vendorIes = listOf(VendorIeRecord("00:50:F2", 4, "10"), VendorIeRecord("50:6F:9A", 0x09, ""), VendorIeRecord("AA:BB:CC", 1, "")),
        )
        val open = rows(RadioKind.WIFI, facts)
        assertEquals("Free public network", open["Network type"])
        assertEquals("Vehicular · Train", open["Venue"])
        assertEquals("WPS (Wi-Fi Protected Setup), Wi-Fi Direct", open["Wi-Fi elements"])
        assertFalse(rows(RadioKind.WIFI, facts, masked = true).values.any { it.contains("00:00:01") })
    }

    @Test
    fun remoteIdAuthenticationSaysItIsNotChecked() {
        val row = DecodedFacts.rows(radio(RadioKind.BLE, RadioFacts()).copy(payloadAuth = RemoteIdAuth(1, 0)), false).single()
        assertEquals(FactRow("Authentication", "UAS ID signature, not checked"), row)
    }

    @Test
    fun everyRowPassesTheCopyRules() {
        val banned = listOf("threat", "spy", "tracked", "tracking you", "surveillance detected", "unsafe", "verified location", "you are being")
        val lines = ExplainLines.all + HomekitLines.all + MatterLines.all + SidewalkLines.all + BthomeLines.all + ExposureLines.all +
            FrenchRidLines.all + RidAuthLines.all + SotreusLines.all
        val bad = lines.filter { line -> banned.any { it in line.text.lowercase() } }.map { it.key }
        assertTrue(bad.toString(), bad.isEmpty())
    }

    @Test
    fun aSafetySensorIsNotASafetyVerdict() {
        val sensor = RadioFacts(bthome = Bthome.readValue(byteArrayOf(0x40, 0x28, 0x00)))
        assertEquals(mapOf("Safety" to "Alarm"), rows(RadioKind.BLE, sensor))
    }

    @Test
    fun aPlainRadioHasNoRows() {
        assertTrue(DecodedFacts.rows(radio(RadioKind.BLE, RadioFacts()), false).isEmpty())
    }
}
