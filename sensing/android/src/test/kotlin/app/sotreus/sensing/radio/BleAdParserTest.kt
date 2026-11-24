package app.sotreus.sensing.radio

import app.sotreus.intelligence.fieldwatch.RadioFacts
import app.sotreus.sensing.radio.BleAdParser.structured
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bytes both the phone's scanner and the Edge hand over, down to the structured facts. */
class BleAdParserTest {
    private fun facts(hex: String): RadioFacts {
        val p = BleAdParser.parse(bytes(hex))
        return RadioFacts(mfgRecords = p.mfg, serviceData = p.serviceData).structured(p.uuids + p.serviceData.map { it.uuid })
    }

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun bthomeSensorFromRawAdvertisement() {
        val ad = "0201060B094449592D73656E736F720A16D2FC4002C40903BF13"
        val parsed = BleAdParser.parse(bytes(ad))
        assertEquals("DIY-sensor", parsed.localName)
        assertEquals("FCD2", parsed.serviceData.single().uuid)
        val objects = facts(ad).bthome!!.objects.associate { it.name to "${it.numberText} ${it.unit}" }
        assertEquals("25.00 °C", objects["Temperature"])
        assertEquals("50.55 %", objects["Humidity"])
    }

    @Test
    fun matterSetupFromServiceData() {
        // Commissionable, discriminator 0xF00, vendor FFF1, product 8001.
        val matter = facts("0B16F6FF00000FF1FF018000").matter!!
        assertTrue(matter.commissionable)
        assertEquals(0xF00, matter.discriminator)
        assertEquals(0xFFF1, matter.vendorId)
        assertEquals(0x8001, matter.productId)
    }

    @Test
    fun homekitOutletFromAppleData() {
        // Company 004C, type 06, length 2D (longer than the element), not paired, outlet.
        val hk = facts("12FF4C00062D01AABBCCDDEEFF0700010001029A").homekit!!
        assertEquals("Outlet", hk.categoryName)
        assertEquals(false, hk.paired)
        assertEquals("AA:BB:CC:DD:EE:FF", hk.deviceId)
    }

    @Test
    fun sidewalkNeedsTheAmaServiceAndAmazonsCompany() {
        val beacon = "0303FE03" + "05FF71010403"
        assertEquals("REGISTRATION", facts(beacon).sidewalk!!.beacon!!.name)
        assertNull(facts("05FF71010403").sidewalk)
    }

    @Test
    fun plainAdvertisementHasNoStructuredFacts() {
        val f = facts("020106")
        assertNull(f.homekit)
        assertNull(f.matter)
        assertNull(f.sidewalk)
        assertNull(f.bthome)
    }
}
