package app.sotreus.edge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Tolerant readers: a missing or differently typed field reads as null, never throws. Unknown
// fields are ignored (protocol: additive changes keep the version).
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonObject.int(key: String): Int? = long(key)?.toInt()
fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

val EdgeJson: Json = Json { ignoreUnknownKeys = true; isLenient = false }

/** What this Edge can do (`info.caps`). Unknown keys are kept in [raw] and otherwise ignored. */
data class EdgeCapabilities(val raw: Map<String, Boolean> = emptyMap()) {
    private fun has(key: String) = raw[key] == true
    val bleObservation get() = has("ble_observation")
    val wifi24Observation get() = has("wifi24_observation")
    val wifiProbeObservation get() = has("wifi_probe_observation")
    val gnss get() = has("gnss")
    val localStorage get() = has("local_storage")
    val firmwareSwitching get() = has("firmware_switching")
    val meshtasticMode get() = has("meshtastic_mode")
    val lora get() = has("lora")
    val display get() = has("display")
    val battery get() = has("battery")
    val otaUpload get() = has("ota_upload")

    /** `live` command and `seen.body` (V2 firmware addition). */
    val liveSightings get() = has("live_sightings")

    /** `placement` in config (V2 firmware addition). */
    val placement get() = has("placement")

    companion object {
        fun parse(caps: JsonObject?): EdgeCapabilities =
            EdgeCapabilities(caps.orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }.toMap())
    }
}

/** `info`: static for a boot (protocol §5). */
data class EdgeInfo(
    val protocol: Int,
    val deviceId: String,
    val name: String,
    val suffix: String,
    val firmware: String,
    val hardware: String,
    val boot: Long,
    val mdnsHost: String?,
    val recordSize: Int,
    val recordFormat: Int,
    /** Hex. Lets the app hash network names it already knows and compare with probe records. */
    val probeSalt: String,
    val caps: EdgeCapabilities,
) {
    val supported: Boolean get() = protocol == EdgeProtocol.VERSION && recordSize == EdgeProtocol.RECORD_SIZE && recordFormat == EdgeProtocol.RECORD_FORMAT

    companion object {
        fun parse(o: JsonObject): EdgeInfo? {
            val id = o.str("device_id") ?: return null
            return EdgeInfo(
                protocol = o.int("protocol") ?: 0,
                deviceId = id,
                name = o.str("name") ?: "Sotreus Edge",
                suffix = o.str("suffix").orEmpty(),
                firmware = o.str("fw").orEmpty(),
                hardware = o.str("hw").orEmpty(),
                boot = o.long("boot") ?: 0,
                mdnsHost = o.str("mdns_host"),
                recordSize = o.int("record_size") ?: EdgeProtocol.RECORD_SIZE,
                recordFormat = o.int("record_format") ?: EdgeProtocol.RECORD_FORMAT,
                probeSalt = o.str("probe_salt").orEmpty(),
                caps = EdgeCapabilities.parse(o.obj("caps")),
            )
        }
    }
}

enum class EdgeMode { AWARENESS, MESH, UNKNOWN;
    val wire: String get() = name.lowercase()
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } ?: UNKNOWN }
}

enum class EdgeLinkMode { AUTO, AP, STA, OFF, UNKNOWN;
    val wire: String get() = name.lowercase()
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } ?: UNKNOWN }
}

data class EdgeBattery(val present: Boolean, val source: String, val millivolts: Int?, val percentApprox: Int?) {
    /**
     * A cell is certainly connected. The Edge cannot tell a full cell on USB from USB with no cell
     * (both read about 4.2 V), so only a reading clearly below the charger's level counts.
     */
    val certain: Boolean
        get() = present && source == "battery" && percentApprox != null && millivolts != null && millivolts < CERTAIN_BELOW_MV

    companion object {
        const val CERTAIN_BELOW_MV = 4_100
    }
}

data class EdgeWifiLinkStatus(
    val mode: EdgeLinkMode,
    val state: String,
    val ssid: String,
    val ip: String,
    val stations: Int,
    val channel: Int,
    val sniffing: Boolean,
    /** False while a Wi-Fi link holds the Edge on one channel. */
    val hopping: Boolean,
)

/**
 * [count] is the number of records actually stored: sequence numbers have gaps (records confirmed
 * with `sync_confirm` are erased, and records handed off to the phone are never stored), so never
 * use `head - oldest + 1` for it.
 */
data class EdgeLogStatus(
    val head: Long,
    val oldest: Long,
    val count: Long,
    val capacity: Long,
    val erasing: Boolean,
    val writeFailures: Long,
    val deletedAfterSync: Long = 0,
    val deleting: Boolean = false,
) {
    /** The ring may have overwritten records (it is full to within one 16-record sector). Unknown capacity counts as full. */
    val ringFull: Boolean get() = capacity <= 0 || count >= capacity - 16
}

