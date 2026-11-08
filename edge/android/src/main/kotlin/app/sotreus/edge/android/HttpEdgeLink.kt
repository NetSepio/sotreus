package app.sotreus.edge.android

import app.sotreus.edge.link.EdgeLink
import app.sotreus.edge.link.EdgeLinkState
import app.sotreus.edge.link.EdgeRequestException
import app.sotreus.edge.link.FetchResult
import app.sotreus.edge.link.LinkKind
import app.sotreus.edge.protocol.EdgeEvent
import app.sotreus.edge.protocol.EdgeJson
import app.sotreus.edge.protocol.EdgeProtocol
import app.sotreus.edge.protocol.EdgeRecordCodec
import app.sotreus.edge.protocol.bool
import app.sotreus.edge.protocol.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * [EdgeLink] over the Edge's HTTP API (protocol §1 "HTTP and WebSocket"): `POST /api/login` with
 * the PIN for a bearer token, commands through `POST /api/cmd`, records through
 * `GET /api/records?fmt=bin`. Plain HTTP (the Edge has no TLS), so it is used only on the Edge's
 * own network or a shared network the owner chose, and only for syncing. Tokens live in the
 * Edge's RAM; a 401 logs in again once.
 */
class HttpEdgeLink(
    private val baseUrl: String,
    client: OkHttpClient,
    private val pin: String,
    override val kind: LinkKind,
    private val clock: () -> Long = System::currentTimeMillis,
) : EdgeLink {
    private val http = client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow<EdgeLinkState>(EdgeLinkState.Idle)
    override val state: StateFlow<EdgeLinkState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<EdgeEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<EdgeEvent> = _events.asSharedFlow()
    @Volatile private var token: String? = null

    /** Logs in. False on a wrong PIN, a lockout or no route to the Edge. */
    suspend fun connect(tzOffsetMin: Int): Boolean = withContext(Dispatchers.IO) {
        _state.value = EdgeLinkState.Connecting
        val ok = runCatching { login(tzOffsetMin) }.getOrElse { _state.value = EdgeLinkState.Failed(it.message ?: "no route to the Edge"); false }
        if (ok) _state.value = EdgeLinkState.Connected(0)
        ok
    }

    private fun login(tzOffsetMin: Int): Boolean {
        val body = JsonObject(mapOf("pin" to JsonPrimitive(pin), "unix_ms" to JsonPrimitive(clock()), "tz_offset_min" to JsonPrimitive(tzOffsetMin)))
        http.newCall(Request.Builder().url("$baseUrl/api/login").post(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
            val o = runCatching { EdgeJson.parseToJsonElement(r.body.string()) as JsonObject }.getOrNull()
            if (!r.isSuccessful || o?.bool("ok") != true) {
                _state.value = EdgeLinkState.Failed(o?.str("error") ?: "login refused (${r.code})")
                return false
            }
            token = o.str("token")
            return token != null
        }
    }

    override suspend fun request(timeoutMs: Long, build: (id: Long) -> String): EdgeEvent.Response = withContext(Dispatchers.IO) {
        val payload = build(1)
        fun call() = http.newCall(
            Request.Builder().url("$baseUrl/api/cmd").header("Authorization", "Bearer ${token ?: ""}").post(payload.toRequestBody(JSON)).build(),
        ).execute()
        var r = call()
        if (r.code == 401) {
            r.close()
            if (!login(0)) throw EdgeRequestException("login refused")
            r = call()
        }
        r.use {
            val o = runCatching { EdgeJson.parseToJsonElement(it.body.string()) as JsonObject }.getOrNull() ?: throw EdgeRequestException("bad response (${it.code})")
            (EdgeEvent.parse(o) as? EdgeEvent.Response) ?: EdgeEvent.Response(null, o.bool("ok") == true, o.str("error"), o)
        }
    }

    override suspend fun fetchRecords(from: Long, limit: Int, onRecord: suspend (EdgeRecordCodec.Result) -> Unit): FetchResult {
        val (bytes, head, oldest) = withContext(Dispatchers.IO) {
            fun call() = http.newCall(
                Request.Builder().url("$baseUrl/api/records?from=$from&limit=${limit.coerceIn(1, MAX_PAGE)}&fmt=bin")
                    .header("Authorization", "Bearer ${token ?: ""}").get().build(),
            ).execute()
            var r = call()
            if (r.code == 401) {
                r.close()
                if (!login(0)) throw EdgeRequestException("login refused")
                r = call()
            }
            r.use {
                if (!it.isSuccessful) throw EdgeRequestException("records refused (${it.code})")
                Triple(it.body.bytes(), it.header("X-Edge-Head")?.toLongOrNull() ?: 0, it.header("X-Edge-Oldest")?.toLongOrNull() ?: 0)
            }
        }
        var received = 0
        var corrupt = 0
        var last: Long? = null
        var at = 0
        while (at + EdgeProtocol.RECORD_SIZE <= bytes.size) {
            val r = EdgeRecordCodec.decode(bytes, at)
            when (r) {
                is EdgeRecordCodec.Result.Ok -> last = r.record.header.seq
                is EdgeRecordCodec.Result.Corrupt -> { corrupt++; r.seq?.let { last = maxOf(last ?: 0, it) } }
            }
            received++
            onRecord(r)
            at += EdgeProtocol.RECORD_SIZE
        }
        val next = last?.let { it + 1 } ?: from
        return FetchResult(next = next, head = head, oldest = oldest, more = received > 0 && next <= head, received = received, corrupt = corrupt)
    }

    override suspend fun linkRssi(): Int? = null

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url("$baseUrl/api/logout").header("Authorization", "Bearer ${token ?: ""}").post(ByteArray(0).toRequestBody()).build()).execute().close()
            }
        }
        token = null
        _state.value = EdgeLinkState.Idle
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_PAGE = 5_000
    }
}
