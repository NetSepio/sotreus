package app.sotreus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.sotreus.core.database.entity.MeshConversationEntity
import app.sotreus.core.database.entity.MeshMessageEntity
import app.sotreus.core.database.entity.MeshNodeEntity
import app.sotreus.core.database.entity.MeshWaypointEntity
import kotlinx.coroutines.flow.Flow

/** One conversation's newest message and unread count, for the Messages list (M1). */
data class MeshConversationRow(
    val conversation: String,
    val channel: Int,
    val lastText: String,
    val lastOutgoing: Boolean,
    val lastFrom: Long,
    val lastToNode: Long,
    val lastAtMs: Long,
    val lastDelivery: String?,
    val lastPki: Boolean,
    val unread: Int,
)

/** Each conversation's newest message (reactions aside) and its unread count, newest first. */
internal const val MESH_CONVERSATIONS_SQL = """SELECT m.conversation AS conversation, m.channel AS channel, m.text AS lastText, m.outgoing AS lastOutgoing,
                  m.from_node AS lastFrom, m.to_node AS lastToNode, m.at_ms AS lastAtMs, m.delivery AS lastDelivery, m.pki AS lastPki,
                  (SELECT COUNT(*) FROM mesh_messages u WHERE u.device_id = m.device_id AND u.conversation = m.conversation
                      AND u.read = 0 AND u.outgoing = 0) AS unread
           FROM mesh_messages m
           WHERE m.device_id = :deviceId AND m.reaction = 0 AND m.at_ms = (
               SELECT MAX(x.at_ms) FROM mesh_messages x WHERE x.device_id = m.device_id AND x.conversation = m.conversation AND x.reaction = 0)
           GROUP BY m.conversation
           ORDER BY m.at_ms DESC"""

@Dao
interface MeshDao {
    // ---- Messages --------------------------------------------------------------------------------

    /** New rows only; returns -1 for a message already stored (same sender and packet id). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessages(rows: List<MeshMessageEntity>): List<Long>

    @Query("SELECT * FROM mesh_messages WHERE device_id = :deviceId AND from_node = :from AND packet_id = :packetId")
    suspend fun message(deviceId: String, from: Long, packetId: Long): MeshMessageEntity?

    /** A record or `mesh_messages` row fills what an event left out (its seq, the Edge's time, hops). */
    @Query(
        """UPDATE mesh_messages SET seq = COALESCE(seq, :seq), hops = COALESCE(hops, :hops),
           at_ms = CASE WHEN time_from_edge THEN at_ms ELSE :atMs END,
           time_from_edge = time_from_edge OR :timeFromEdge
           WHERE device_id = :deviceId AND from_node = :from AND packet_id = :packetId""",
    )
    suspend fun fillMessage(deviceId: String, from: Long, packetId: Long, seq: Long?, hops: Int?, atMs: Long, timeFromEdge: Boolean)

    @Query("SELECT delivery FROM mesh_messages WHERE device_id = :deviceId AND from_node = :self AND packet_id = :packetId")
    suspend fun delivery(deviceId: String, self: Long, packetId: Long): String?

    @Query("UPDATE mesh_messages SET delivery = :delivery, reason = :reason WHERE device_id = :deviceId AND outgoing = 1 AND packet_id = :packetId")
    suspend fun setDelivery(deviceId: String, packetId: Long, delivery: String, reason: Int?)

    @Query("SELECT * FROM mesh_messages WHERE device_id = :deviceId AND conversation = :conversation ORDER BY at_ms, packet_id")
    fun observeConversation(deviceId: String, conversation: String): Flow<List<MeshMessageEntity>>

    @Query(MESH_CONVERSATIONS_SQL)
    fun observeConversations(deviceId: String): Flow<List<MeshConversationRow>>

    @Query("UPDATE mesh_messages SET read = 1 WHERE device_id = :deviceId AND conversation = :conversation AND read = 0")
    suspend fun markRead(deviceId: String, conversation: String)

    @Query("SELECT COUNT(*) FROM mesh_messages WHERE device_id = :deviceId AND read = 0 AND outgoing = 0")
    fun observeUnread(deviceId: String): Flow<Int>

    // ---- Nodes -----------------------------------------------------------------------------------

    @Upsert
    suspend fun upsertNodes(rows: List<MeshNodeEntity>)

    @Query("SELECT * FROM mesh_nodes WHERE device_id = :deviceId AND num = :num")
    suspend fun node(deviceId: String, num: Long): MeshNodeEntity?

