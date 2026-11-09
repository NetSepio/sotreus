package app.sotreus.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** An in-memory SQLite database with the current exported schema's tables, for running DAO queries. */
internal fun <T> withSchemaDb(block: (Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
    val schema = Json.parseToJsonElement(
        File("schemas/app.sotreus.core.database.SotreusDatabase/${SotreusDatabase.SCHEMA_VERSION}.json").readText(),
    ).jsonObject["database"]!!.jsonObject
    schema["entities"]!!.jsonArray.forEach { e ->
        val t = e.jsonObject
        val name = t["tableName"]!!.jsonPrimitive.content
        conn.createStatement().use { it.execute(t["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)) }
    }
    block(conn)
}

internal fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
