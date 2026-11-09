package app.sotreus.core.database

import app.sotreus.core.database.dao.REPAIR_VISIT_ENDS_SQL
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs [REPAIR_VISIT_ENDS_SQL] on SQLite: a visit a restart ended at its start gets its real end. */
class RepairVisitEndsQueryTest {
    @Test fun onlyEndedVisitsEndingBeforeTheirLatestRadioMove() = withSchemaDb { conn ->
        conn.exec("INSERT INTO places (id, name, created_at_ms, keep_learning, updated_at_ms) VALUES (3, 'a', 0, 1, 0)")
        conn.exec(
            "INSERT INTO place_visits (id, place_id, started_at_ms, ended_at_ms) VALUES " +
                "(5, 3, 1000, 1000), " + // ended at its start, radios until 9000: repaired
                "(6, 3, 10000, 12000), " + // ends after its radios: kept
                "(7, 3, 20000, 20500), " + // no radios: kept
                "(8, 3, 30000, NULL)", // open: kept
        )
        conn.exec(
            "INSERT INTO visit_entities (visit_id, entity_id, first_seen_ms, last_seen_ms) VALUES " +
                "(5, 'ble:a', 1000, 4000), (5, 'ble:b', 2000, 9000), (6, 'ble:a', 10000, 11000), (8, 'ble:a', 30000, 40000)",
        )
        val changed = conn.createStatement().use { it.executeUpdate(REPAIR_VISIT_ENDS_SQL) }
        assertEquals(1, changed)
        val ends = conn.createStatement().use { st ->
            st.executeQuery("SELECT id, ended_at_ms FROM place_visits ORDER BY id").use { rs ->
                buildMap { while (rs.next()) put(rs.getLong(1), rs.getLong(2).takeUnless { rs.wasNull() }) }
            }
        }
        assertEquals(mapOf(5L to 9000L, 6L to 12000L, 7L to 20500L, 8L to null), ends)
        // Running it again changes nothing.
        assertEquals(0, conn.createStatement().use { it.executeUpdate(REPAIR_VISIT_ENDS_SQL) })
    }
}
