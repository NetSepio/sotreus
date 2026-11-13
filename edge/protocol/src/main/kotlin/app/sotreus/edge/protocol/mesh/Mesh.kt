package app.sotreus.edge.protocol.mesh

import app.sotreus.edge.protocol.arr
import app.sotreus.edge.protocol.bool
import app.sotreus.edge.protocol.double
import app.sotreus.edge.protocol.int
import app.sotreus.edge.protocol.long
import app.sotreus.edge.protocol.obj
import app.sotreus.edge.protocol.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.pow

// Mesh mode (HANDOFF_V3_MESH.md; protocol §2 `mesh_*`, §3 mesh events, §6 `config.mesh`). The
// shapes follow the Edge's JSON; every reader is tolerant: a missing or differently typed field
// reads as null and unknown fields are ignored. Everything here is NETWORK data: what nodes report.

/** A node number as Meshtastic writes it: `!` and 8 lower-case hex digits. */
object MeshNodeId {
    const val BROADCAST: Long = 0xFFFFFFFFL

    fun format(num: Long): String = "!%08x".format(num and 0xFFFFFFFFL)

    /** `!a1b2c3d4`, or exactly 8 hex digits; null for anything else (including "unknown"). */
    fun parse(s: String?): Long? {
        if (s == null) return null
        val hex = if (s.startsWith("!")) s.substring(1) else s.takeIf { it.length == 8 } ?: return null
        if (hex.isEmpty() || hex.length > 8) return null
        return hex.toLongOrNull(16)
    }
}

/** A sent message's progress (`mesh_tx` and the message `status`). Never moves back (see [advance]). */
enum class MeshDelivery(val wire: String, private val rank: Int) {
    QUEUED("queued", 0),
    SENT("sent", 1),
    RELAYED("relayed", 2),
    NO_RELAY_HEARD("no_relay_heard", 3),
    NO_ACK("no_ack", 3),
    FAILED("failed", 3),
    DELIVERED("delivered", 4);

    val done: Boolean get() = rank >= 3

    /** The state after [next] arrives: a later report never undoes a further one, but a late ACK wins. */
    fun advance(next: MeshDelivery): MeshDelivery = if (next.rank > rank) next else this

    companion object {
        fun of(s: String?): MeshDelivery? = entries.firstOrNull { it.wire == s }
    }
}

/** Text limits from protocol §2: `mesh_send` and `mesh_tak_chat` take 1–200 UTF-8 bytes. */
object MeshText {
    const val MAX_BYTES = 200

    fun bytes(text: String): Int = text.encodeToByteArray().size

    fun fits(text: String): Boolean = bytes(text) in 1..MAX_BYTES

    /** The longest prefix of [text] that fits, never splitting a character. */
    fun clip(text: String): String {
        if (bytes(text) <= MAX_BYTES) return text
        var end = 0
        var used = 0
        while (end < text.length) {
            val step = if (text[end].isHighSurrogate() && end + 1 < text.length && text[end + 1].isLowSurrogate()) 2 else 1
            val n = text.substring(end, end + step).encodeToByteArray().size
            if (used + n > MAX_BYTES) break
            used += n
            end += step
        }
        return text.substring(0, end)
    }
}