data class EdgeAwarenessStatus(
    val running: Boolean,
    val openBle: Int,
    val openAp: Int,
    val openProbe: Int,
    val bleReports: Long,
    val wifiFrames: Long,
    val records: Long,
    val queueDrops: Long,
)

/** `status` (command or the 10 s event). */
data class EdgeStatus(
    val uptimeMs: Long,
    val timeValid: Boolean,
    val timeSource: String,
    /** The Edge's wall clock when this status was built; only when [timeValid]. */
    val unixMs: Long?,
    val mode: EdgeMode,
    val battery: EdgeBattery?,
    val link: EdgeWifiLinkStatus?,
    val awareness: EdgeAwarenessStatus?,
    val log: EdgeLogStatus?,
    val eventsDropped: Long,
) {
    companion object {
        fun parse(o: JsonObject): EdgeStatus {
            val b = o.obj("battery")
            val l = o.obj("link")
            val a = o.obj("awareness")
            val open = a?.obj("open_sightings")
            val g = o.obj("log")
            return EdgeStatus(
                uptimeMs = o.long("uptime_ms") ?: 0,
                timeValid = o.bool("time_valid") == true,
                timeSource = o.str("time_source").orEmpty(),
                unixMs = o.long("unix_ms"),
                mode = EdgeMode.of(o.str("mode")),
                battery = b?.let { EdgeBattery(it.bool("present") == true, it.str("source") ?: "unknown", it.int("mv"), it.int("percent_approx")) },
                link = l?.let {
                    EdgeWifiLinkStatus(
                        EdgeLinkMode.of(it.str("mode")), it.str("state").orEmpty(), it.str("ssid").orEmpty(), it.str("ip").orEmpty(),
                        it.int("stations") ?: 0, it.int("channel") ?: 0, it.bool("sniffing") == true, it.bool("hopping") == true,
                    )
                },
                awareness = a?.let {
                    EdgeAwarenessStatus(
                        it.bool("running") == true, open?.int("ble") ?: 0, open?.int("wifi_ap") ?: 0, open?.int("wifi_probe") ?: 0,
                        it.long("ble_reports") ?: 0, it.long("wifi_frames") ?: 0, it.long("records") ?: 0, it.long("queue_drops") ?: 0,
                    )
                },
                log = g?.let {
                    EdgeLogStatus(it.long("head") ?: 0, it.long("oldest") ?: 0, it.long("count") ?: 0, it.long("capacity") ?: 0,
                        it.bool("erasing") == true, it.long("write_failures") ?: 0, it.long("deleted_after_sync") ?: 0, it.bool("deleting") == true)
                },
                eventsDropped = o.long("events_dropped") ?: 0,
            )
        }
    }
}

data class EdgeAwarenessConfig(
    val ble: Boolean,
    val bleActive: Boolean,
    val wifiAps: Boolean,
    val wifiProbes: Boolean,
    val probeNames: Boolean,
    val rssiFloor: Int,
    val closeAfterS: Int,
    val maxWindowS: Int,
)

enum class DeploymentMode { TRAVEL, STATIONARY;
    val wire: String get() = name.lowercase()
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } }
}

/** `config.placement` (V2): how the Edge is used and, when stationary, where it is. Stored only. */
data class EdgePlacement(
    val mode: DeploymentMode,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val setUnixMs: Long? = null,
    /** `phone`, `map` or `gnss`. */
    val source: String? = null,
) {
    val positioned: Boolean get() = lat != null && lon != null && accuracyM != null

    fun toJson(): JsonObject = JsonObject(buildMap {
        put("mode", JsonPrimitive(mode.wire))
        lat?.let { put("lat", JsonPrimitive(it)) }
        lon?.let { put("lon", JsonPrimitive(it)) }
        accuracyM?.let { put("accuracy_m", JsonPrimitive(it)) }
        setUnixMs?.let { put("set_unix_ms", JsonPrimitive(it)) }
        source?.let { put("source", JsonPrimitive(it)) }
    })

    companion object {
        fun parse(o: JsonObject?): EdgePlacement? {
            if (o == null) return null
            val mode = DeploymentMode.of(o.str("mode")) ?: return null
            return EdgePlacement(mode, o.double("lat"), o.double("lon"), o.double("accuracy_m")?.toFloat(), o.long("set_unix_ms"), o.str("source"))
        }
    }
}

