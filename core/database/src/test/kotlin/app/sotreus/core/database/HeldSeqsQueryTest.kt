package app.sotreus.core.database

import app.sotreus.core.database.dao.HELD_SEQS_SQL
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs [HELD_SEQS_SQL] on SQLite: which seqs of a restart's range this phone holds, in any record table, once each. */
class HeldSeqsQueryTest {
    private fun java.sql.Connection.held(deviceId: String, from: Long, to: Long): Long =
        createStatement().use { st ->
            st.executeQuery(HELD_SEQS_SQL.replace(":deviceId", "'$deviceId'").replace(":fromSeq", "$from").replace(":toSeq", "$to")).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    @Test fun countsEachHeldSeqOnceAcrossTablesForThatEdgeOnly() = withSchemaDb { conn ->
        conn.exec(
            "INSERT INTO edge_sightings (device_id, seq, entity_id, type, first_ms, last_ms, rssi_last, rssi_max, rssi_min, frames, continues, covered_live, loc_kind, coverage) VALUES " +
                "('e1', 100, 'ble:a', 1, 0, 1, -60, -60, -60, 1, 0, 0, 'NOT_KNOWN', 0), " +
                "('e1', 102, 'ble:b', 1, 0, 1, -60, -60, -60, 1, 0, 0, 'NOT_KNOWN', 0), " +
                "('e2', 101, 'ble:c', 1, 0, 1, -60, -60, -60, 1, 0, 0, 'NOT_KNOWN', 0)",
        )
        // 102 also still untimed (moved, not yet deleted); 103 kept raw; 110 outside the range.
        conn.exec(
            "INSERT INTO edge_untimed_records (device_id, seq, boot, type, first_uptime_ms, last_uptime_ms, record_hex, received_at_ms) VALUES " +
                "('e1', 102, 58, 1, 0, 1, '', 0)",
        )
        conn.exec(
            "INSERT INTO edge_kept_records (device_id, seq, type, boot, flags, first_ms, last_ms, record_hex, received_at_ms) VALUES " +
                "('e1', 103, 7, 58, 0, 0, 1, '', 0), ('e1', 110, 7, 58, 0, 0, 1, '', 0)",
        )
        assertEquals(3, conn.held("e1", 100, 104))
        assertEquals(0, conn.held("e1", 104, 109))
        assertEquals(1, conn.held("e2", 100, 104))
    }
}