    @Query("SELECT * FROM mesh_nodes WHERE device_id = :deviceId AND num IN (:nums)")
    suspend fun nodes(deviceId: String, nums: List<Long>): List<MeshNodeEntity>

    @Query("SELECT * FROM mesh_nodes WHERE device_id = :deviceId ORDER BY favorite DESC, last_heard_ms DESC")
    fun observeNodes(deviceId: String): Flow<List<MeshNodeEntity>>

    @Query("SELECT * FROM mesh_nodes WHERE device_id = :deviceId AND num = :num")
    fun observeNode(deviceId: String, num: Long): Flow<MeshNodeEntity?>

    @Query("UPDATE mesh_nodes SET favorite = :favorite WHERE device_id = :deviceId AND num = :num")
    suspend fun setFavorite(deviceId: String, num: Long, favorite: Boolean)

    @Query("UPDATE mesh_nodes SET ignored = :ignored WHERE device_id = :deviceId AND num = :num")
    suspend fun setIgnored(deviceId: String, num: Long, ignored: Boolean)

    /** A node removed from the Edge's table goes here too; its messages stay. */
    @Query("DELETE FROM mesh_nodes WHERE device_id = :deviceId AND num = :num")
    suspend fun deleteNode(deviceId: String, num: Long)

    // ---- Waypoints -------------------------------------------------------------------------------

    @Upsert
    suspend fun upsertWaypoints(rows: List<MeshWaypointEntity>)

    @Query("SELECT * FROM mesh_waypoints WHERE device_id = :deviceId AND (expire_ms IS NULL OR expire_ms > :nowMs) ORDER BY at_ms DESC")
    fun observeWaypoints(deviceId: String, nowMs: Long): Flow<List<MeshWaypointEntity>>

    // ---- Conversations ---------------------------------------------------------------------------

    @Upsert
    suspend fun upsertConversation(row: MeshConversationEntity)

    @Query("SELECT conversation FROM mesh_conversations WHERE device_id = :deviceId AND muted = 1")
    fun observeMuted(deviceId: String): Flow<List<String>>

    @Query("SELECT muted FROM mesh_conversations WHERE device_id = :deviceId AND conversation = :conversation")
    suspend fun muted(deviceId: String, conversation: String): Boolean?

    // ---- Retention and erase ---------------------------------------------------------------------

    @Query("DELETE FROM mesh_messages WHERE at_ms < :beforeMs")
    suspend fun deleteMessagesOlderThan(beforeMs: Long): Int

    @Query("DELETE FROM mesh_nodes WHERE COALESCE(last_heard_ms, 0) < :beforeMs AND favorite = 0")
    suspend fun deleteNodesOlderThan(beforeMs: Long): Int

    @Query("DELETE FROM mesh_waypoints WHERE at_ms < :beforeMs")
    suspend fun deleteWaypointsOlderThan(beforeMs: Long): Int

    @Transaction
    suspend fun deleteOlderThan(beforeMs: Long) {
        deleteMessagesOlderThan(beforeMs)
        deleteNodesOlderThan(beforeMs)
        deleteWaypointsOlderThan(beforeMs)
    }

    @Query("DELETE FROM mesh_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM mesh_nodes")
    suspend fun deleteAllNodes()

    @Query("DELETE FROM mesh_waypoints")
    suspend fun deleteAllWaypoints()

    @Query("DELETE FROM mesh_conversations")
    suspend fun deleteAllConversations()

    @Transaction
    suspend fun deleteAll() {
        deleteAllMessages()
        deleteAllNodes()
        deleteAllWaypoints()
        deleteAllConversations()
    }

    /** Forgetting an Edge removes what its mesh brought in. */
    @Query("DELETE FROM mesh_messages WHERE device_id = :deviceId")
    suspend fun deleteDeviceMessages(deviceId: String)

    @Query("DELETE FROM mesh_nodes WHERE device_id = :deviceId")
    suspend fun deleteDeviceNodes(deviceId: String)

    @Query("DELETE FROM mesh_waypoints WHERE device_id = :deviceId")
    suspend fun deleteDeviceWaypoints(deviceId: String)

    @Query("DELETE FROM mesh_conversations WHERE device_id = :deviceId")
    suspend fun deleteDeviceConversations(deviceId: String)

    @Transaction
    suspend fun deleteDevice(deviceId: String) {
        deleteDeviceMessages(deviceId)
        deleteDeviceNodes(deviceId)
        deleteDeviceWaypoints(deviceId)
        deleteDeviceConversations(deviceId)
    }
}