/** The parts of `config` the app uses. [raw] keeps the rest (mesh settings) untouched. */
data class EdgeConfig(
    val mode: EdgeMode,
    val link: EdgeLinkMode,
    val awareness: EdgeAwarenessConfig?,
    val placement: EdgePlacement?,
    val raw: JsonObject,
) {
    companion object {
        fun parse(o: JsonObject): EdgeConfig {
            val a = o.obj("awareness")
            return EdgeConfig(
                mode = EdgeMode.of(o.str("mode")),
                link = EdgeLinkMode.of(o.str("link")),
                awareness = a?.let {
                    EdgeAwarenessConfig(
                        it.bool("ble") == true, it.bool("ble_active") == true, it.bool("wifi_aps") == true, it.bool("wifi_probes") == true,
                        it.bool("probe_names") == true, it.int("rssi_floor") ?: -100, it.int("close_after_s") ?: 60, it.int("max_window_s") ?: 300,
                    )
                },
                placement = EdgePlacement.parse(o.obj("placement")),
                raw = o,
            )
        }
    }
}

enum class SightingKind(val wire: String, val recordType: Int) {
    BLE("ble", EdgeRecordType.BLE),
    WIFI_AP("wifi_ap", EdgeRecordType.WIFI_AP),
    WIFI_PROBE("wifi_probe", EdgeRecordType.WIFI_PROBE);

    /** `watch` takes `ble` or `wifi` (access points and probing devices). */
    val watchKind: String get() = if (this == BLE) "ble" else "wifi"

    companion object {
        fun of(s: String?): SightingKind? = entries.firstOrNull { it.wire == s }
    }
}

/** One open sighting from `live` (V2): what the Edge is hearing right now. */
data class LiveRow(
    val kind: SightingKind,
    val address: String,
    val rssiLast: Int,
    val rssiMax: Int,
    /** Milliseconds since the Edge last heard it, measured on the Edge. Independent of clocks. */
    val agoMs: Long,
    val count: Long,
)

data class LivePage(val rows: List<LiveRow>, val total: Int, val nextOffset: Int?, val uptimeMs: Long?) {
    companion object {
        /** Rows are `[kind, addr, rssi_last, rssi_max, ago_ms, count]`; malformed rows are skipped. */
        fun parse(resp: JsonObject): LivePage {
            val rows = resp.arr("rows").orEmpty().mapNotNull { el ->
                val a = el as? JsonArray ?: return@mapNotNull null
                if (a.size < 6) return@mapNotNull null
                fun p(i: Int) = a[i] as? JsonPrimitive
                val kind = SightingKind.of(p(0)?.takeIf { it.isString }?.content) ?: return@mapNotNull null
                val addr = p(1)?.takeIf { it.isString }?.content?.uppercase() ?: return@mapNotNull null
                LiveRow(
                    kind, addr,
                    p(2)?.longOrNull?.toInt() ?: return@mapNotNull null,
                    p(3)?.longOrNull?.toInt() ?: return@mapNotNull null,
                    p(4)?.longOrNull ?: return@mapNotNull null,
                    p(5)?.longOrNull ?: 0,
                )
            }
            return LivePage(rows, resp.int("total") ?: rows.size, resp.int("next"), resp.long("uptime_ms"))
        }
    }
}

/** Events and responses from the Edge (protocol §3). Mesh events are passed through as [Other]. */
sealed interface EdgeEvent {
    data class Response(val id: Long?, val ok: Boolean, val error: String?, val body: JsonObject) : EdgeEvent
    data class Status(val status: EdgeStatus) : EdgeEvent

    /** A record was written. Over Bluetooth only the seq and type; read it with `sync`. */
    data class Obs(val seq: Long, val type: Int) : EdgeEvent

    /** A device was first heard in a new window. [body] is the record body (V2 `seen.body`), if sent. */
    data class Seen(val kind: SightingKind, val address: String, val rssi: Int, val label: String?, val uptimeMs: Long, val body: ByteArray?) : EdgeEvent

    /** During `watch`, at most 4 Hz. */
    data class Rssi(val kind: String, val address: String, val rssi: Int, val channel: Int?, val uptimeMs: Long, val unixMs: Long?) : EdgeEvent
    data class Mode(val mode: EdgeMode, val restarting: Boolean) : EdgeEvent
    data class SyncDone(val id: Long?, val ok: Boolean, val sent: Long, val next: Long, val head: Long, val more: Boolean) : EdgeEvent
    /** The phone hand-off started or stopped (`stream` event). After a stop, unacknowledged records are stored on the Edge. */
    data class Stream(val started: Boolean, val reason: String?) : EdgeEvent
    data class Other(val ev: String, val body: JsonObject) : EdgeEvent

