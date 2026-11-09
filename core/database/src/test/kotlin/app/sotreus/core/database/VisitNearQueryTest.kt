package app.sotreus.core.database

import app.sotreus.core.database.dao.VISITS_NEAR_SQL
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection

/** Runs [VISITS_NEAR_SQL] on SQLite: which existing visits a run of stored sightings joins. */
class VisitNearQueryTest {
    private fun near(conn: Connection, placeId: Long, fromMs: Long, toMs: Long, gapMs: Long): List<Long> =
        conn.prepareStatement(VISITS_NEAR_SQL).use { st ->
            // SQLite numbers named parameters in order of first appearance: placeId, toMs, gapMs, fromMs.
            st.setLong(1, placeId)
            st.setLong(2, toMs)
            st.setLong(3, gapMs)
            st.setLong(4, fromMs)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong("id")) } }
        }

    @Test fun aRunJoinsTheVisitItOverlapsOrComesWithinTheGapOf() = withSchemaDb { conn ->
        conn.exec("INSERT INTO places (id, name, created_at_ms, keep_learning, updated_at_ms) VALUES (1, 'a', 0, 1, 0), (2, 'b', 0, 1, 0)")
        conn.exec("INSERT INTO place_visits (id, place_id, started_at_ms, ended_at_ms) VALUES (10, 1, 1000, 2000)")
        conn.exec("INSERT INTO place_visits (id, place_id, started_at_ms, ended_at_ms) VALUES (11, 1, 10000, NULL)")
        conn.exec("INSERT INTO place_visits (id, place_id, started_at_ms, ended_at_ms) VALUES (12, 2, 2500, 2600)")
        assertEquals(listOf(10L), near(conn, 1, 1500, 1600, 1000)) // inside
        assertEquals(listOf(10L), near(conn, 1, 2500, 2600, 1000)) // 500 after it ended
        assertEquals(listOf(10L), near(conn, 1, 200, 300, 1000)) // 700 before it started
        assertEquals(emptyList<Long>(), near(conn, 1, 3500, 3600, 1000)) // 1500 after: a new visit
        assertEquals(listOf(11L), near(conn, 1, 9500, 9600, 1000)) // just before the open visit
        assertEquals(listOf(11L), near(conn, 1, 50000, 50001, 1000)) // an open visit runs on
        assertEquals(listOf(10L, 11L), near(conn, 1, 1500, 9500, 1000)) // a run touching both: joined
        assertEquals(listOf(12L), near(conn, 2, 2500, 2600, 1000)) // only the place's own visits
        assertEquals(emptyList<Long>(), near(conn, 3, 1500, 1600, 1000))
    }

    @Test fun onlyEndedVisitsWithNoSightingLeftAreEmpty() = withSchemaDb { conn ->
        conn.exec("INSERT INTO places (id, name, created_at_ms, keep_learning, updated_at_ms) VALUES (1, 'a', 0, 1, 0), (2, 'b', 0, 1, 0)")
        conn.exec("INSERT INTO place_visits (id, place_id, started_at_ms, ended_at_ms) VALUES (20, 1, 1000, 2000), (21, 1, 5000, 6000), (22, 1, 9000, NULL), (23, 2, 1000, 2000)")
        val obs = "INSERT INTO observations (entity_id, observed_at_ms, rssi, radio, provenance, place_id) VALUES"
        conn.exec("$obs ('ble:a', 1500, -60, 'BLE', 'SENSED', 1)") // keeps 20
        conn.exec("$obs ('ble:a', 5500, -60, 'BLE', 'SENSED', 2)") // another place's: 21 is empty
        val empty = conn.prepareStatement(app.sotreus.core.database.dao.EMPTY_STORED_VISITS).use { st ->
            st.setLong(1, 1)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
        assertEquals(listOf(21L), empty) // 22 is open, 23 is another place's
    }
}
