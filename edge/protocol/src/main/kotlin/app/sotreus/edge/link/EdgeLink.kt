package app.sotreus.edge.link

import app.sotreus.edge.protocol.EdgeEvent
import app.sotreus.edge.protocol.EdgeRecordCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** How the phone reaches the Edge (HANDOFF_V2_EDGE.md §3). */
enum class LinkKind {
    BLUETOOTH,

    /** The Edge's own access point (a burst for big syncs). */
    EDGE_WIFI,

    /** A shared network the Edge has joined (stationary at home). */
    SHARED_NETWORK,
}

sealed interface EdgeLinkState {
    data object Idle : EdgeLinkState
    data object Connecting : EdgeLinkState

    /** Android is asking for the code on the Edge's display. */
    data object Bonding : EdgeLinkState
    data class Connected(val mtu: Int) : EdgeLinkState

    /** The link dropped at [atMs]; a reconnect may follow. */
    data class Lost(val atMs: Long, val reason: String?) : EdgeLinkState
    /** [pairingLost]: this phone no longer holds the bond (Android can delete it); only pairing again helps. */
    data class Failed(val reason: String, val pairingLost: Boolean = false) : EdgeLinkState
}

class EdgeRequestException(message: String) : Exception(message)

/** Result of one `fetchRecords` page. */
data class FetchResult(
    /** Next seq to ask for. */
    val next: Long,
    val head: Long,
    val oldest: Long,
    val more: Boolean,
    val received: Int,
    val corrupt: Int,
)

/**
 * What the app needs from any link to an Edge, Bluetooth or Wi-Fi. Commands go out with an `id`
 * and the matching response comes back; events arrive on [events]; records arrive through
 * [fetchRecords]. Implementations never retry silently; callers decide.
 */
interface EdgeLink {
    val kind: LinkKind
    val state: StateFlow<EdgeLinkState>
    val events: SharedFlow<EdgeEvent>

    /** Sends the command built for a fresh id and waits for its response. */
    suspend fun request(timeoutMs: Long = DEFAULT_TIMEOUT_MS, build: (id: Long) -> String): EdgeEvent.Response

    /**
     * Sends [line], whose `id` is already [echoId], and waits for the response echoing it. For
     * commands whose own parameter is named `id` (`mesh_node` names its node there), so the Edge's
     * echo carries that value instead of a request number. Links that answer each request on its
     * own (HTTP) need no matching.
     */
    suspend fun requestEcho(echoId: Long, line: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): EdgeEvent.Response =
        request(timeoutMs) { line }

    /** Fetches records from [from], at most [limit], calling [onRecord] in seq order. */
    suspend fun fetchRecords(from: Long, limit: Int, onRecord: suspend (EdgeRecordCodec.Result) -> Unit): FetchResult

    /**
     * Records that arrive outside [fetchRecords]: the phone hand-off (`stream_start`). Empty for
     * links that cannot carry it (Wi-Fi).
     */
    val liveRecords: Flow<EdgeRecordCodec.Result> get() = kotlinx.coroutines.flow.emptyFlow()

    /**
     * While a hand-off runs, records with seq at or above [seq] are live even during a fetch (the
     * catch-up sync of what was stored before `stream_start`). Null ends the routing.
     */
    fun routeLiveFrom(seq: Long?) = Unit

    /** Signal strength of the link itself (Bluetooth only), used for "together". */
    suspend fun linkRssi(): Int?

    suspend fun close()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

/**
 * The raw byte pipes of a Bluetooth link (GATT): write to `control`, notifications from `events`
 * and `records`. [maxWrite] is the ATT MTU minus 3.
 */
interface ByteTransport {
    val state: StateFlow<EdgeLinkState>
    val eventChunks: Flow<ByteArray>
    val recordChunks: Flow<ByteArray>
    val maxWrite: Int
    suspend fun write(chunk: ByteArray)
    suspend fun readRssi(): Int?
    suspend fun close()
}
