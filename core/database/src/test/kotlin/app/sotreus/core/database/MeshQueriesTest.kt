package app.sotreus.core.database

import app.sotreus.core.database.dao.HELD_SEQS_SQL
import app.sotreus.core.database.dao.MESH_CONVERSATIONS_SQL
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs the mesh queries on SQLite with the exported schema. */
class MeshQueriesTest {
    private fun java.sql.Connection.msg(from: Long, packet: Long, conversation: String, text: String, at: Long, outgoing: Boolean = false, read: Boolean = false, reaction: Boolean = false, seq: Long? = null) =
        exec(
            "INSERT INTO mesh_messages (device_id, from_node, packet_id, to_node, channel, conversation, text, outgoing, at_ms, time_from_edge, reaction, pki, read, seq) " +
                "VALUES ('e1', $from, $packet, 4294967295, 0, '$conversation', '$text', ${if (outgoing) 1 else 0}, $at, 1, ${if (reaction) 1 else 0}, 0, ${if (read) 1 else 0}, ${seq ?: "NULL"})",
        )

    private data class Row(val conversation: String, val lastText: String, val unread: Int)

    private fun java.sql.Connection.conversations(): List<Row> = createStatement().use { st ->
        st.executeQuery(MESH_CONVERSATIONS_SQL.replace(":deviceId", "'e1'")).use { rs ->
            buildList { while (rs.next()) add(Row(rs.getString("conversation"), rs.getString("lastText"), rs.getInt("unread"))) }
        }
    }

    @Test fun oneRowPerConversationWithItsNewestTextAndUnreadCount() = withSchemaDb { conn ->
        conn.msg(1, 10, "ch:0", "Leaving now", at = 100, read = true)
        conn.msg(2, 11, "ch:0", "Reached the ridge", at = 300)
        conn.msg(5, 12, "ch:0", "Great", at = 200, outgoing = true)
        // A reaction is newer but never the preview.
        conn.msg(3, 13, "ch:0", "👍", at = 400, reaction = true)
        conn.msg(2, 14, "dm:2", "See you at five", at = 250)
        val rows = conn.conversations()
        assertEquals(listOf(Row("ch:0", "Reached the ridge", 2), Row("dm:2", "See you at five", 1)), rows)
    }

    @Test fun meshMessagesCountAsHeldRecords() = withSchemaDb { conn ->
        conn.msg(2, 11, "ch:0", "from a record", at = 300, seq = 120)
        conn.msg(2, 12, "ch:0", "from an event only", at = 301)
        val held = conn.createStatement().use { st ->
            st.executeQuery(HELD_SEQS_SQL.replace(":deviceId", "'e1'").replace(":fromSeq", "100").replace(":toSeq", "130")).use { rs -> rs.next(); rs.getLong(1) }
        }
        assertEquals(1, held)
    }
}
