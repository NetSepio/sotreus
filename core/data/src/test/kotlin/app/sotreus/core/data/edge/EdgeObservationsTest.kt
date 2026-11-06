package app.sotreus.core.data.edge

import app.sotreus.edge.protocol.EdgeBodies
import app.sotreus.edge.protocol.EdgeRecordCodec
import app.sotreus.edge.protocol.EdgeRecordType
import app.sotreus.edge.protocol.SightingBody
import app.sotreus.edge.protocol.SightingKind
import app.sotreus.edge.protocol.WifiAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.sotreus.intelligence.fieldwatch.RadioKind as FwKind

class EdgeObservationsTest {
    private fun body(type: Int, bytes: ByteArray) = EdgeRecordCodec.decodeBody(type, bytes) as SightingBody

    @Test fun bleRecordMatchesWhatThePhonePathBuilds() {
        val adv = byteArrayOf(0x02, 0x01, 0x06, 0x05, 0x09, 'T'.code.toByte(), 'a'.code.toByte(), 'g'.code.toByte(), '1'.code.toByte(), 0x05, 0xFF.toByte(), 0x4C, 0x00, 0x12, 0x34)
        val b = body(EdgeRecordType.BLE, EdgeBodies.ble("C4:5B:BE:01:02:03", addressType = 1, rssiLast = -61, adv = adv))
        val o = EdgeObservations.of(b, atMs = 1_000, rssi = -61)
        assertEquals(FwKind.BLE, o.kind)
        assertEquals("C4:5B:BE:01:02:03", o.mac)
        assertEquals("Tag1", o.name)
        assertEquals(0x004C, o.manufacturerId)
        assertEquals("Random", o.facts.addressType)
        assertEquals(-61, o.rssi)
        assertEquals(1_000L, o.at)
        assertEquals("ble:C4:5B:BE:01:02:03", EdgeObservations.key(b))
    }

    @Test fun apRecordCarriesSecurityAndRemoteIdElement() {
        val rid = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte(), 0x0D, 0x01, 0x02)
        val b = body(EdgeRecordType.WIFI_AP, EdgeBodies.ap("24:2F:D0:BD:0F:10", 11, -70, auth = WifiAuth.WPA3, ssid = "Office", remoteId = rid))
        val o = EdgeObservations.of(b, 5)
        assertEquals(FwKind.WIFI, o.kind)
        assertEquals("Office", o.name)
        assertEquals(2462, o.frequencyMhz)
        assertEquals(11, o.channel)
        assertFalse(o.hiddenSsid)
        assertEquals("[RSN-SAE-CCMP][ESS]", o.facts.capabilities)
        assertEquals("FA:0B:BC", o.facts.vendorIes.single().oui)
        assertEquals(0x0D, o.facts.vendorIes.single().type)
        assertEquals("0102", o.facts.vendorIes.single().dataHex)
        assertTrue(o.fresh)
    }

    @Test fun probeRecordIsMarkedAsAClient() {
        val b = body(EdgeRecordType.WIFI_PROBE, EdgeBodies.probe("CA:2A:F0:E6:B6:01", 6, -52))
        val o = EdgeObservations.of(b, 9)
        assertTrue(EdgeHub.isProbe(o))
        assertEquals(SightingKind.WIFI_PROBE, EdgeHub.kindOf(o))
        assertEquals("", o.name)
        assertEquals("wifi:CA:2A:F0:E6:B6:01", EdgeObservations.key(b))
    }

    @Test fun bareBodiesKeepAddressAndSignal() {
        val o = EdgeObservations.of(EdgeObservations.bare(SightingKind.WIFI_AP, "24:2F:D0:BD:0F:10", -80, -75), 1)
        assertTrue(o.hiddenSsid)
        assertEquals(-80, o.rssi)
        assertEquals(0, EdgeObservations.frequency(0))
        assertEquals(2484, EdgeObservations.frequency(14))
    }
}
