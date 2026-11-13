package app.sotreus.edge.fake

import app.sotreus.edge.protocol.EdgeBodies
import app.sotreus.edge.protocol.EdgeRecordType
import app.sotreus.edge.protocol.bool
import app.sotreus.edge.protocol.double
import app.sotreus.edge.protocol.int
import app.sotreus.edge.protocol.long
import app.sotreus.edge.protocol.mesh.MeshNodeId
import app.sotreus.edge.protocol.mesh.MeshText
import app.sotreus.edge.protocol.obj
import app.sotreus.edge.protocol.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The fake Edge's Mesh mode (protocol §2 `mesh_*`): a node table, the last 32 messages, waypoints
 * and `config.mesh`, answered the way the firmware answers. Tests drive the other side with
 * [receive], [deliver], [hear] and [route]. Never presented as real data.
 */
class FakeMesh internal constructor(private val edge: FakeEdge) {
    sealed interface Reply {
        data class Ok(val fields: List<Pair<String, Any?>> = emptyList()) : Reply
        data class Err(val message: String) : Reply
    }

    var nodeNum: Long = 0x5AC1E0D2L
    var config: JsonObject = FakeEdge.obj(
        "region" to "IN", "preset" to "LONG_FAST", "hop_limit" to 3, "rebroadcast" to "all", "tx_power" to 0, "channel_num" to 0,
        "node_id" to MeshNodeId.format(0x5AC1E0D2L), "long_name" to "Sotreus Edge 96FF", "short_name" to "SE2B", "nodeinfo_interval_s" to 10800,
        "channels" to JsonArray(listOf(
            FakeEdge.obj("name" to "", "psk" to "default", "position_precision" to 13, "uplink" to false, "downlink" to false),
            FakeEdge.obj("name" to "Team", "psk" to "base64:q83vASNFZ4mrze8BI0VniQ==", "position_precision" to 19, "uplink" to false, "downlink" to false),
        )),
        "role" to "CLIENT", "licensed" to false,
        "position" to FakeEdge.obj("mode" to "off", "interval_s" to 900),
        "telemetry_interval_s" to 1800,
        "canned" to JsonArray(listOf("OK", "On my way", "Need assistance", "All fine here").map(::JsonPrimitive)),
        "mqtt" to FakeEdge.obj("enabled" to false),
    )

    /** In table order, by node number. */
    val nodes = LinkedHashMap<Long, JsonObject>()
    /** Oldest first; the Edge keeps the last [MAX_MESSAGES]. */
    val messages = ArrayList<JsonObject>()
    val waypoints = ArrayList<JsonObject>()
    /** Every `mesh_send` the phone asked for. */
    val sends = ArrayList<JsonObject>()
    /** Every position the phone handed over (`mesh_set_position`). */
    val positions = ArrayList<Pair<Double, Double>>()
    var storeForwardRouter: Long? = null
    private var nextPacket = 0x1000L

    val running: Boolean get() = edge.mode == "mesh" && config.str("region") != "UNSET"

    fun mergeConfig(patch: JsonObject) {
        // One level deep, like the firmware: nested objects (position, mqtt…) merge their keys.
        config = JsonObject(config + patch.mapValues { (k, v) ->
            val cur = config[k]
            if (v is JsonObject && cur is JsonObject) JsonObject(cur + v) else v
        })
    }

    /** A text from another node arrives: `mesh_rx`, and a type 4 record. */
    fun receive(from: Long, text: String, to: Long = MeshNodeId.BROADCAST, channel: Int = 0, rssi: Int = -98, snr: Double = 6.5,
                replyId: Long? = null, reaction: Boolean = false, pki: Boolean = false): Long {
        val id = nextPacket++
        val m = msg(id, from, to, channel, outgoing = false, pki = pki, replyId = replyId, reaction = reaction, text = text, rssi = rssi, snr = snr)
        keep(m)
        edge.emit(FakeEdge.obj("ev" to "mesh_rx", "msg" to m))
        record(from, to, id, channel, rssi, snr, sent = false, text = text)
        return id
    }

    /** A sent message moves on (`mesh_tx`). */
    fun deliver(packetId: Long, status: String, reason: Int? = null) {
        val i = messages.indexOfFirst { it.long("id") == packetId && it.str("dir") == "sent" }
        if (i >= 0) messages[i] = JsonObject(messages[i] + ("status" to JsonPrimitive(status)))
        edge.emit(FakeEdge.obj("ev" to "mesh_tx", "id" to packetId, "status" to status, "reason" to reason))
    }

    /** Node info, position or telemetry arrives (`mesh_node`). */
    fun hear(node: JsonObject) {
        val num = node.long("num") ?: MeshNodeId.parse(node.str("id")) ?: return
        val full = JsonObject(node + ("num" to JsonPrimitive(num)) + ("id" to JsonPrimitive(MeshNodeId.format(num))))
        nodes[num] = full
        edge.emit(FakeEdge.obj("ev" to "mesh_node", "node" to full))
    }

