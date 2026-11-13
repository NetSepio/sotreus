package app.sotreus.edge.protocol.mesh

import app.sotreus.edge.protocol.arr
import app.sotreus.edge.protocol.bool
import app.sotreus.edge.protocol.double
import app.sotreus.edge.protocol.int
import app.sotreus.edge.protocol.long
import app.sotreus.edge.protocol.obj
import app.sotreus.edge.protocol.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where the Edge's mesh position comes from (`config.mesh.position.mode`). The Edge has no GNSS. */
enum class MeshPositionMode(val wire: String) {
    OFF("off"),
    /** A point set once (typed or picked on a map). */
    FIXED("fixed"),
    /** This phone hands its location over with `mesh_set_position`. */
    CLIENT("client");

    companion object {
        fun of(s: String?): MeshPositionMode = entries.firstOrNull { it.wire == s } ?: OFF
    }
}

/**
 * One channel of `config.mesh.channels`. [psk] is `default` (Meshtastic's public key), `none`,
 * `simpleN` or `base64:…`. The Edge replaces the whole list on `set_config`, and a channel sent
 * without a key gets the default one, so every write sends every channel with its key.
 */
data class MeshChannel(
    val name: String,
    val psk: String,
    val positionPrecision: Int,
    val uplink: Boolean = false,
    val downlink: Boolean = false,
) {
    val defaultKey: Boolean get() = psk == "default"
    val noKey: Boolean get() = psk == "none"

    /** An empty name on the primary channel means the preset name (`LongFast`). */
    fun displayName(index: Int, preset: String?): String =
        name.ifBlank { if (index == 0) MeshNames.channelForPreset(preset) else "Channel $index" }

    fun toJson(): JsonObject = JsonObject(mapOf(
        "name" to JsonPrimitive(name), "psk" to JsonPrimitive(psk), "position_precision" to JsonPrimitive(positionPrecision),
        "uplink" to JsonPrimitive(uplink), "downlink" to JsonPrimitive(downlink),
    ))

    companion object {
        const val MAX = 8
        const val NAME_MAX_BYTES = 11
    }
}

data class MeshPositionConfig(val mode: MeshPositionMode, val lat: Double?, val lon: Double?, val alt: Int?, val intervalS: Long)

/**
 * The parts of `config.mesh` the app shows. [raw] keeps every field, so a setting the app does not
 * know is never lost. Writes go out as small partial objects ([MeshConfigPatch]).
 */
data class MeshConfig(
    val region: String,
    val preset: String,
    val hopLimit: Int,
    val rebroadcast: String,
    val txPower: Int,
    val nodeId: String?,
    val longName: String,
    val shortName: String,
    val role: String,
    val licensed: Boolean,
    val channels: List<MeshChannel>,
    val position: MeshPositionConfig,
    val telemetryIntervalS: Long,
    val canned: List<String>,
    val mqttEnabled: Boolean,
    val raw: JsonObject,
) {
    val regionSet: Boolean get() = region != "UNSET" && region.isNotBlank()

    companion object {
        /** [mesh] is `config.mesh`. */
        fun parse(mesh: JsonObject?): MeshConfig? {
            if (mesh == null) return null
            val pos = mesh.obj("position")
            return MeshConfig(
                region = mesh.str("region") ?: "UNSET",
                preset = mesh.str("preset") ?: "LONG_FAST",
                hopLimit = mesh.int("hop_limit") ?: 3,
                rebroadcast = mesh.str("rebroadcast") ?: "all",
                txPower = mesh.int("tx_power") ?: 0,
                nodeId = mesh.str("node_id"),
                longName = mesh.str("long_name").orEmpty(),
                shortName = mesh.str("short_name").orEmpty(),
                role = mesh.str("role") ?: "CLIENT",
                licensed = mesh.bool("licensed") == true,
                channels = mesh.arr("channels").orEmpty().mapNotNull { el ->
                    val c = el as? JsonObject ?: return@mapNotNull null
                    val psk = c.str("psk") ?: "default"
                    MeshChannel(
                        c.str("name").orEmpty(), psk,
                        c.int("position_precision") ?: if (psk == "default") MeshPrecision.DEFAULT_PUBLIC else MeshPrecision.EXACT,
                        c.bool("uplink") == true, c.bool("downlink") == true,
                    )
                },
                position = MeshPositionConfig(
                    MeshPositionMode.of(pos?.str("mode")), pos?.double("lat"), pos?.double("lon"), pos?.int("alt"), pos?.long("interval_s") ?: 900,
                ),
                telemetryIntervalS = mesh.long("telemetry_interval_s") ?: 1800,
                canned = mesh.arr("canned").orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                mqttEnabled = mesh.obj("mqtt")?.bool("enabled") == true,
                raw = mesh,
            )
        }
    }
}

/** Partial `set_config` bodies for the mesh: `{"mesh": {…}}`, holding only what changes. */
object MeshConfigPatch {
    private fun mesh(fields: Map<String, JsonElement>) = JsonObject(mapOf("mesh" to JsonObject(fields)))

    fun fields(vararg pairs: Pair<String, Any>): JsonObject = mesh(pairs.associate { (k, v) -> k to element(v) })

    fun channels(list: List<MeshChannel>): JsonObject {
        require(list.size in 1..MeshChannel.MAX) { "1 to ${MeshChannel.MAX} channels" }
        return mesh(mapOf("channels" to kotlinx.serialization.json.JsonArray(list.map { it.toJson() })))
    }

    fun positionOff(): JsonObject = mesh(mapOf("position" to JsonObject(mapOf("mode" to JsonPrimitive(MeshPositionMode.OFF.wire)))))

    fun positionClient(): JsonObject = mesh(mapOf("position" to JsonObject(mapOf("mode" to JsonPrimitive(MeshPositionMode.CLIENT.wire)))))

    fun positionFixed(lat: Double, lon: Double, alt: Int? = null): JsonObject = mesh(mapOf("position" to JsonObject(buildMap {
        put("mode", JsonPrimitive(MeshPositionMode.FIXED.wire))
        put("lat", JsonPrimitive(lat))
        put("lon", JsonPrimitive(lon))
        alt?.let { put("alt", JsonPrimitive(it)) }
    })))

    private fun element(v: Any): JsonElement = when (v) {
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }
}

/** Display names for Meshtastic identifiers; unknown ones are shown as the Edge writes them. */
object MeshNames {
    /** Protocol §6. The Edge has no command that lists roles, so the app carries this list. */
    val ROLES = listOf(
        "CLIENT", "CLIENT_MUTE", "CLIENT_HIDDEN", "CLIENT_BASE", "ROUTER", "ROUTER_LATE",
        "REPEATER", "TRACKER", "TAK", "TAK_TRACKER", "SENSOR", "LOST_AND_FOUND",
    )

    val REBROADCAST = listOf("all", "all_skip_decoding", "local_only", "known_only", "none", "core_portnums_only")

    /** `LONG_FAST` → `Long Fast`; `TAK_TRACKER` → `TAK Tracker`. */
    fun title(wire: String): String = wire.split('_').filter { it.isNotEmpty() }.joinToString(" ") { part ->
        if (part.length <= 3 && part.all { it.isLetter() } && part in setOf("TAK", "IN", "US", "EU", "CN", "JP", "KR", "TW", "RU", "TH", "UA", "MY", "SG", "PH", "NZ", "KZ", "NP", "BR", "ANZ")) part
        else part.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** The primary channel's name when blank: `LONG_FAST` → `LongFast`. */
    fun channelForPreset(preset: String?): String =
        (preset ?: "LONG_FAST").split('_').joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
}
