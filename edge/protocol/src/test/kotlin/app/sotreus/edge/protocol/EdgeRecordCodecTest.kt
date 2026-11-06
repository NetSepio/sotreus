package app.sotreus.edge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import kotlin.random.Random

class EdgeRecordCodecTest {
    private val golden: Map<String, ByteArray> by lazy {
        javaClass.getResource("/golden-records.txt")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.substringBefore(' ') to Hex.decode(line.substringAfter(' '))!! }
    }

    private fun ok(bytes: ByteArray): EdgeRecord = (EdgeRecordCodec.decode(bytes) as EdgeRecordCodec.Result.Ok).record

    @Test fun crcMatchesZlibAndTheCheckValue() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
        val rnd = Random(7)
        repeat(50) {
            val b = rnd.nextBytes(rnd.nextInt(0, 600))
            val z = CRC32().apply { update(b) }.value
            assertEquals(z, Crc32.of(b))
        }
    }

    @Test fun probeHashMatchesFirmwareVectors() {
        // sotreus-edge/tests/host/test_main.c
        assertEquals(2166136261L, ProbeNameHash.of(ByteArray(0), ByteArray(0)))
        assertEquals(0xE40C292CL, ProbeNameHash.of(ByteArray(0), "a".encodeToByteArray()))
        assertEquals(ProbeNameHash.of(byteArrayOf(1, 2), "Home".encodeToByteArray()), ProbeNameHash.of("0102", "Home"))
        assertNull(ProbeNameHash.of("0", "x"))
    }

    @Test fun goldenBleRecordFromFirmwareStructs() {
        val r = ok(golden.getValue("BLE"))
        assertEquals(0x01020304L, r.header.seq)
        assertEquals(EdgeRecordType.BLE, r.header.type)
        assertTrue(r.header.timeValid)
        assertTrue(r.header.continues)
        assertFalse(r.header.truncated)
        assertEquals(0xBEEF, r.header.boot)
        assertEquals(1762400000123L, r.header.firstMs)
        assertEquals(1762400300456L, r.header.lastMs)
        val b = r.body as BleSighting
        assertEquals("C4:5B:BE:01:02:03", b.address)
        assertTrue(b.randomAddress)
        assertEquals(3, b.lastPdu)
        assertEquals(-67, b.rssiLast)
        assertEquals(-52, b.rssiMax)
        assertEquals(-90, b.rssiMin)
        assertTrue(b.hasScanResponse)
        assertTrue(b.connectable)
        assertEquals(70000L, b.reports)
        assertEquals("02010605095461673103FF4C00", b.advHex)
        assertEquals("0303AAFE", b.scanResponseHex)
    }

    @Test fun goldenApRecordWithUtf8SsidAndRemoteId() {
        val r = ok(golden.getValue("AP"))
        assertEquals(77L, r.header.seq)
        assertFalse(r.header.timeValid)
        assertEquals(12, r.header.boot)
        assertEquals(5000L, r.header.firstMs)
        val ap = r.body as WifiApSighting
        assertEquals("24:2F:D0:BD:0F:10", ap.address)
        assertEquals(11, ap.channel)
        assertEquals(WifiAuth.WPA2_WPA3, ap.auth)
        assertEquals("Café Net", ap.ssid)
        assertFalse(ap.hidden)
        assertTrue(ap.hasRemoteId)
        assertEquals("FA0BBC0D01", ap.remoteIdHex)
        assertEquals(100, ap.beaconIntervalTu)
        assertEquals(30L, ap.frames)
    }

    @Test fun goldenProbeRecordWithMaxSeqAndClearNames() {
        val r = ok(golden.getValue("PROBE"))
        assertEquals(4294967295L, r.header.seq)
        val p = r.body as WifiProbeSighting
        assertEquals("CA:2A:F0:E6:B6:01", p.address)
        assertTrue(p.randomisedMac)
        assertTrue(p.namesInClear)
        assertTrue(p.moreNames)
        assertFalse(p.wildcardSeen)
        assertEquals(listOf(0x9A1B2C3DL, 0xE40C292CL), p.nameHashes)
        assertEquals(listOf("Home", "a"), p.names)
    }

    @Test fun goldenEventRecord() {
        val e = ok(golden.getValue("EVENT")).body as EdgeEventRecord
        assertEquals(EdgeEventRecord.MODE, e.code)
        assertEquals("awareness", e.detail)
    }

    @Test fun encoderMatchesFirmwareBytes() {
        val src = golden.getValue("EVENT")
        val r = ok(src)
        val again = EdgeRecordCodec.encode(r.header, EdgeBodies.event(EdgeEventRecord.MODE, "awareness"))
        assertEquals(Hex.encode(src), Hex.encode(again))
    }

    @Test fun anyFlippedBitFailsTheCrc() {
        val src = golden.getValue("BLE")
        for (i in listOf(0, 4, 27, 28, 100, 251, 252, 255)) {
            val b = src.copyOf()
            b[i] = (b[i].toInt() xor 0x10).toByte()
            assertTrue("byte $i", EdgeRecordCodec.decode(b) is EdgeRecordCodec.Result.Corrupt)
        }
    }

    @Test fun shortAndWrongFormatRecordsAreCorrupt() {
        assertTrue(EdgeRecordCodec.decode(ByteArray(255)) is EdgeRecordCodec.Result.Corrupt)
        val h = EdgeRecordHeader(5, 1, 0, 0, 0, 0, 0, format = 2)
        val r = EdgeRecordCodec.decode(EdgeRecordCodec.encode(h, ByteArray(0)))
        assertEquals("unsupported format 2", (r as EdgeRecordCodec.Result.Corrupt).reason)
        assertEquals(5L, r.seq)
    }

    @Test fun decodesAtAnOffset() {
        val two = golden.getValue("AP") + golden.getValue("EVENT")
        assertEquals(9L, (EdgeRecordCodec.decode(two, 256) as EdgeRecordCodec.Result.Ok).record.header.seq)
    }

    @Test fun roundTripsEveryBodyThroughTheBuilders() {
        val h = { type: Int -> EdgeRecordHeader(1, type, 1, 2, -5, 10, 0, 1) }
        val ble = ok(EdgeRecordCodec.encode(h(1), EdgeBodies.ble("AA:BB:CC:DD:EE:FF", addressType = 0, rssiLast = -40, adv = ByteArray(40) { 1 })))
        assertEquals(31 * 2, (ble.body as BleSighting).advHex.length)
        assertFalse((ble.body as BleSighting).randomAddress)
        assertEquals(-5L, ble.header.firstMs)

        val hiddenAp = ok(EdgeRecordCodec.encode(h(2), EdgeBodies.ap("00:11:22:33:44:55", 6, -80, ssid = "", hidden = true))).body as WifiApSighting
        assertTrue(hiddenAp.hidden)
        assertEquals("", hiddenAp.ssid)

        val probe = ok(EdgeRecordCodec.encode(h(3), EdgeBodies.probe("02:00:00:00:00:01", 6, -60, hashes = listOf(1, 2, 3, 4, 5)))).body as WifiProbeSighting
        assertEquals(4, probe.nameHashes.size)
        assertTrue(probe.names.isEmpty())

        val pos = ok(EdgeRecordCodec.encode(h(6), EdgeBodies.position(12.9716, 77.5946, 920.5, 3.2f, null, 11, 3, 1.25f))).body as EdgePositionRecord
        assertEquals(12.9716, pos.lat, 1e-7)
        assertEquals(77.5946, pos.lon, 1e-7)
        assertEquals(920.5, pos.altitudeM!!, 1e-3)
        assertEquals(3.2f, pos.horizontalAccuracyM!!, 1e-3f)
        assertNull(pos.verticalAccuracyM)
        assertTrue(pos.hasFix)
        assertEquals(1.25f, pos.speedMps!!, 1e-3f)

        val neg = ok(EdgeRecordCodec.encode(h(6), EdgeBodies.position(-33.8688, -151.2093, null, null, null, 0, 0, null))).body as EdgePositionRecord
        assertEquals(-33.8688, neg.lat, 1e-7)
        assertNull(neg.altitudeM)
        assertFalse(neg.hasFix)

        assertTrue(ok(EdgeRecordCodec.encode(h(99), ByteArray(3) { 7 })).body is UnknownRecordBody)
    }

    @Test fun seenBodyMayBeShorterThanTheFullBody() {
        val body = EdgeBodies.ble("C4:5B:BE:01:02:03", rssiLast = -50).copyOf(18)
        val b = EdgeRecordCodec.decodeBody(EdgeRecordType.BLE, body) as BleSighting
        assertEquals("C4:5B:BE:01:02:03", b.address)
        assertEquals("", b.advHex)
    }
}
