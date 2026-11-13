package app.sotreus.edge.link

import app.sotreus.edge.protocol.long
import app.sotreus.edge.protocol.mesh.MeshCommands
import app.sotreus.edge.protocol.mesh.MeshMessage
import app.sotreus.edge.protocol.mesh.MeshNode
import app.sotreus.edge.protocol.mesh.MeshNodePage
import app.sotreus.edge.protocol.mesh.MeshRegions
import app.sotreus.edge.protocol.mesh.MeshStatus
import app.sotreus.edge.protocol.mesh.MeshTakUser
import app.sotreus.edge.protocol.mesh.MeshText
import app.sotreus.edge.protocol.mesh.MeshWaypoint
import app.sotreus.edge.protocol.obj
import app.sotreus.edge.protocol.str

// Typed mesh calls (protocol §2) over any [EdgeLink]. A refused command throws
// [EdgeRequestException] with the Edge's own error text ("mesh is not running…").

/** Sends text; returns the packet id that `mesh_tx` events report on. */
suspend fun EdgeLink.meshSend(text: String, to: Long, channel: Int, wantAck: Boolean = true, replyId: Long? = null, reaction: Boolean = false): Long {
    if (!MeshText.fits(text)) throw EdgeRequestException("text must be 1-${MeshText.MAX_BYTES} bytes")
    return ok { MeshCommands.send(it, text, to, channel, wantAck, replyId, reaction) }.body.long("packet_id") ?: 0
}

suspend fun EdgeLink.meshCanned(index: Int): Long = ok { MeshCommands.canned(it, index) }.body.long("packet_id") ?: 0

suspend fun EdgeLink.meshTraceroute(to: Long, channel: Int = 0): Long = ok { MeshCommands.traceroute(it, to, channel) }.body.long("packet_id") ?: 0

suspend fun EdgeLink.meshRequestPosition(to: Long, channel: Int = 0) {
    ok { MeshCommands.requestPosition(it, to, channel) }
}

/** A new waypoint, or with [id] and [targetFields] an update (older firmware cannot take one safely). */
suspend fun EdgeLink.meshWaypoint(lat: Double, lon: Double, name: String, description: String? = null, expireUnixS: Long? = null, icon: Int? = null, id: Long? = null, channel: Int = 0, targetFields: Boolean = false): Long {
    if (id != null && !targetFields) throw EdgeRequestException("updating a waypoint needs newer Edge firmware")
    return ok { MeshCommands.waypoint(it, lat, lon, name, description, expireUnixS, icon, id, channel, targetFields) }.body.long("waypoint_id") ?: 0
}

suspend fun EdgeLink.meshWaypoints(): List<MeshWaypoint> = MeshWaypoint.parseList(ok { MeshCommands.waypoints(it) }.body)

suspend fun EdgeLink.meshStoreForward(router: Long? = null, channel: Int = 0) {
    ok { MeshCommands.storeForward(it, router, channel) }
}

suspend fun EdgeLink.meshSendNodeInfo() {
    ok { MeshCommands.sendNodeInfo(it) }
}

/** This phone's location for `client` position mode. Sent only in Mesh mode (HANDOFF_V3_MESH.md §3.1). */
suspend fun EdgeLink.meshSetPosition(lat: Double, lon: Double, alt: Int? = null) {
    ok { MeshCommands.setPosition(it, lat, lon, alt) }
}

/**
 * `mesh_node`. With [targetFields] (`caps.mesh_target_fields`) the node goes in `node` and the reply
 * is matched as usual; older firmware reads the node from `id`, so the reply is matched on it.
 */
private suspend fun EdgeLink.nodeCommand(num: Long, targetFields: Boolean, favorite: Boolean? = null, ignored: Boolean? = null, remove: Boolean = false) {
    if (targetFields) {
        ok { MeshCommands.node(it, num, favorite, ignored, remove) }
        return
    }
    val r = requestEcho(num and 0xFFFFFFFFL, MeshCommands.nodeInId(num, favorite, ignored, remove))
    if (!r.ok) throw EdgeRequestException(r.error ?: "refused")
}

suspend fun EdgeLink.meshNode(num: Long, favorite: Boolean? = null, ignored: Boolean? = null, targetFields: Boolean = false) =
    nodeCommand(num, targetFields, favorite, ignored)

/** Drops a node from the Edge's table (`caps.mesh_node_remove`). No event follows: read `mesh_nodes` again. */
suspend fun EdgeLink.meshRemoveNode(num: Long, targetFields: Boolean = false) = nodeCommand(num, targetFields, remove = true)

/** The channels as a Meshtastic link; with [url], applies it first ([replace] swaps all, else adds). */
suspend fun EdgeLink.meshChannelUrl(url: String? = null, replace: Boolean = true): String =
    ok { MeshCommands.channelUrl(it, url, replace) }.body.str("url") ?: throw EdgeRequestException("mesh_channel_url without url")

suspend fun EdgeLink.meshTak(): List<MeshTakUser> = MeshTakUser.parseList(ok { MeshCommands.tak(it) }.body)

suspend fun EdgeLink.meshTakChat(text: String, channel: Int = 0) {
    if (!MeshText.fits(text)) throw EdgeRequestException("text must be 1-${MeshText.MAX_BYTES} bytes")
    ok { MeshCommands.takChat(it, text, channel) }
}

suspend fun EdgeLink.meshTakPosition(channel: Int = 0) {
    ok { MeshCommands.takPosition(it, channel) }
}

/**
 * The whole node table. With [paged] (`caps.mesh_nodes_paging`) it reads page by page, so each
 * response stays small on a 27-byte link; pages can shift by one, so nodes are merged by number.
 */
suspend fun EdgeLink.meshNodes(paged: Boolean): List<MeshNode> {
    if (!paged) return MeshNode.parseList(ok { MeshCommands.nodes(it) }.body)
    val byNum = LinkedHashMap<Long, MeshNode>()
    var offset = 0
    repeat(MAX_NODE_PAGES) {
        val page = MeshNodePage.parse(ok { MeshCommands.nodes(it, offset, MeshNodePage.LIMIT) }.body)
        page.nodes.forEach { byNum[it.num] = it }
        offset = page.nextOffset ?: return byNum.values.toList()
    }
    return byNum.values.toList()
}

private const val MAX_NODE_PAGES = 8

suspend fun EdgeLink.meshMessages(): List<MeshMessage> = MeshMessage.parseList(ok { MeshCommands.messages(it) }.body)

suspend fun EdgeLink.meshStatus(): MeshStatus = ok { MeshCommands.status(it) }.body.let { MeshStatus.parse(it.obj("mesh") ?: it) }

/** A new node number and key pair; returns the new `!id`. */
suspend fun EdgeLink.meshNewIdentity(): String = ok { MeshCommands.newIdentity(it) }.body.str("node_id").orEmpty()

suspend fun EdgeLink.meshRegions(): MeshRegions = MeshRegions.parse(ok { MeshCommands.regions(it) }.body)