    fun route(requestId: Long, to: Long, route: List<Long>, snrTowards: List<Double?>, routeBack: List<Long>, snrBack: List<Double?>) =
        edge.emit(FakeEdge.obj(
            "ev" to "mesh_traceroute", "id" to requestId, "to" to MeshNodeId.format(to),
            "route" to JsonArray(route.map { JsonPrimitive(MeshNodeId.format(it)) }), "snr_towards" to JsonArray(snrTowards.map { JsonPrimitive(it) }),
            "route_back" to JsonArray(routeBack.map { JsonPrimitive(MeshNodeId.format(it)) }), "snr_back" to JsonArray(snrBack.map { JsonPrimitive(it) }),
        ))

    internal fun handle(req: JsonObject): Reply? = when (req.str("cmd")) {
        "mesh_send" -> needsRadio {
            val text = req.str("text").orEmpty()
            if (!MeshText.fits(text)) return@needsRadio Reply.Err("text must be 1-200 bytes")
            val to = req.str("to")?.let { if (it == "broadcast") MeshNodeId.BROADCAST else MeshNodeId.parse(it) } ?: return@needsRadio Reply.Err("bad destination")
            val channel = req.int("channel") ?: 0
            val id = nextPacket++
            sends += req
            keep(msg(id, nodeNum, to, channel, outgoing = true, pki = to != MeshNodeId.BROADCAST && nodes[to]?.bool("has_public_key") == true,
                replyId = req.long("reply_id"), reaction = req.bool("reaction") == true, text = text, status = "queued"))
            record(nodeNum, to, id, channel, 0, 0.0, sent = true, text = text)
            Reply.Ok(listOf("packet_id" to id))
        }
        "mesh_canned" -> needsRadio {
            val list = config["canned"] as? JsonArray
            val i = req.int("index") ?: 0
            val text = (list?.getOrNull(i) as? JsonPrimitive)?.content ?: return@needsRadio Reply.Err("no such canned message")
            val id = nextPacket++
            keep(msg(id, nodeNum, MeshNodeId.BROADCAST, 0, outgoing = true, pki = false, replyId = null, reaction = false, text = text, status = "queued"))
            Reply.Ok(listOf("packet_id" to id))
        }
        "mesh_traceroute" -> needsRadio { Reply.Ok(listOf("packet_id" to nextPacket++)) }
        "mesh_request_position", "mesh_send_nodeinfo" -> needsRadio { Reply.Ok() }
        "mesh_store_forward" -> needsRadio { if (storeForwardRouter == null) Reply.Err("no store & forward router heard yet") else Reply.Ok() }
        "mesh_set_position" -> {
            val lat = req.double("lat")
            val lon = req.double("lon")
            if (lat == null || lon == null) Reply.Err("lat and lon required") else { positions += lat to lon; Reply.Ok() }
        }
        "mesh_waypoint" -> needsRadio {
            val lat = req.double("lat") ?: return@needsRadio Reply.Err("lat and lon required")
            val lon = req.double("lon") ?: return@needsRadio Reply.Err("lat and lon required")
            val target = if (edge.caps["mesh_target_fields"] == true && "waypoint_id" in req) req.long("waypoint_id") else req.long("id")
            val id = target?.takeIf { it != 0L } ?: nextPacket++
            waypoints.removeAll { it.long("id") == id }
            waypoints += FakeEdge.obj("id" to id, "from" to MeshNodeId.format(nodeNum), "name" to (req.str("name") ?: "Waypoint"), "lat" to lat, "lon" to lon,
                "expire_unix_s" to req.long("expire_unix_s"), "provenance" to "NETWORK")
            Reply.Ok(listOf("waypoint_id" to id))
        }
        "mesh_waypoints" -> Reply.Ok(listOf("waypoints" to JsonArray(waypoints)))
        "mesh_node" -> {
            // `node` names the node when the Edge has `mesh_target_fields`; otherwise `id` does (number or "!hex").
            val target = if (edge.caps["mesh_target_fields"] == true && "node" in req) "node" else "id"
            val num = req.long(target) ?: MeshNodeId.parse(req.str(target))
            val cur = num?.let { nodes[it] }
            when {
                num == null -> Reply.Err("bad node id")
                req.bool("remove") == true -> when {
                    !running -> Reply.Err("mesh is not running")
                    num == nodeNum -> Reply.Err("this is the Edge's own node")
                    cur == null -> Reply.Err("node not found")
                    else -> { nodes.remove(num); Reply.Ok() }
                }
                else -> {
                if (cur != null) {
                    var n: JsonObject = cur
                    req.bool("favorite")?.let { n = JsonObject(n + ("favorite" to JsonPrimitive(it))) }
                    req.bool("ignored")?.let { n = JsonObject(n + ("ignored" to JsonPrimitive(it))) }
                    nodes[num] = n
                }
                Reply.Ok()
                }
            }
        }
        "mesh_channel_url" -> {
            req.str("url")?.let { url -> if (!url.startsWith("https://meshtastic.org/e/#")) return Reply.Err("not a Meshtastic channel link") }
            Reply.Ok(listOf("url" to "https://meshtastic.org/e/#CgcSAQEaAgAB"))
        }
        "mesh_tak" -> Reply.Ok(listOf("tak" to JsonArray(emptyList())))
        "mesh_tak_chat", "mesh_tak_position" -> needsRadio { Reply.Ok() }
        "mesh_nodes" -> {
            val all = nodes.values.toList()
            val paging = edge.caps["mesh_nodes_paging"] == true && ("offset" in req || "limit" in req)
            if (!paging) Reply.Ok(listOf("nodes" to JsonArray(all))) else {
                val offset = (req.int("offset") ?: 0).coerceAtLeast(0)
                val limit = (req.int("limit") ?: 64).let { if (it in 1..64) it else 64 }
                val page = all.drop(offset).take(limit)
                val next = if (offset + limit < all.size) offset + limit else null
                Reply.Ok(listOf("nodes" to JsonArray(page), "total" to all.size, "next" to next))
            }
        }
        "mesh_messages" -> Reply.Ok(listOf("messages" to JsonArray(messages)))
        "mesh_status" -> Reply.Ok(listOf("mesh" to status()))
        "mesh_new_identity" -> {
            nodeNum = (nodeNum * 31 + 7) and 0xFFFFFFFFL
            config = JsonObject(config + ("node_id" to JsonPrimitive(MeshNodeId.format(nodeNum))))
            Reply.Ok(listOf("node_id" to MeshNodeId.format(nodeNum)))
        }
        "regions" -> Reply.Ok(listOf(
            "regions" to JsonArray(REGIONS.map(::JsonPrimitive)),
            "presets" to JsonArray(PRESETS.map(::JsonPrimitive)),
        ))
        else -> null
    }