/** One text message (`mesh_messages`, the `mesh_rx` event's `msg`). */
data class MeshMessage(
    val packetId: Long,
    val from: Long,
    val to: Long,
    val channel: Int,
    val outgoing: Boolean,
    /** Encrypted with the node's key (direct messages to and from nodes whose key is known). */
    val pki: Boolean,
    /** The Edge's wall clock when it heard or sent the message; null without a valid clock. */
    val unixS: Long?,
    /** Seconds since, measured on the Edge (independent of clocks). */
    val agoS: Long?,
    val replyId: Long?,
    /** The text is one emoji reacting to [replyId]. */
    val reaction: Boolean,
    /** Outgoing only. */
    val delivery: MeshDelivery?,
    /** Incoming only: the last hop's signal, as link quality. */
    val rssi: Int?,
    val snr: Double?,
    val text: String,
    /** The other side's short name, when the Edge knows it. */
    val peerShortName: String?,
) {
    val broadcast: Boolean get() = to == MeshNodeId.BROADCAST

    companion object {
        fun parse(o: JsonObject): MeshMessage? {
            val from = MeshNodeId.parse(o.str("from")) ?: return null
            val to = MeshNodeId.parse(o.str("to")) ?: return null
            val outgoing = o.str("dir") == "sent"
            return MeshMessage(
                packetId = o.long("id") ?: return null,
                from = from, to = to,
                channel = o.int("channel") ?: 0,
                outgoing = outgoing,
                pki = o.bool("pki") == true,
                unixS = o.long("unix_s"),
                agoS = o.long("ago_s"),
                replyId = o.long("reply_id")?.takeIf { it != 0L },
                reaction = o.bool("reaction") == true,
                delivery = if (outgoing) MeshDelivery.of(o.str("status")) else null,
                rssi = if (outgoing) null else o.int("rssi"),
                snr = if (outgoing) null else o.double("snr"),
                text = o.str("text").orEmpty(),
                peerShortName = o.str(if (outgoing) "to_name" else "from_name"),
            )
        }

        fun parseList(resp: JsonObject): List<MeshMessage> = resp.arr("messages").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parse) }
    }
}

/** A position a node reported (NETWORK). [precisionBits] null means the node sent it exactly. */
data class MeshPosition(
    val lat: Double,
    val lon: Double,
    val altM: Int?,
    val precisionBits: Int?,
    val reportedUnixS: Long?,
) {
    /** About how far the true position may be from this one; null when exact. */
    val precisionM: Double? get() = MeshPrecision.metres(precisionBits ?: 32)

    companion object {
        fun parse(o: JsonObject?): MeshPosition? {
            if (o == null) return null
            val lat = o.double("lat") ?: return null
            val lon = o.double("lon") ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            if (lat == 0.0 && lon == 0.0) return null
            return MeshPosition(lat, lon, o.int("alt_m"), o.int("precision_bits")?.takeIf { it in 1..32 }, o.long("reported_unix_s"))
        }
    }
}

data class MeshDeviceMetrics(val batteryLevel: Int?, val voltage: Double?, val channelUtil: Double?, val airUtilTx: Double?, val uptimeS: Long?) {
    /** Meshtastic reports 101 for "powered, no battery". */
    val onPower: Boolean get() = (batteryLevel ?: 0) > 100
}

data class MeshEnvironment(val temperatureC: Double?, val humidityPct: Double?, val pressureHpa: Double?)

data class MeshNeighbor(val num: Long, val snr: Double?)

/** One entry of the Edge's node table (`mesh_nodes`, the `mesh_node` event). */
data class MeshNode(
    val num: Long,
    val longName: String?,
    val shortName: String?,
    val hwModel: Int?,
    /** A Meshtastic role name as the Edge writes it (`CLIENT`, `ROUTER`, `CLIENT_BASE`…); kept as is. */
    val role: String?,
    val hasPublicKey: Boolean,
    val publicKey: String?,
    val favorite: Boolean,
    val ignored: Boolean,
    val lastHeardAgoS: Long?,
    val lastHeardUnixS: Long?,
    val rssi: Int?,
    val snr: Double?,
    val hopsAway: Int?,
    val position: MeshPosition?,
    val device: MeshDeviceMetrics?,
    val environment: MeshEnvironment?,
    val neighbors: List<MeshNeighbor>,
) {
    val id: String get() = MeshNodeId.format(num)

    companion object {
        fun parse(o: JsonObject): MeshNode? {
            val num = o.long("num") ?: MeshNodeId.parse(o.str("id")) ?: return null
            val d = o.obj("device")
            val e = o.obj("environment")
            return MeshNode(
                num = num,
                longName = o.str("long_name")?.takeIf { it.isNotBlank() },
                shortName = o.str("short_name")?.takeIf { it.isNotBlank() },
                hwModel = o.int("hw_model"),
                role = o.str("role"),
                hasPublicKey = o.bool("has_public_key") == true,
                publicKey = o.str("public_key"),
                favorite = o.bool("favorite") == true,
                ignored = o.bool("ignored") == true,
                lastHeardAgoS = o.long("last_heard_ago_s"),
                lastHeardUnixS = o.long("last_heard_unix_s")?.takeIf { it > 0 },
                rssi = o.int("rssi"),
                snr = o.double("snr"),
                hopsAway = o.int("hops_away"),
                position = MeshPosition.parse(o.obj("reported_position")),
                device = d?.let { MeshDeviceMetrics(it.int("battery_level"), it.double("voltage"), it.double("channel_util"), it.double("air_util_tx"), it.long("uptime_s")) },
                environment = e?.let { MeshEnvironment(it.double("temperature_c"), it.double("humidity_pct"), it.double("pressure_hpa")) },
                neighbors = o.arr("neighbors").orEmpty().mapNotNull { el ->
                    val n = el as? JsonObject ?: return@mapNotNull null
                    MeshNodeId.parse(n.str("id"))?.let { MeshNeighbor(it, n.double("snr")) }
                },
            )
        }

        fun parseList(resp: JsonObject): List<MeshNode> = resp.arr("nodes").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parse) }
    }
}

