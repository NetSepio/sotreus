package app.sotreus.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * Upgrades a real SQLite database built from the exported schema 7 with [Migrations.MIGRATION_7_8]
 * and checks every table's columns (name, type, not-null, primary key) and indices against the
 * exported schema 8 that Room validates on open. Existing rows survive with null V2 columns.
 */
class MigrationSchemaTest {
    private val dir = File("schemas/app.sotreus.core.database.SotreusDatabase")

    private fun schema(v: Int): JsonObject = Json.parseToJsonElement(File(dir, "$v.json").readText()).jsonObject["database"]!!.jsonObject

    private fun create(conn: Connection, v: Int) {
        schema(v)["entities"]!!.jsonArray.forEach { e ->
            val t = e.jsonObject
            val name = t["tableName"]!!.jsonPrimitive.content
            conn.createStatement().use { it.execute(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)) }
            t["indices"]?.jsonArray?.forEach { ix -> conn.createStatement().use { it.execute(ix.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)) } }
        }
    }

    private fun Connection.asSupport(): SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, m, args ->
        when (m.name) {
            "execSQL" -> { createStatement().use { it.execute(args!![0] as String) }; null }
            else -> error("unexpected call ${m.name}")
        }
    } as SupportSQLiteDatabase

    private data class Col(val name: String, val type: String, val notNull: Boolean, val pk: Boolean)

    private fun columns(conn: Connection, table: String): Set<Col> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildSet { while (rs.next()) add(Col(rs.getString("name"), rs.getString("type"), rs.getInt("notnull") == 1, rs.getInt("pk") > 0)) }
        }
    }

    private fun indices(conn: Connection, table: String): Set<String> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA index_list(`$table`)").use { rs -> buildSet { while (rs.next()) rs.getString("name").takeIf { it.startsWith("index_") }?.let(::add) } }
    }

    /** Builds schema [from], runs [migration], and checks every table against the exported schema [from] + 1. */
    private fun checkUpgrade(from: Int, migration: androidx.room.migration.Migration) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, from)
            migration.migrate(conn.asSupport())
            schema(from + 1)["entities"]!!.jsonArray.forEach { e ->
                val t = e.jsonObject
                val name = t["tableName"]!!.jsonPrimitive.content
                val pk = t["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                val expected = t["fields"]!!.jsonArray.map { f ->
                    val o = f.jsonObject
                    val col = o["columnName"]!!.jsonPrimitive.content
                    Col(col, o["affinity"]!!.jsonPrimitive.content, o["notNull"]?.jsonPrimitive?.boolean == true, col in pk)
                }.toSet()
                assertEquals("v${from + 1} table $name", expected, columns(conn, name))
                val ix = t["indices"]?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }?.toSet().orEmpty()
                assertEquals("v${from + 1} indices of $name", ix, indices(conn, name))
            }
        }
    }

    @Test fun migration8To9MatchesExportedSchema9() = checkUpgrade(8, Migrations.MIGRATION_8_9)

    @Test fun migration9To10MatchesExportedSchema10() = checkUpgrade(9, Migrations.MIGRATION_9_10)

    @Test fun migration10To11MatchesExportedSchema11() = checkUpgrade(10, Migrations.MIGRATION_10_11)

    @Test fun migration7To8MatchesExportedSchema8() {
        checkUpgrade(7, Migrations.MIGRATION_7_8)
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            create(conn, 7)
            conn.createStatement().use {
                it.execute("INSERT INTO entities (id, radio, address, address_type, link_confidence, user_state, guess_confidence, notable, service_uuids, first_seen_ms, last_seen_ms, last_rssi, observation_count) VALUES ('ble:AA', 'BLE', 'AA', 'PUBLIC', 'HIGH', 'UNCLASSIFIED', 'LOW', 0, '', 1, 2, -60, 1)")
                it.execute("INSERT INTO observations (entity_id, observed_at_ms, rssi, radio, provenance) VALUES ('ble:AA', 2, -60, 'BLE', 'SENSED')")
            }
            Migrations.MIGRATION_7_8.migrate(conn.asSupport())
            schema(8)["entities"]!!.jsonArray.forEach { e ->
                val t = e.jsonObject
                val name = t["tableName"]!!.jsonPrimitive.content
                val pk = t["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                val expected = t["fields"]!!.jsonArray.map { f ->
                    val o = f.jsonObject
                    val col = o["columnName"]!!.jsonPrimitive.content
                    Col(col, o["affinity"]!!.jsonPrimitive.content, o["notNull"]?.jsonPrimitive?.boolean == true, col in pk)
                }.toSet()
                assertEquals("table $name", expected, columns(conn, name))
                val ix = t["indices"]?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }?.toSet().orEmpty()
                assertEquals("indices of $name", ix, indices(conn, name))
            }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT origin, loc_source FROM observations").use { rs ->
                    rs.next()
                    assertEquals(null, rs.getString(1))
                    assertEquals(null, rs.getString(2))
                }
                st.executeQuery("SELECT COUNT(*) FROM entities WHERE wifi_role IS NULL").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
            }
        }
    }

    @Test fun everyVersionHasAMigration() {
        assertEquals(SotreusDatabase.SCHEMA_VERSION - 1, Migrations.ALL.size)
        Migrations.ALL.forEachIndexed { i, m -> assertEquals(i + 1, m.startVersion); assertEquals(i + 2, m.endVersion) }
    }
}
