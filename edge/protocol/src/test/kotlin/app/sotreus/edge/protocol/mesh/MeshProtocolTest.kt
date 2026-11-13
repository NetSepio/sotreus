package app.sotreus.edge.protocol.mesh

import app.sotreus.edge.protocol.EdgeEvent
import app.sotreus.edge.protocol.EdgeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes follow the firmware's JSON builders (sotreus-edge main/mesh/mesh.c, edge_api.c). */
class MeshProtocolTest {
    private fun json(s: String) = EdgeJson.parseToJsonElement(s) as JsonObject

    @Test fun nodeIdsRoundTrip() {
        assertEquals("!5ac1e0d2", MeshNodeId.format(0x5AC1E0D2L))
        assertEquals(0x5AC1E0D2L, MeshNodeId.parse("!5ac1e0d2"))
        assertEquals(0x5AC1E0D2L, MeshNodeId.parse("5AC1E0D2"))
        assertEquals(MeshNodeId.BROADCAST, MeshNodeId.parse("!ffffffff"))
        assertNull(MeshNodeId.parse("unknown"))
        assertNull(MeshNodeId.parse("!123456789"))
        assertNull(MeshNodeId.parse(""))
    }

    @Test fun deliveryOnlyMovesForwardButALateAckWins() {
        assertEquals(MeshDelivery.SENT, MeshDelivery.QUEUED.advance(MeshDelivery.SENT))
        assertEquals(MeshDelivery.RELAYED, MeshDelivery.RELAYED.advance(MeshDelivery.SENT))
        assertEquals(MeshDelivery.NO_ACK, MeshDelivery.NO_ACK.advance(MeshDelivery.RELAYED))
        assertEquals(MeshDelivery.DELIVERED, MeshDelivery.NO_ACK.advance(MeshDelivery.DELIVERED))
        assertEquals(MeshDelivery.DELIVERED, MeshDelivery.DELIVERED.advance(MeshDelivery.FAILED))
        assertTrue(MeshDelivery.FAILED.done)
        assertFalse(MeshDelivery.RELAYED.done)
        assertNull(MeshDelivery.of("lost"))
    }

    @Test fun textLimitCountsUtf8Bytes() {
        assertEquals(23, MeshText.bytes("See you at the top 🙂"))
        assertTrue(MeshText.fits("a".repeat(200)))
        assertFalse(MeshText.fits("a".repeat(201)))
        assertFalse(MeshText.fits(""))
        // 50 emoji are 200 bytes; one more character does not fit.
        val emoji = "🙂".repeat(50)
        assertTrue(MeshText.fits(emoji))
        assertFalse(MeshText.fits(emoji + "a"))
        // Clipping never splits a character.
        val clipped = MeshText.clip("a" + "🙂".repeat(50))
        assertEquals(197, MeshText.bytes(clipped))
        assertEquals(1 + 49 * 2, clipped.length)
    }

    @Test fun receivedAndSentMessagesParse() {
        val rx = EdgeEvent.parse(
            """{"ev":"mesh_rx","msg":{"id":305419896,"from":"!a1b2c3d4","to":"!ffffffff","channel":0,"dir":"received",
               "provenance":"NETWORK","pki":false,"ago_s":4,"unix_s":1762400000,"rssi":-98,"snr":6.5,"text":"Reached the ridge","from_name":"BASE"}}""",
        ) as EdgeEvent.MeshRx
        val m = rx.message
        assertEquals(305419896L, m.packetId)
        assertEquals(0xA1B2C3D4L, m.from)
        assertTrue(m.broadcast)
        assertFalse(m.outgoing)
        assertEquals(-98, m.rssi)
        assertEquals(6.5, m.snr!!, 0.0)
        assertEquals("BASE", m.peerShortName)
        assertNull(m.delivery)

        val sent = MeshMessage.parseList(json(
            """{"ok":true,"messages":[{"id":7,"from":"!5ac1e0d2","to":"!a1b2c3d4","channel":1,"dir":"sent","pki":true,
               "status":"relayed","reply_id":305419896,"reaction":true,"text":"👍","to_name":"BASE"},{"id":8}]}""",
        ))
        assertEquals(1, sent.size)
        val s = sent.single()
        assertTrue(s.outgoing)
        assertTrue(s.pki)
        assertTrue(s.reaction)
        assertEquals(305419896L, s.replyId)
        assertEquals(MeshDelivery.RELAYED, s.delivery)
        assertNull(s.rssi)
        assertEquals("BASE", s.peerShortName)
    }

