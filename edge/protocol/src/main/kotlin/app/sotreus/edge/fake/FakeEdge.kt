package app.sotreus.edge.fake

import app.sotreus.edge.link.ByteTransport
import app.sotreus.edge.link.EdgeLinkState
import app.sotreus.edge.protocol.EdgeJson
import app.sotreus.edge.protocol.EdgePlacement
import app.sotreus.edge.protocol.EdgeProtocol
import app.sotreus.edge.protocol.EdgeRecordCodec
import app.sotreus.edge.protocol.EdgeRecordHeader
import app.sotreus.edge.protocol.NdjsonFramer
import app.sotreus.edge.protocol.SightingKind
import app.sotreus.edge.protocol.bool
import app.sotreus.edge.protocol.int
import app.sotreus.edge.protocol.long
import app.sotreus.edge.protocol.obj
import app.sotreus.edge.protocol.str
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An in-memory Edge speaking protocol v1 over the Bluetooth byte pipes. For tests, Compose
 * previews and the simulated Edge; never presented as real data. Records, open sightings and the
 * clock are set by the caller.
 */
class FakeEdge(
    private val phoneNowMs: () -> Long = System::currentTimeMillis,
    val deviceId: String = "9f3c00aa11bb22cc",
    val suffix: String = "96FF",
    var caps: Map<String, Boolean> = DEFAULT_CAPS,
    mtu: Int = 517,
) : ByteTransport {
    private val _state = MutableStateFlow<EdgeLinkState>(EdgeLinkState.Connected(mtu))
    override val state: StateFlow<EdgeLinkState> = _state
    private val events = Channel<ByteArray>(Channel.UNLIMITED)
    private val recordsOut = Channel<ByteArray>(Channel.UNLIMITED)
    override val eventChunks: Flow<ByteArray> = events.receiveAsFlow()
    override val recordChunks: Flow<ByteArray> = recordsOut.receiveAsFlow()
    override var maxWrite: Int = mtu - EdgeProtocol.ATT_OVERHEAD
        private set
    private val control = NdjsonFramer()

    var boot: Long = 9
    var bootAtMs: Long = phoneNowMs() - 60_000
    var timeValid: Boolean = true
    var linkRssiDbm: Int? = -58
    var mode: String = "awareness"
    var placement: EdgePlacement? = null
    var battery: Int? = 82
    val records = ArrayList<ByteArray>()
    val open = ArrayList<OpenSighting>()
    val received = ArrayList<String>()

    /** Ignore commands (no response), to test timeouts and dropped links. */
    var mute: Boolean = false

    /** Records past this index are dropped from a sync, to test stalls. */
    var dropRecordsAfter: Int = Int.MAX_VALUE

    data class OpenSighting(val kind: SightingKind, val address: String, val rssiLast: Int, val rssiMax: Int, val agoMs: Long, val count: Long)

    /** Next seq to assign. Never repeats, so stored seqs have gaps (handed-off and confirmed records). */
    var nextSeq: Long = 1
        private set

    /** Newest stored seq (0 = nothing ever stored), as `status.log.head` / `sync` report it. */
    var head: Long = 0
        private set
    val oldest: Long get() = records.firstOrNull()?.let { seqOf(it) } ?: 0

    /** Highest seq confirmed with `sync_confirm`, and every value the phone sent. */
    var deletedThrough: Long = 0
        private set
    val confirms = ArrayList<Long>()

    /** Phone hand-off (protocol §4): records waiting for `stream_ack`, oldest first. */
    var streaming: Boolean = false
        private set
    val unacked = ArrayList<ByteArray>()
    val acks = ArrayList<Long>()

    /** False: answer `sync_confirm` and `stream_start` as older firmware does (unknown command). */
    var supportsDeleteAfterSync: Boolean = true

    /** Uses up [n] seqs without storing them, as records handed off to a phone do. */
    fun skipSeqs(n: Int) {
        nextSeq += n
    }

    /** Called with each command line before it is handled, so tests can check what the phone sent and when. */
    var onCommand: (JsonObject) -> Unit = {}

    fun uptimeMs(): Long = phoneNowMs() - bootAtMs

    /** Appends a record with the next seq; times are Unix ms when [timeValid], else uptime. */
    fun addRecord(type: Int, body: ByteArray, firstMs: Long, lastMs: Long, flags: Int = 0): Long {
        val seq = nextSeq++
        val f = flags or (if (timeValid) EdgeRecordHeader.FLAG_TIME_VALID else 0)
        val bytes = EdgeRecordCodec.encode(EdgeRecordHeader(seq, type, f, (boot and 0xFFFF).toInt(), firstMs, lastMs, body.size, 1), body)
        if (streaming) {
            // Handed off: sent now, kept in RAM until acknowledged; the oldest goes to flash when 32 wait.
            if (!loseInTransit(seq)) NdjsonFramer.chunks(bytes, maxWrite).forEach { recordsOut.trySend(it) }
            unacked += bytes
            if (unacked.size > STREAM_HOLD) store(unacked.removeAt(0))
        } else {
            store(bytes)
        }
        return seq
    }

    /** Drops records from the front, as the ring buffer would. */
    private fun store(bytes: ByteArray) {
        records += bytes
        head = maxOf(head, seqOf(bytes))
        records.sortBy { seqOf(it) }
    }

    fun overwriteOldest(count: Int) = repeat(count.coerceAtMost(records.size)) { records.removeAt(0) }

    /** The hand-off stops (no ack for 30 s, link closed…): unacknowledged records are stored. */
    /** Newer firmware answers `stream_start` with `first_seq` as well as `stored_head`. */
    var reportsFirstSeq = true

    /** Runs between starting the hand-off and answering `stream_start` (a record made in that gap). */
    var recordBeforeStartResponse: (() -> Unit)? = null

    /** Streamed records for which this returns true never reach the phone (they stay unacknowledged). */
    var loseInTransit: (Long) -> Boolean = { false }

    fun stopStream(reason: String = "no confirmation from the app") {
        if (!streaming) return
        streaming = false
        unacked.forEach(::store)
        unacked.clear()
        emit(obj("ev" to "stream", "state" to "stopped", "reason" to reason))
    }

    fun emit(event: JsonObject) = sendLine(event.toString())

    fun emitObs(seq: Long, type: Int) = emit(obj("ev" to "obs", "seq" to seq, "type" to type))

    fun drop(reason: String = "out of range") {
        _state.value = EdgeLinkState.Lost(phoneNowMs(), reason)
    }

    fun reconnect(mtu: Int = 517) {
        maxWrite = mtu - EdgeProtocol.ATT_OVERHEAD
        _state.value = EdgeLinkState.Connected(mtu)
    }

    override suspend fun write(chunk: ByteArray) {
        if (_state.value !is EdgeLinkState.Connected) error("not connected")
        require(chunk.size <= maxWrite) { "write of ${chunk.size} over MTU" }
        control.feed(chunk).forEach { line ->
            received += line
            val req = runCatching { EdgeJson.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return@forEach
            handle(req)
        }
    }

    override suspend fun readRssi(): Int? = linkRssiDbm.takeIf { _state.value is EdgeLinkState.Connected }

    override suspend fun close() {
        _state.value = EdgeLinkState.Idle
    }

    private fun sendLine(line: String) {
        NdjsonFramer.chunks(NdjsonFramer.frame(line), maxWrite).forEach { events.trySend(it) }
    }

    private fun resp(id: Long?, vararg fields: Pair<String, Any?>) =
        emit(obj("ev" to "resp", "id" to id, "ok" to true, *fields))

    private fun err(id: Long?, message: String) = emit(obj("ev" to "resp", "id" to id, "ok" to false, "error" to message))

    private fun handle(req: JsonObject) {
        if (mute) return
        onCommand(req)
        val id = req.long("id")
        when (req.str("cmd")) {
            "info" -> resp(id, "info" to info())
            "status" -> resp(id, "status" to status())
            "get_config" -> resp(id, "config" to config())
            "set_config", "set_mode" -> {
                val patch = if (req.str("cmd") == "set_mode") req else req.obj("config") ?: JsonObject(emptyMap())
                patch.str("mode")?.let { mode = it }
                patch.obj("placement")?.let { p ->
                    val cur = placement ?: EdgePlacement(app.sotreus.edge.protocol.DeploymentMode.TRAVEL)
                    val merged = JsonObject(cur.toJson() + p)
                    placement = if (p["lat"] is JsonNull) EdgePlacement.parse(JsonObject(merged - "lat" - "lon" - "accuracy_m" - "set_unix_ms" - "source"))
                    else EdgePlacement.parse(merged)
                }
                resp(id, "config" to config())
            }
            "set_time" -> {
                req.long("unix_ms")?.let { bootAtMs = it - uptimeMs(); timeValid = true }
                resp(id)
            }
            "live" -> {
                if (mode != "awareness") return err(id, "not listening (Mesh mode or stopped)")
                val offset = req.int("offset") ?: 0
                val limit = (req.int("limit") ?: 100).coerceIn(1, 100)
                val sorted = open.sortedByDescending { it.rssiLast }
                val page = sorted.drop(offset).take(limit)
                val next = if (offset + limit < sorted.size) offset + limit else null
                resp(
                    id,
                    "rows" to JsonArray(page.map { JsonArray(listOf(p(it.kind.wire), p(it.address.lowercase()), p(it.rssiLast), p(it.rssiMax), p(it.agoMs), p(it.count))) }),
                    "total" to sorted.size, "next" to next, "uptime_ms" to uptimeMs(),
                )
            }
            "sync" -> {
                val from = req.long("from") ?: 0
                val limit = req.long("limit") ?: Long.MAX_VALUE
                resp(id, "oldest" to oldest, "head" to head)
                val batch = records.filter { seqOf(it) >= from }.take(limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                val sent = batch.take(dropRecordsAfter)
                val stream = sent.fold(ByteArray(0)) { acc, r -> acc + r }
                NdjsonFramer.chunks(stream, maxWrite).forEach { recordsOut.trySend(it) }
                if (dropRecordsAfter == Int.MAX_VALUE) {
                    val next = batch.lastOrNull()?.let { seqOf(it) + 1 } ?: from
                    emit(obj("ev" to "sync_done", "id" to id, "ok" to true, "sent" to batch.size, "next" to next, "head" to head, "more" to (next <= head)))
                }
            }
            "sync_confirm", "stream_start", "stream_ack", "stream_stop" -> if (!supportsDeleteAfterSync) return err(id, "unknown command") else handleV2(req, id)
            else -> handleRest(req, id)
        }
    }

    private fun handleV2(req: JsonObject, id: Long?) {
        when (req.str("cmd")) {
            "sync_confirm" -> {
                val seq = req.long("seq") ?: return err(id, "missing seq")
                confirms += seq
                val through = minOf(seq, head)
                records.removeAll { seqOf(it) <= through }
                deletedThrough = maxOf(deletedThrough, through)
                resp(id, "deleted_through" to through)
            }
            "stream_start" -> {
                streaming = true
                val stored = head
                val first = nextSeq
                emit(obj("ev" to "stream", "state" to "started"))
                // A record made right now reaches the phone before this response (two characteristics).
                recordBeforeStartResponse?.invoke()
                if (reportsFirstSeq) resp(id, "stored_head" to stored, "first_seq" to first) else resp(id, "stored_head" to stored)
            }
            "stream_ack" -> {
                val seq = req.long("seq") ?: return err(id, "missing seq")
                acks += seq
                unacked.removeAll { seqOf(it) <= seq }
                resp(id)
            }
            "stream_stop" -> {
                resp(id)
                stopStream("stopped by the app")
            }
        }
    }

    private fun handleRest(req: JsonObject, id: Long?) {
        when (req.str("cmd")) {
            "watch", "unwatch", "pairing_open", "reboot" -> resp(id)
            "access" -> resp(id, "pin" to "12345678", "ap_ssid" to "Sotreus-Edge-$suffix", "ap_password" to "abcdefghjkmn")
            else -> err(id, "unknown command")
        }
    }

    private fun info() = obj(
        "protocol" to 1, "device_id" to deviceId, "name" to "Sotreus Edge", "suffix" to suffix, "fw" to "0.2.0",
        "hw" to "heltec_wifi_lora_32_v3", "boot" to boot, "record_size" to 256, "record_format" to 1,
        "mdns_host" to "sotreus-${suffix.lowercase()}.local", "probe_salt" to "0a0b0c0d",
        "caps" to JsonObject(caps.mapValues { JsonPrimitive(it.value) }),
    )

    private fun status() = obj(
        "uptime_ms" to uptimeMs(), "time_valid" to timeValid, "time_source" to "phone",
        "unix_ms" to (if (timeValid) bootAtMs + uptimeMs() else null), "mode" to mode,
        "battery" to obj("present" to (battery != null), "source" to (if (battery != null) "battery" else "usb"), "percent_approx" to battery),
        "link" to obj("mode" to "off", "state" to "off", "ssid" to "", "ip" to "", "stations" to 0, "channel" to 6, "sniffing" to true, "hopping" to true),
        "awareness" to obj("running" to (mode == "awareness"), "open_sightings" to obj("ble" to open.count { it.kind == SightingKind.BLE }, "wifi_ap" to open.count { it.kind == SightingKind.WIFI_AP }, "wifi_probe" to open.count { it.kind == SightingKind.WIFI_PROBE }), "records" to records.size),
        "log" to obj("head" to head, "oldest" to oldest, "count" to records.size, "capacity" to 14_000, "erasing" to false, "write_failures" to 0),
        "events_dropped" to 0,
    )

    private fun config() = obj(
        "mode" to mode, "link" to "off",
        "placement" to (placement ?: EdgePlacement(app.sotreus.edge.protocol.DeploymentMode.TRAVEL)).toJson(),
        "awareness" to obj("ble" to true, "ble_active" to true, "wifi_aps" to true, "wifi_probes" to true, "probe_names" to false, "rssi_floor" to -100, "close_after_s" to 60, "max_window_s" to 300),
    )

    companion object {
        const val STREAM_HOLD = 32

        val DEFAULT_CAPS = mapOf(
            "ble_observation" to true, "wifi24_observation" to true, "wifi_probe_observation" to true, "gnss" to false,
            "local_storage" to true, "firmware_switching" to false, "meshtastic_mode" to true, "lora" to true,
            "display" to true, "battery" to true, "ota_upload" to true, "live_sightings" to true, "placement" to true,
        )

        fun seqOf(record: ByteArray): Long =
            (record[0].toLong() and 0xFF) or ((record[1].toLong() and 0xFF) shl 8) or ((record[2].toLong() and 0xFF) shl 16) or ((record[3].toLong() and 0xFF) shl 24)

        private fun p(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }

        fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(fields.associate { (k, v) -> k to p(v) })
    }
}
