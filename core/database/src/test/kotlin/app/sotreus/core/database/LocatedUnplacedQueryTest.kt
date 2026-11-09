package app.sotreus.core.database

import app.sotreus.core.database.dao.LOCATED_UNPLACED_OBSERVATIONS_SQL
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection

/**
 * Runs [LOCATED_UNPLACED_OBSERVATIONS_SQL] on a real SQLite database built from the exported
 * schema: V1 geotagged rows take the accuracy of the session fix they were tagged with, only when
 * that fix has the same position and was taken at most the given age before the sighting.
 */
class LocatedUnplacedQueryTest {
    private fun Connection.observation(id: Long, atMs: Long, lat: Double?, lon: Double?, sessionId: Long?, accuracyM: Double? = null, placeId: Long? = null) = exec(
        "INSERT INTO observations (id, entity_id, observed_at_ms, rssi, radio, provenance, place_id, session_id, lat, lon, loc_accuracy_m) " +
            "VALUES ($id, 'ble:x', $atMs, -60, 'BLE', 'SENSED', $placeId, $sessionId, $lat, $lon, $accuracyM)",
    )

    private data class Row(val id: Long, val accuracyM: Double?)

    private fun query(conn: Connection, afterId: Long, limit: Int, maxFixAgeMs: Long): List<Row> =
        conn.prepareStatement(LOCATED_UNPLACED_OBSERVATIONS_SQL).use { st ->
            // SQLite numbers named parameters in order of first appearance.
            st.setLong(1, maxFixAgeMs)
            st.setLong(2, afterId)
            st.setInt(3, limit)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(Row(rs.getLong("id"), rs.getDouble("accuracy_m").takeUnless { rs.wasNull() }))
                }
            }
        }

    @Test fun geotaggedRowsTakeTheirSessionFixAccuracyOnlyWhenItMatches() = withSchemaDb { conn ->
        conn.exec("INSERT INTO sessions (id, kind, name, started_at_ms, geotag) VALUES (1, 'JOURNEY', 'Journey', 0, 1)")
        conn.exec("INSERT INTO session_locations (session_id, at_ms, lat, lon, accuracy_m) VALUES (1, 1000, 12.1, 77.1, 12.0)")
        conn.exec("INSERT INTO session_locations (session_id, at_ms, lat, lon, accuracy_m) VALUES (1, 20000, 12.2, 77.2, 40.0)")
        conn.observation(1, 5_000, 12.1, 77.1, sessionId = 1) // its fix, 4 s old: ±12 m
        conn.observation(2, 15_000, 12.1, 77.1, sessionId = 1) // same fix, 14 s old: too old
        conn.observation(3, 21_000, 12.2, 77.2, sessionId = 1) // its fix is coarse: ±40 m, left to the rules
        conn.observation(4, 22_000, 12.3, 77.3, sessionId = 1) // no fix at that position
        conn.observation(5, 500, 12.1, 77.1, sessionId = 1) // before the fix was taken
        conn.observation(6, 6_000, 12.4, 77.4, sessionId = null, accuracyM = 8.0) // its own accuracy wins
        conn.observation(7, 5_000, 12.1, 77.1, sessionId = 1, placeId = 3) // already filed
        conn.observation(8, 5_000, null, null, sessionId = 1) // not located
        assertEquals(
            listOf(Row(1, 12.0), Row(2, null), Row(3, 40.0), Row(4, null), Row(5, null), Row(6, 8.0)),
            query(conn, afterId = 0, limit = 100, maxFixAgeMs = 10_000),
        )
        assertEquals(listOf(Row(5, null), Row(6, 8.0)), query(conn, afterId = 4, limit = 2, maxFixAgeMs = 10_000))
    }
}