    @Test fun deliveryAndOtherMeshEventsParse() {
        val tx = EdgeEvent.parse("""{"ev":"mesh_tx","id":7,"status":"failed","reason":3}""") as EdgeEvent.MeshTx
        assertEquals(MeshDelivery.FAILED, tx.delivery)
        assertEquals(3, tx.reason)
        assertTrue(EdgeEvent.parse("""{"ev":"mesh_tx","id":7,"status":"weird"}""") is EdgeEvent.Other)

        val route = (EdgeEvent.parse(
            """{"ev":"mesh_traceroute","id":9,"to":"!a1b2c3d4","route":["!11112222","unknown"],"snr_towards":[7.0,null,5.5],
               "route_back":["!11112222"],"snr_back":[6.0,6.75]}""",
        ) as EdgeEvent.MeshRoute).trace
        assertEquals(listOf(0x11112222L, null), route.route)
        assertEquals(listOf(7.0, null, 5.5), route.snrTowards)
        assertEquals(listOf(6.0, 6.75), route.snrBack)

        assertEquals(42L, (EdgeEvent.parse("""{"ev":"mesh_waypoint","id":42}""") as EdgeEvent.MeshWaypointHeard).id)
        val admin = EdgeEvent.parse("""{"ev":"mesh_admin","from":"!a1b2c3d4","request":5,"outcome":"refused"}""") as EdgeEvent.MeshRemote
        assertFalse(admin.hardware)
        assertEquals("refused", admin.outcome)
        assertTrue((EdgeEvent.parse("""{"ev":"mesh_rhw","from":"!a1b2c3d4","request":1,"outcome":"done"}""") as EdgeEvent.MeshRemote).hardware)
        assertTrue(EdgeEvent.parse("""{"ev":"mesh_rx","msg":{}}""") is EdgeEvent.Other)
    }

    @Test fun nodesParseWithPositionTelemetryAndNeighbours() {
        val heard = EdgeEvent.parse(
            """{"ev":"mesh_node","node":{"id":"!a1b2c3d4","num":2712847316,"long_name":"Base Camp","short_name":"BASE","hw_model":43,
               "role":"CLIENT_BASE","has_public_key":true,"public_key":"AAAA","favorite":true,"ignored":false,"last_heard_ago_s":120,
               "last_heard_unix_s":1762400000,"rssi":-98,"snr":6.5,"hops_away":2,
               "reported_position":{"lat":12.989,"lon":77.615,"alt_m":920,"precision_bits":13,"reported_unix_s":1762399800,"provenance":"NETWORK"},
               "device":{"battery_level":84,"voltage":3.98,"channel_util":4.1,"air_util_tx":0.8,"uptime_s":273600},
               "environment":{"temperature_c":24.6,"humidity_pct":61.0,"pressure_hpa":1009.0},
               "neighbors":[{"id":"!11112222","snr":7.0},{"id":"bad","snr":1}]}}""",
        ) as EdgeEvent.MeshNodeHeard
        val n = heard.node
        assertEquals(0xA1B2C3D4L, n.num)
        assertEquals("!a1b2c3d4", n.id)
        assertEquals("CLIENT_BASE", n.role)
        assertTrue(n.favorite && n.hasPublicKey)
        assertEquals(2, n.hopsAway)
        assertEquals(13, n.position!!.precisionBits)
        assertEquals(2914.0, n.position!!.precisionM!!, 1.0)
        assertEquals(84, n.device!!.batteryLevel)
        assertFalse(n.device!!.onPower)
        assertEquals(24.6, n.environment!!.temperatureC!!, 0.0)
        assertEquals(listOf(MeshNeighbor(0x11112222L, 7.0)), n.neighbors)

        // A node heard but never introduced: no names, no position; (0, 0) is "no position".
        val bare = MeshNode.parse(json("""{"id":"!0000beef","has_public_key":false,"favorite":false,"ignored":false,"rssi":-120,"snr":-12.25,
            "reported_position":{"lat":0,"lon":0}}"""))!!
        assertNull(bare.longName)
        assertNull(bare.position)
        assertEquals(0xBEEFL, bare.num)
    }

    @Test fun nodePagesCarryTotalAndNext() {
        val page = MeshNodePage.parse(json("""{"ok":true,"nodes":[{"id":"!00000001"},{"id":"!00000002"}],"total":20,"next":16}"""))
        assertEquals(2, page.nodes.size)
        assertEquals(20, page.total)
        assertEquals(16, page.nextOffset)
        val last = MeshNodePage.parse(json("""{"ok":true,"nodes":[],"total":20,"next":null}"""))
        assertNull(last.nextOffset)
        // An Edge without paging answers only `nodes`.
        val whole = MeshNodePage.parse(json("""{"ok":true,"nodes":[{"id":"!00000001"}]}"""))
        assertNull(whole.total)
        assertNull(whole.nextOffset)
    }