/**
 * One `mesh_nodes` page (`caps.mesh_nodes_paging`). Pages follow the Edge's slot order, so a node
 * first heard between two requests can shift later pages by one: merge by [MeshNode.num].
 */
data class MeshNodePage(val nodes: List<MeshNode>, val total: Int?, val nextOffset: Int?) {
    companion object {
        const val LIMIT = 16

        fun parse(resp: JsonObject) = MeshNodePage(MeshNode.parseList(resp), resp.int("total"), resp.int("next"))
    }
}

/** A waypoint received or sent (`mesh_waypoints`). */
data class MeshWaypoint(
    val id: Long,
    val from: Long?,
    val name: String,
    val description: String?,
    val lat: Double?,
    val lon: Double?,
    val expireUnixS: Long?,
    val icon: Int?,
    val unixS: Long?,
) {
    companion object {
        fun parse(o: JsonObject): MeshWaypoint? = MeshWaypoint(
            id = o.long("id") ?: return null,
            from = MeshNodeId.parse(o.str("from")),
            name = o.str("name").orEmpty(),
            description = o.str("description")?.takeIf { it.isNotBlank() },
            lat = o.double("lat"), lon = o.double("lon"),
            expireUnixS = o.long("expire_unix_s")?.takeIf { it > 0 },
            icon = o.int("icon"),
            unixS = o.long("unix_s"),
        )

        fun parseList(resp: JsonObject): List<MeshWaypoint> = resp.arr("waypoints").orEmpty().mapNotNull { (it as? JsonObject)?.let(::parse) }
    }
}

/**
 * A traceroute result (`mesh_traceroute` event). Routes list the hops between us and [to] in each
 * direction (null = a hop the Edge could not name); SNR values are dB per hop (null = unknown).
 */
data class MeshTraceroute(
    val requestId: Long,
    val to: Long,
    val route: List<Long?>,
    val snrTowards: List<Double?>,
    val routeBack: List<Long?>,
    val snrBack: List<Double?>,
) {
    companion object {
        private fun ids(a: JsonArray?) = a.orEmpty().map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(MeshNodeId::parse) }
        private fun snrs(a: JsonArray?) = a.orEmpty().map { (it as? JsonPrimitive)?.doubleOrNull }

        fun parse(o: JsonObject): MeshTraceroute? = MeshTraceroute(
            requestId = o.long("id") ?: return null,
            to = MeshNodeId.parse(o.str("to")) ?: return null,
            route = ids(o.arr("route")), snrTowards = snrs(o.arr("snr_towards")),
            routeBack = ids(o.arr("route_back")), snrBack = snrs(o.arr("snr_back")),
        )
    }
}

