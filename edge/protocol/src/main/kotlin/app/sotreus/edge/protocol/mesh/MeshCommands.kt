package app.sotreus.edge.protocol.mesh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Mesh command lines (protocol §2). Each carries an `id` the response echoes. */
object MeshCommands {
    private fun cmd(name: String, id: Long, extra: Map<String, JsonElement> = emptyMap()): String =
        JsonObject(buildMap { put("cmd", JsonPrimitive(name)); put("id", JsonPrimitive(id)); putAll(extra) }).toString()

    private fun target(num: Long): JsonPrimitive = JsonPrimitive(if (num == MeshNodeId.BROADCAST) "broadcast" else MeshNodeId.format(num))

    /** [text] must be 1–200 UTF-8 bytes; [to] is a node number or [MeshNodeId.BROADCAST] for the channel. */
    fun send(id: Long, text: String, to: Long, channel: Int, wantAck: Boolean = true, replyId: Long? = null, reaction: Boolean = false) =
        cmd("mesh_send", id, buildMap {
            put("text", JsonPrimitive(text))
            put("to", target(to))
            put("channel", JsonPrimitive(channel))
            put("want_ack", JsonPrimitive(wantAck))
            replyId?.let { put("reply_id", JsonPrimitive(it)) }
            if (reaction) put("reaction", JsonPrimitive(true))
        })

    fun canned(id: Long, index: Int) = cmd("mesh_canned", id, mapOf("index" to JsonPrimitive(index)))
    fun traceroute(id: Long, to: Long, channel: Int = 0) = cmd("mesh_traceroute", id, mapOf("to" to target(to), "channel" to JsonPrimitive(channel)))
    fun requestPosition(id: Long, to: Long, channel: Int = 0) = cmd("mesh_request_position", id, mapOf("to" to target(to), "channel" to JsonPrimitive(channel)))

    /**
     * With [targetFields] (`caps.mesh_target_fields`) the waypoint goes in `waypoint_id`, 0 for a new
     * one. Without it only new waypoints are sent: there `id` would name the waypoint, not the request.
     */
    fun waypoint(id: Long, lat: Double, lon: Double, name: String, description: String?, expireUnixS: Long?, icon: Int?, waypointId: Long?, channel: Int = 0, targetFields: Boolean = false) =
        cmd("mesh_waypoint", id, buildMap {
            put("lat", JsonPrimitive(lat))
            put("lon", JsonPrimitive(lon))
            put("name", JsonPrimitive(name))
            description?.let { put("description", JsonPrimitive(it)) }
            expireUnixS?.let { put("expire_unix_s", JsonPrimitive(it)) }
            icon?.let { put("icon", JsonPrimitive(it)) }
            if (targetFields) put("waypoint_id", JsonPrimitive(waypointId ?: 0L))
            put("channel", JsonPrimitive(channel))
        })

    fun waypoints(id: Long) = cmd("mesh_waypoints", id)

    /** Asks a store & forward router for missed channel messages; any router heard when [router] is null. */
    fun storeForward(id: Long, router: Long? = null, channel: Int = 0) =
        cmd("mesh_store_forward", id, buildMap { router?.let { put("router", target(it)) }; put("channel", JsonPrimitive(channel)) })

    fun sendNodeInfo(id: Long) = cmd("mesh_send_nodeinfo", id)

    /** The position used in `client` mode: this phone's location, handed to the Edge. */
    fun setPosition(id: Long, lat: Double, lon: Double, alt: Int? = null) =
        cmd("mesh_set_position", id, buildMap { put("lat", JsonPrimitive(lat)); put("lon", JsonPrimitive(lon)); alt?.let { put("alt", JsonPrimitive(it)) } })

    /**
     * `mesh_node` names its node in `id`, the field the Edge echoes as the request id. So the line
     * carries the node number there, and the caller matches the response on it
     * ([app.sotreus.edge.link.EdgeLink.requestEcho]). [remove] drops the node from the Edge's table
     * (`caps.mesh_node_remove`); it comes back when next heard.
     */
    /** With `caps.mesh_target_fields`: the node goes in `node`, and `id` is the request id as everywhere. */
    fun node(id: Long, num: Long, favorite: Boolean? = null, ignored: Boolean? = null, remove: Boolean = false): String =
        cmd("mesh_node", id, buildMap {
            put("node", JsonPrimitive(MeshNodeId.format(num)))
            favorite?.let { put("favorite", JsonPrimitive(it)) }
            ignored?.let { put("ignored", JsonPrimitive(it)) }
            if (remove) put("remove", JsonPrimitive(true))
        })

    /** Older firmware: the node in `id` (see above). */
    fun nodeInId(num: Long, favorite: Boolean? = null, ignored: Boolean? = null, remove: Boolean = false): String =
        JsonObject(buildMap {
            put("cmd", JsonPrimitive("mesh_node"))
            put("id", JsonPrimitive(num and 0xFFFFFFFFL))
            favorite?.let { put("favorite", JsonPrimitive(it)) }
            ignored?.let { put("ignored", JsonPrimitive(it)) }
            if (remove) put("remove", JsonPrimitive(true))
        }).toString()

    /** Without [url], reads the channels as a Meshtastic link; with it, applies the link first. */
    fun channelUrl(id: Long, url: String? = null, replace: Boolean = true) =
        cmd("mesh_channel_url", id, buildMap { url?.let { put("url", JsonPrimitive(it)); put("replace", JsonPrimitive(replace)) } })

    fun tak(id: Long) = cmd("mesh_tak", id)
    fun takChat(id: Long, text: String, channel: Int = 0) = cmd("mesh_tak_chat", id, mapOf("text" to JsonPrimitive(text), "channel" to JsonPrimitive(channel)))
    fun takPosition(id: Long, channel: Int = 0) = cmd("mesh_tak_position", id, mapOf("channel" to JsonPrimitive(channel)))

    /** [offset]/[limit] only when `caps.mesh_nodes_paging`; without them the whole table comes back. */
    fun nodes(id: Long, offset: Int? = null, limit: Int? = null) =
        cmd("mesh_nodes", id, buildMap { offset?.let { put("offset", JsonPrimitive(it)) }; limit?.let { put("limit", JsonPrimitive(it)) } })

    fun messages(id: Long) = cmd("mesh_messages", id)
    fun status(id: Long) = cmd("mesh_status", id)
    fun newIdentity(id: Long) = cmd("mesh_new_identity", id)
    fun regions(id: Long) = cmd("regions", id)
}
