package app.sotreus.edge.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingAndMessagesTest {
    @Test fun framerSplitsLinesAcrossChunksAndMultibyteCharacters() {
        val f = NdjsonFramer()
        val text = "{\"ev\":\"seen\",\"label\":\"Café\"}\n{\"ev\":\"obs\",\"seq\":5,\"type\":1}\n"
        val bytes = text.encodeToByteArray()
        val lines = NdjsonFramer.chunks(bytes, 7).flatMap { f.feed(it) }
        assertEquals(2, lines.size)
        assertEquals("{\"ev\":\"seen\",\"label\":\"Café\"}", lines[0])
        // Split exactly inside the two-byte "é".
        val at = bytes.indexOfFirst { it == 0xC3.toByte() } + 1
        val g = NdjsonFramer()
        assertTrue(g.feed(bytes.copyOfRange(0, at)).isEmpty())
        assertEquals("{\"ev\":\"seen\",\"label\":\"Café\"}", g.feed(bytes.copyOfRange(at, bytes.size))[0])
    }

    @Test fun framerKeepsSeveralObjectsInOneChunkAndSkipsBlankLines() {
        val f = NdjsonFramer()
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), f.feed("{\"a\":1}\n\n{\"b\":2}\n{\"c\"".encodeToByteArray()))
        assertEquals(listOf("{\"c\":3}"), f.feed(":3}\n".encodeToByteArray()))
    }

    @Test fun framerDropsOversizedLinesAndRecovers() {
        val f = NdjsonFramer(maxLineBytes = 16)
        assertTrue(f.feed(("{\"x\":\"" + "y".repeat(40) + "\"}\n").encodeToByteArray()).isEmpty())
        assertEquals(1, f.overflows)
        assertEquals(listOf("{\"ok\":1}"), f.feed("{\"ok\":1}\n".encodeToByteArray()))
    }

    @Test fun frameAndChunks() {
        assertArrayEquals("{}\n".encodeToByteArray(), NdjsonFramer.frame("{}"))
        assertArrayEquals("{}\n".encodeToByteArray(), NdjsonFramer.frame("{}\n"))
        val parts = NdjsonFramer.chunks(ByteArray(1030) { it.toByte() }, 514)
        assertEquals(listOf(514, 514, 2), parts.map { it.size })
    }

    @Test fun recordAssemblerCountsBytes() {
        val a = RecordStreamAssembler()
        val two = ByteArray(512) { (it / 256).toByte() }
        val out = NdjsonFramer.chunks(two, 509).flatMap { a.feed(it) }
        assertEquals(2, out.size)
        assertEquals(0, out[0][255].toInt())
        assertEquals(1, out[1][0].toInt())
        assertEquals(0, a.pending)
        a.feed(ByteArray(10))
        assertEquals(10, a.pending)
    }

    @Test fun infoParsesCapabilitiesAndIgnoresUnknownKeys() {
        val line = """{"protocol":1,"device_id":"9f3c11","name":"Sotreus Edge","suffix":"96FF","fw":"0.1.0","hw":"heltec_wifi_lora_32_v3",
            "boot":12,"record_size":256,"record_format":1,"probe_salt":"0a0b","future":{"x":1},
            "caps":{"ble_observation":true,"gnss":false,"live_sightings":true,"new_thing":true,"weird":3}}"""
        val info = EdgeInfo.parse(EdgeJson.parseToJsonElement(line).jsonObject)!!
        assertEquals("96FF", info.suffix)
        assertTrue(info.supported)
        assertTrue(info.caps.bleObservation)
        assertFalse(info.caps.gnss)
        assertTrue(info.caps.liveSightings)
        assertFalse(info.caps.placement)
        assertEquals(true, info.caps.raw["new_thing"])
        assertNull(info.caps.raw["weird"])
        assertNull(EdgeInfo.parse(JsonObject(emptyMap())))
    }

    @Test fun statusAndConfigParse() {
        val s = EdgeStatus.parse(EdgeJson.parseToJsonElement(
            """{"uptime_ms":61000,"time_valid":true,"time_source":"phone","unix_ms":1762400000000,"mode":"awareness",
               "battery":{"present":true,"source":"battery","mv":3900,"percent_approx":72},
               "link":{"mode":"off","state":"off","ssid":"","ip":"","stations":0,"channel":3,"sniffing":true,"hopping":true},
               "awareness":{"running":true,"open_sightings":{"ble":31,"wifi_ap":24,"wifi_probe":9},"records":170},
               "log":{"head":230,"oldest":1,"count":230,"capacity":15616,"erasing":false,"write_failures":0}}""",
        ).jsonObject)
        assertEquals(EdgeMode.AWARENESS, s.mode)
        assertEquals(72, s.battery!!.percentApprox)
        assertTrue(s.link!!.hopping)
        assertEquals(24, s.awareness!!.openAp)
        assertEquals(230L, s.log!!.head)

        val c = EdgeConfig.parse(EdgeJson.parseToJsonElement(
            """{"mode":"mesh","link":"auto","awareness":{"ble":true,"ble_active":false,"wifi_aps":true,"wifi_probes":true,"probe_names":false,
               "rssi_floor":-95,"close_after_s":60,"max_window_s":300},"placement":{"mode":"stationary","lat":12.5,"lon":77.25,"accuracy_m":8,
               "set_unix_ms":1,"source":"phone"},"mesh":{"region":"IN"}}""",
        ).jsonObject)
        assertEquals(EdgeMode.MESH, c.mode)
        assertEquals(EdgeLinkMode.AUTO, c.link)
        assertFalse(c.awareness!!.bleActive)
        assertEquals(DeploymentMode.STATIONARY, c.placement!!.mode)
        assertTrue(c.placement!!.positioned)
        assertEquals("IN", c.raw["mesh"]!!.jsonObject["region"]!!.jsonPrimitive.content)
        assertEquals(c.placement, EdgePlacement.parse(c.placement!!.toJson()))
    }

    @Test fun eventsParse() {
        val seen = EdgeEvent.parse("""{"ev":"seen","kind":"wifi_probe","addr":"ca:2a:f0:e6:b6:01","rssi":-52,"uptime_ms":9,"body":"AQID"}""") as EdgeEvent.Seen
        assertEquals(SightingKind.WIFI_PROBE, seen.kind)
        assertEquals("CA:2A:F0:E6:B6:01", seen.address)
        assertArrayEquals(byteArrayOf(1, 2, 3), seen.body)
        assertNull((EdgeEvent.parse("""{"ev":"seen","kind":"ble","addr":"AA:BB:CC:DD:EE:FF","rssi":-1,"uptime_ms":1}""") as EdgeEvent.Seen).body)
        assertNull(EdgeEvent.parse("""{"ev":"seen","kind":"zigbee","addr":"x"}"""))
        assertEquals(EdgeEvent.Obs(42, 1), EdgeEvent.parse("""{"ev":"obs","seq":42,"type":1}"""))
        val done = EdgeEvent.parse("""{"ev":"sync_done","id":7,"ok":true,"sent":500,"next":501,"head":900,"more":true}""") as EdgeEvent.SyncDone
        assertTrue(done.more)
        assertEquals(7L, done.id)
        val resp = EdgeEvent.parse("""{"ev":"resp","id":3,"ok":false,"error":"no memory"}""") as EdgeEvent.Response
        assertFalse(resp.ok)
        assertEquals("no memory", resp.error)
        assertTrue(EdgeEvent.parse("""{"ev":"mesh_rx","msg":{}}""") is EdgeEvent.Other)
        assertNull(EdgeEvent.parse("not json"))
        assertNull(EdgeEvent.parse("[1,2]"))
        val status = EdgeEvent.parse("""{"ev":"status","status":{"uptime_ms":5,"mode":"mesh"}}""") as EdgeEvent.Status
        assertEquals(EdgeMode.MESH, status.status.mode)
    }

    @Test fun livePageSkipsMalformedRows() {
        val o = EdgeJson.parseToJsonElement(
            """{"ev":"resp","ok":true,"total":3,"next":null,"uptime_ms":100,
               "rows":[["ble","c4:5b:be:01:02:03",-60,-55,1200,14],["wifi_ap","24:2F:D0:BD:0F:10",-70,-66,300,4],["bad"],["ble",5,1,1,1,1]]}""",
        ).jsonObject
        val p = LivePage.parse(o)
        assertEquals(2, p.rows.size)
        assertEquals("C4:5B:BE:01:02:03", p.rows[0].address)
        assertEquals(1200L, p.rows[0].agoMs)
        assertEquals(SightingKind.WIFI_AP, p.rows[1].kind)
        assertNull(p.nextOffset)
    }

    @Test fun commandsCarryIdAndFields() {
        val o = EdgeJson.parseToJsonElement(EdgeCommands.setTime(9, 1762400000000, 330)).jsonObject
        assertEquals("set_time", o["cmd"]!!.jsonPrimitive.content)
        assertEquals("9", o["id"]!!.jsonPrimitive.content)
        assertEquals("phone", o["source"]!!.jsonPrimitive.content)
        val w = EdgeJson.parseToJsonElement(EdgeCommands.watch(1, SightingKind.WIFI_PROBE, "CA:2A:F0:E6:B6:01")).jsonObject
        assertEquals("wifi", w["kind"]!!.jsonPrimitive.content)
        assertEquals("600", w["seconds"]!!.jsonPrimitive.content)
        val s = EdgeJson.parseToJsonElement(EdgeCommands.setConfig(2, JsonObject(mapOf("link" to JsonPrimitive("off"))))).jsonObject
        assertEquals("off", s["config"]!!.jsonObject["link"]!!.jsonPrimitive.content)
    }

    @Test fun macHelpers() {
        assertEquals("C4:5B:BE:01:02:03", Mac.format(Mac.parse("c4-5b-be-01-02-03")!!))
        assertNull(Mac.parse("c4:5b"))
        assertTrue(Mac.isLocallyAdministered("CA:2A:F0:E6:B6:01"))
        assertFalse(Mac.isLocallyAdministered("C4:5B:BE:01:02:03"))
    }
}