/** An ATAK user heard on the mesh (`mesh_tak`). */
data class MeshTakUser(
    val node: Long?,
    val callsign: String,
    val uid: String?,
    val team: String?,
    val role: String?,
    val battery: Int?,
    val plugin: String?,
    val position: MeshPosition?,
    val agoS: Long?,
) {
    companion object {
        fun parseList(resp: JsonObject): List<MeshTakUser> = resp.arr("tak").orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            MeshTakUser(
                MeshNodeId.parse(o.str("node")), o.str("callsign").orEmpty(), o.str("uid"), o.str("team"), o.str("role"),
                o.int("battery"), o.str("plugin"), MeshPosition.parse(o.obj("reported_position")), o.long("ago_s"),
            )
        }
    }
}

data class MeshChannelState(val index: Int, val name: String, val encrypted: Boolean)

/** `mesh_status` (and `status.mesh`): the radio's state. [raw] keeps everything for diagnostics. */
data class MeshStatus(
    val radioPresent: Boolean,
    val running: Boolean,
    val nodeNum: Long?,
    val role: String?,
    /** Null while the region is UNSET: the radio never transmits. */
    val region: String?,
    val dutyCyclePct: Double?,
    /** The preset in use. */
    val preset: String?,
    /** The preset that was set but is wider than the region allows; [preset] is used instead. */
    val presetTooWide: String?,
    val channelUtilPct: Double?,
    val airUtilTxPct: Double?,
    val politeHold: Boolean,
    val channels: List<MeshChannelState>,
    val nodes: Int,
    val storeForwardRouters: List<Long>,
    val adminOpen: Boolean,
    val adminRequests: Long,
    val adminRefused: Long,
    val rx: Long,
    val tx: Long,
    val relayed: Long,
    val raw: JsonObject,
) {
    companion object {
        fun parse(o: JsonObject): MeshStatus {
            val adm = o.obj("admin")
            val c = o.obj("counters")
            return MeshStatus(
                radioPresent = o.bool("radio_present") != false,
                running = o.bool("running") == true,
                nodeNum = o.long("node_num") ?: MeshNodeId.parse(o.str("node_id")),
                role = o.str("role"),
                region = o.str("region"),
                dutyCyclePct = o.double("duty_cycle_pct"),
                preset = o.str("preset"),
                presetTooWide = o.str("preset_too_wide"),
                channelUtilPct = o.double("channel_util_pct"),
                airUtilTxPct = o.double("air_util_tx_pct"),
                politeHold = o.bool("polite_hold") == true,
                channels = o.arr("channels").orEmpty().mapNotNull { el ->
                    val ch = el as? JsonObject ?: return@mapNotNull null
                    MeshChannelState(ch.int("index") ?: return@mapNotNull null, ch.str("name").orEmpty(), ch.bool("encrypted") == true)
                },
                nodes = o.int("nodes") ?: 0,
                storeForwardRouters = o.arr("store_forward_routers").orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(MeshNodeId::parse) },
                adminOpen = adm?.bool("enabled") != false && adm?.str("mode") != "keys",
                adminRequests = adm?.long("requests") ?: 0,
                adminRefused = adm?.long("refused") ?: 0,
                rx = c?.long("rx") ?: 0,
                tx = c?.long("tx") ?: 0,
                relayed = c?.long("relayed") ?: 0,
                raw = o,
            )
        }
    }
}

/** `regions`: the names this firmware accepts, so new ones need no app change. */
data class MeshRegions(val regions: List<String>, val presets: List<String>) {
    companion object {
        private fun names(a: JsonArray?) = a.orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        fun parse(resp: JsonObject) = MeshRegions(names(resp.arr("regions")), names(resp.arr("presets")))
    }
}

/**
 * Meshtastic's position precision (bits kept of a 32-bit coordinate). 13 bits is about ±2.9 km and
 * each further bit halves it; 32 is exact and 0 sends nothing.
 */
object MeshPrecision {
    const val NONE = 0
    const val EXACT = 32
    /** The firmware's default on the default-key channel. */
    const val DEFAULT_PUBLIC = 13

    /** About how far off a position with [bits] may be; null for exact (32) or none (0). */
    fun metres(bits: Int): Double? = if (bits <= 0 || bits >= 32) null else 2914.0 * 2.0.pow(13 - bits)
}