    fun status(): JsonObject = FakeEdge.obj(
        "radio_present" to true, "running" to running, "node_id" to MeshNodeId.format(nodeNum), "node_num" to nodeNum,
        "role" to (config.str("role") ?: "CLIENT"), "region" to config.str("region")?.takeIf { it != "UNSET" },
        "preset" to config.str("preset"), "channel_util_pct" to 4.1, "air_util_tx_pct" to 0.8, "polite_hold" to false,
        "nodes" to nodes.size,
        "store_forward_routers" to JsonArray(listOfNotNull(storeForwardRouter).map { JsonPrimitive(MeshNodeId.format(it)) }),
        "admin" to FakeEdge.obj("enabled" to true, "mode" to "open", "keys" to 0, "requests" to 0, "refused" to 0),
        "counters" to FakeEdge.obj("rx" to messages.count { it.str("dir") == "received" }, "tx" to messages.count { it.str("dir") == "sent" }, "relayed" to 0),
    )

    private fun needsRadio(block: () -> Reply): Reply = if (!running) Reply.Err("mesh is not running or the LoRa region is not set") else block()

    private fun keep(m: JsonObject) {
        messages += m
        while (messages.size > MAX_MESSAGES) messages.removeAt(0)
    }

    private fun msg(
        id: Long, from: Long, to: Long, channel: Int, outgoing: Boolean, pki: Boolean, replyId: Long?, reaction: Boolean, text: String,
        status: String? = null, rssi: Int? = null, snr: Double? = null,
    ): JsonObject {
        val peer = nodes[if (outgoing) to else from]?.str("short_name")
        return FakeEdge.obj(
            "id" to id, "from" to MeshNodeId.format(from), "to" to MeshNodeId.format(to), "channel" to channel,
            "dir" to (if (outgoing) "sent" else "received"), "provenance" to "NETWORK", "pki" to pki, "ago_s" to 0,
            "unix_s" to edge.nowMs() / 1000, "reply_id" to replyId, "reaction" to (if (reaction) true else null),
            "status" to status, "rssi" to rssi, "snr" to snr, "text" to text,
            (if (outgoing) "to_name" else "from_name") to peer,
        ).let { o -> JsonObject(o.filterValues { it !is kotlinx.serialization.json.JsonNull }) }
    }

    private fun record(from: Long, to: Long, id: Long, channel: Int, rssi: Int, snr: Double, sent: Boolean, text: String) {
        val now = edge.nowMs()
        edge.addRecord(EdgeRecordType.MESH_TEXT, EdgeBodies.meshText(from, to, id, channel, 3, 3, rssi, snr.toFloat(), sent, text), now, now)
    }

    companion object {
        const val MAX_MESSAGES = 32
        val REGIONS = listOf(
            "US", "EU_433", "EU_868", "CN", "JP", "ANZ", "KR", "TW", "RU", "IN", "NZ_865", "TH", "UA_433", "UA_868", "MY_433", "MY_919",
            "SG_923", "PH_433", "PH_868", "PH_915", "ANZ_433", "KZ_433", "KZ_863", "NP_865", "BR_902",
        )
        val PRESETS = listOf("LONG_FAST", "LONG_SLOW", "LONG_MODERATE", "LONG_TURBO", "MEDIUM_SLOW", "MEDIUM_FAST", "SHORT_SLOW", "SHORT_FAST", "SHORT_TURBO")
    }
}
