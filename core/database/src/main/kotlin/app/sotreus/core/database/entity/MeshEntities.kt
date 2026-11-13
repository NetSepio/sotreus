package app.sotreus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

// Mesh mode (HANDOFF_V3_MESH.md §3.3): what the Edge's mesh brought in. NETWORK data, kept only on
// this phone, under retention and erase. Network clients never take these entities.

/**
 * One text message, received or sent. Keyed by the sender and Meshtastic packet id, so the same
 * message arriving as a record, a `mesh_rx` event and in `mesh_messages` is one row.
 * [conversation] is `ch:<index>` for a channel or `dm:<node number>` for a direct conversation.
 */
@Entity(
    tableName = "mesh_messages",
    primaryKeys = ["device_id", "from_node", "packet_id"],
    indices = [Index("device_id", "conversation", "at_ms"), Index("at_ms")],
)
data class MeshMessageEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "from_node") val fromNode: Long,
    @ColumnInfo(name = "packet_id") val packetId: Long,
    @ColumnInfo(name = "to_node") val toNode: Long,
    val channel: Int,
    val conversation: String,
    val text: String,
    val outgoing: Boolean,
    /** When the Edge heard or sent it (its clock when valid), else when this phone got it. */
    @ColumnInfo(name = "at_ms") val atMs: Long,
    /** False when [atMs] is this phone's receipt time rather than the Edge's. */
    @ColumnInfo(name = "time_from_edge") val timeFromEdge: Boolean,
    val rssi: Int? = null,
    val snr: Float? = null,
    val hops: Int? = null,
    @ColumnInfo(name = "reply_id") val replyId: Long? = null,
    val reaction: Boolean = false,
    val pki: Boolean = false,
    /** Sent messages: `queued`, `sent`, `relayed`, `delivered`, `no_ack`, `no_relay_heard`, `failed`. */
    val delivery: String? = null,
    /** Meshtastic's routing error when [delivery] is `failed`. */
    val reason: Int? = null,
    /** The record's seq when a record brought it. */
    val seq: Long? = null,
    val read: Boolean = false,
)

/** A node the Edge has heard. The Edge keeps up to 64; this phone keeps every one it has seen. */
@Entity(tableName = "mesh_nodes", primaryKeys = ["device_id", "num"], indices = [Index("last_heard_ms")])
data class MeshNodeEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    val num: Long,
    @ColumnInfo(name = "long_name") val longName: String? = null,
    @ColumnInfo(name = "short_name") val shortName: String? = null,
    @ColumnInfo(name = "hw_model") val hwModel: Int? = null,
    val role: String? = null,
    @ColumnInfo(name = "has_key") val hasKey: Boolean = false,
    val favorite: Boolean = false,
    val ignored: Boolean = false,
    @ColumnInfo(name = "last_heard_ms") val lastHeardMs: Long? = null,
    val rssi: Int? = null,
    val snr: Float? = null,
    @ColumnInfo(name = "hops_away") val hopsAway: Int? = null,
    val battery: Int? = null,
    val voltage: Float? = null,
    @ColumnInfo(name = "channel_util") val channelUtil: Float? = null,
    @ColumnInfo(name = "air_util") val airUtil: Float? = null,
    @ColumnInfo(name = "uptime_s") val uptimeS: Long? = null,
    @ColumnInfo(name = "temperature_c") val temperatureC: Float? = null,
    @ColumnInfo(name = "humidity_pct") val humidityPct: Float? = null,
    @ColumnInfo(name = "pressure_hpa") val pressureHpa: Float? = null,
    @ColumnInfo(name = "telemetry_ms") val telemetryMs: Long? = null,
    /** The node's reported position (NETWORK). */
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "alt_m") val altM: Int? = null,
    @ColumnInfo(name = "precision_bits") val precisionBits: Int? = null,
    @ColumnInfo(name = "position_ms") val positionMs: Long? = null,
    /** `num:snr` pairs, comma separated. */
    val neighbors: String? = null,
)

/** A waypoint received or sent on the mesh. */
@Entity(tableName = "mesh_waypoints", primaryKeys = ["device_id", "id"])
data class MeshWaypointEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    val id: Long,
    @ColumnInfo(name = "from_node") val fromNode: Long? = null,
    val name: String,
    val description: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "expire_ms") val expireMs: Long? = null,
    val icon: Int? = null,
    @ColumnInfo(name = "at_ms") val atMs: Long,
)

/** Per-conversation settings. */
@Entity(tableName = "mesh_conversations", primaryKeys = ["device_id", "conversation"])
data class MeshConversationEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    val conversation: String,
    val muted: Boolean = false,
)