    companion object {
        /** Null for a line that is not a JSON object with an `ev`. */
        fun parse(line: String): EdgeEvent? {
            val o = runCatching { EdgeJson.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null
            return parse(o)
        }

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        fun parse(o: JsonObject): EdgeEvent? = when (val ev = o.str("ev") ?: return null) {
            "resp" -> Response(o.long("id"), o.bool("ok") == true, o.str("error"), o)
            "status" -> Status(EdgeStatus.parse(o.obj("status") ?: o))
            "obs" -> o.long("seq")?.let { Obs(it, o.int("type") ?: 0) }
            "seen" -> {
                val kind = SightingKind.of(o.str("kind"))
                val addr = o.str("addr")
                if (kind == null || addr == null) null else Seen(
                    kind, addr.uppercase(), o.int("rssi") ?: -127, o.str("label"), o.long("uptime_ms") ?: 0,
                    o.str("body")?.let { runCatching { kotlin.io.encoding.Base64.decode(it) }.getOrNull() },
                )
            }
            "rssi" -> o.str("addr")?.let { Rssi(o.str("kind").orEmpty(), it.uppercase(), o.int("rssi") ?: -127, o.int("channel"), o.long("uptime_ms") ?: 0, o.long("unix_ms")) }
            "mode" -> Mode(EdgeMode.of(o.str("mode")), o.bool("restarting") == true)
            "stream" -> Stream(o.str("state") == "started", o.str("reason"))
            "sync_done" -> SyncDone(o.long("id"), o.bool("ok") == true, o.long("sent") ?: 0, o.long("next") ?: 0, o.long("head") ?: 0, o.bool("more") == true)
            else -> Other(ev, o)
        }
    }
}

/** Command lines (protocol §2). Each carries an `id` the response echoes. */
object EdgeCommands {
    private fun cmd(name: String, id: Long, extra: Map<String, JsonElement> = emptyMap()): String =
        JsonObject(buildMap { put("cmd", JsonPrimitive(name)); put("id", JsonPrimitive(id)); putAll(extra) }).toString()

    fun info(id: Long) = cmd("info", id)
    fun status(id: Long) = cmd("status", id)
    fun getConfig(id: Long) = cmd("get_config", id)
    fun setConfig(id: Long, partial: JsonObject) = cmd("set_config", id, mapOf("config" to partial))
    fun setMode(id: Long, mode: EdgeMode) = cmd("set_mode", id, mapOf("mode" to JsonPrimitive(mode.wire)))

    /** Sent on every connect, and again when the clock drifts (HANDOFF_V2_EDGE.md §4.4). */
    fun setTime(id: Long, unixMs: Long, tzOffsetMin: Int) =
        cmd("set_time", id, mapOf("unix_ms" to JsonPrimitive(unixMs), "source" to JsonPrimitive("phone"), "tz_offset_min" to JsonPrimitive(tzOffsetMin)))

    fun sync(id: Long, from: Long, limit: Int = EdgeProtocol.SYNC_PAGE) =
        cmd("sync", id, mapOf("from" to JsonPrimitive(from), "limit" to JsonPrimitive(limit)))

    fun live(id: Long, offset: Int = 0, limit: Int = EdgeProtocol.LIVE_PAGE) =
        cmd("live", id, mapOf("offset" to JsonPrimitive(offset), "limit" to JsonPrimitive(limit)))

    fun watch(id: Long, kind: SightingKind, address: String, seconds: Int = EdgeProtocol.WATCH_SECONDS) =
        cmd("watch", id, mapOf("kind" to JsonPrimitive(kind.watchKind), "addr" to JsonPrimitive(address), "seconds" to JsonPrimitive(seconds)))

    fun unwatch(id: Long) = cmd("unwatch", id)
    fun access(id: Long) = cmd("access", id)
    fun pairingOpen(id: Long, seconds: Int = 120) = cmd("pairing_open", id, mapOf("seconds" to JsonPrimitive(seconds)))
    fun reboot(id: Long) = cmd("reboot", id)

    /** Every record up to and including [seq] is committed on the phone; the Edge erases them. */
    fun syncConfirm(id: Long, seq: Long) = cmd("sync_confirm", id, mapOf("seq" to JsonPrimitive(seq)))

    /** Bluetooth only, after subscribing to `records`: new records come to the phone instead of flash. */
    fun streamStart(id: Long) = cmd("stream_start", id)

    /** Cumulative: the phone has committed every handed-off record up to and including [seq]. */
    fun streamAck(id: Long, seq: Long) = cmd("stream_ack", id, mapOf("seq" to JsonPrimitive(seq)))

    fun streamStop(id: Long) = cmd("stream_stop", id)
}

/** `access` response: secrets shown only on request and never stored in clear. */
data class EdgeAccess(val pin: String, val apSsid: String, val apPassword: String) {
    companion object {
        fun parse(o: JsonObject): EdgeAccess? = o.str("pin")?.let { EdgeAccess(it, o.str("ap_ssid").orEmpty(), o.str("ap_password").orEmpty()) }
    }
}