    @Test fun statusNamesTheTooWidePreset() {
        val s = MeshStatus.parse(json(
            """{"radio_present":true,"running":true,"node_id":"!5ac1e0d2","node_num":1522655442,"role":"CLIENT","region":"EU_868",
               "freq_mhz":869.525,"duty_cycle_pct":10,"preset":"LONG_FAST","preset_too_wide":"SHORT_TURBO","channel_util_pct":4.1,
               "air_util_tx_pct":0.8,"polite_hold":false,"channels":[{"index":0,"name":"","hash":8,"encrypted":true}],"nodes":6,
               "store_forward_routers":["!11112222"],"admin":{"enabled":true,"mode":"open","keys":0,"requests":2,"refused":1},
               "counters":{"rx":1284,"tx":96,"relayed":40}}""",
        ))
        assertEquals("SHORT_TURBO", s.presetTooWide)
        assertEquals("LONG_FAST", s.preset)
        assertEquals(listOf(0x11112222L), s.storeForwardRouters)
        assertTrue(s.adminOpen)
        assertEquals(1284L, s.rx)
        // Region UNSET: the Edge writes null and the radio never transmits.
        assertNull(MeshStatus.parse(json("""{"running":true,"region":null}""")).region)
    }

    @Test fun configKeepsUnknownFieldsAndPatchesSendEveryChannelKey() {
        val mesh = json(
            """{"region":"IN","preset":"LONG_TURBO","hop_limit":3,"rebroadcast":"all","tx_power":0,"node_id":"!5ac1e0d2",
               "long_name":"Sotreus Edge 96FF","short_name":"SE2B","role":"CLIENT_BASE","licensed":false,
               "channels":[{"name":"","psk":"default","position_precision":13},{"name":"Team","psk":"base64:AAAA","position_precision":19,"uplink":true}],
               "position":{"mode":"client","interval_s":900},"canned":["OK","On my way"],"mqtt":{"enabled":false},"future_key":{"x":1}}""",
        )
        val c = MeshConfig.parse(mesh)!!
        assertTrue(c.regionSet)
        assertEquals("LONG_TURBO", c.preset)
        assertEquals("CLIENT_BASE", c.role)
        assertEquals(MeshPositionMode.CLIENT, c.position.mode)
        assertEquals("LongTurbo", c.channels[0].displayName(0, c.preset))
        assertTrue(c.channels[1].uplink)
        assertTrue("future_key" in c.raw)

        val patch = MeshConfigPatch.channels(c.channels.map { if (it.name == "Team") it.copy(positionPrecision = 16) else it })
        val sent = patch["mesh"]!!.jsonObject["channels"]!!.jsonArray
        assertEquals(2, sent.size)
        assertEquals("default", sent[0].jsonObject["psk"]!!.jsonPrimitive.content)
        assertEquals("base64:AAAA", sent[1].jsonObject["psk"]!!.jsonPrimitive.content)
        assertEquals(16, sent[1].jsonObject["position_precision"]!!.jsonPrimitive.content.toInt())

        val fixed = MeshConfigPatch.positionFixed(12.97, 77.59)["mesh"]!!.jsonObject["position"]!!.jsonObject
        assertEquals("fixed", fixed["mode"]!!.jsonPrimitive.content)
        assertNull(MeshConfig.parse(null))
    }

    @Test fun namesReadAsMeshtasticShowsThem() {
        assertEquals("Long Fast", MeshNames.title("LONG_FAST"))
        assertEquals("Client Base", MeshNames.title("CLIENT_BASE"))
        assertEquals("TAK Tracker", MeshNames.title("TAK_TRACKER"))
        assertEquals("EU 868", MeshNames.title("EU_868"))
        assertEquals("LongFast", MeshNames.channelForPreset("LONG_FAST"))
        assertTrue("CLIENT_BASE" in MeshNames.ROLES)
    }

    @Test fun commandsCarryTheProtocolFields() {
        val send = json(MeshCommands.send(5, "hi", MeshNodeId.BROADCAST, 1, replyId = 9, reaction = true))
        assertEquals("mesh_send", send["cmd"]!!.jsonPrimitive.content)
        assertEquals("broadcast", send["to"]!!.jsonPrimitive.content)
        assertEquals("9", send["reply_id"]!!.jsonPrimitive.content)
        assertEquals("true", send["reaction"]!!.jsonPrimitive.content)
        val dm = json(MeshCommands.send(6, "hi", 0xA1B2C3D4L, 0))
        assertEquals("!a1b2c3d4", dm["to"]!!.jsonPrimitive.content)
        assertFalse("reaction" in dm)
        assertFalse("offset" in json(MeshCommands.nodes(7)))
        assertEquals("16", json(MeshCommands.nodes(7, 0, 16))["limit"]!!.jsonPrimitive.content)
        assertFalse("url" in json(MeshCommands.channelUrl(8)))
    }

    @Test fun precisionHalvesPerBit() {
        assertNull(MeshPrecision.metres(0))
        assertNull(MeshPrecision.metres(32))
        assertEquals(364.0, MeshPrecision.metres(16)!!, 1.0)
        assertEquals(45.5, MeshPrecision.metres(19)!!, 0.5)
        assertEquals(23_312.0, MeshPrecision.metres(10)!!, 1.0)
    }
}
