package app.sotreus.edge.link

import app.sotreus.edge.protocol.EdgeCommands
import app.sotreus.edge.protocol.EdgeEvent
import app.sotreus.edge.protocol.EdgeRecordCodec
import app.sotreus.edge.protocol.NdjsonFramer
import app.sotreus.edge.protocol.RecordStreamAssembler
import app.sotreus.edge.protocol.long
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * [EdgeLink] over a [ByteTransport] carrying NDJSON (`control` / `events`) and back-to-back
 * 256-byte records (`records`), i.e. the Bluetooth link of protocol §1.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NdjsonEdgeLink(
    private val transport: ByteTransport,
    private val scope: CoroutineScope,
    override val kind: LinkKind = LinkKind.BLUETOOTH,
    /** A sync that goes quiet this long without records or `sync_done` has stalled. */
    private val syncIdleMs: Long = 15_000,
    /** Diagnostic lines (seqs and routing only, nothing about the radios). */
    private val trace: (String) -> Unit = {},
) : EdgeLink {
    private val framer = NdjsonFramer()
    private val assembler = RecordStreamAssembler()
    private val lock = Any()
    private var nextId = 1L
    private val pending = HashMap<Long, CompletableDeferred<EdgeEvent.Response>>()
    private val writes = Mutex()
    private val syncs = Mutex()

    @Volatile private var recordSink: Channel<ByteArray>? = null
    @Volatile private var liveFrom: Long? = null
    private val live = Channel<EdgeRecordCodec.Result>(Channel.UNLIMITED)
    override val liveRecords: kotlinx.coroutines.flow.Flow<EdgeRecordCodec.Result> = live.receiveAsFlow()

    override fun routeLiveFrom(seq: Long?) {
        liveFrom = seq
    }

    private val _events = MutableSharedFlow<EdgeEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<EdgeEvent> = _events.asSharedFlow()
    override val state: StateFlow<EdgeLinkState> get() = transport.state

    init {
        scope.launch { transport.eventChunks.collect { chunk -> framer.feed(chunk).forEach(::onLine) } }
        scope.launch {
            transport.recordChunks.collect { chunk ->
                if (chunk.size % EdgeRecordCodec.SIZE != 0) trace("records chunk of ${chunk.size} bytes")
                assembler.feed(chunk).forEach(::onRecord)
            }
        }
        scope.launch {
            transport.state.collect { s ->
                if (s !is EdgeLinkState.Connected) {
                    framer.reset()
                    assembler.reset()
                    failPending("link ${s::class.simpleName?.lowercase()}")
                }
            }
        }
    }

    /**
     * A hand-off's records (at or above [liveFrom]) go to [liveRecords]; a sync's go to the fetch in
     * progress.
     */
    private fun onRecord(bytes: ByteArray) {
        val threshold = liveFrom
        val sink = recordSink
        val seq = (bytes[0].toLong() and 0xFF) or ((bytes[1].toLong() and 0xFF) shl 8) or
            ((bytes[2].toLong() and 0xFF) shl 16) or ((bytes[3].toLong() and 0xFF) shl 24)
        when {
            threshold != null && seq >= threshold -> live.trySend(EdgeRecordCodec.decode(bytes))
            sink != null -> sink.trySend(bytes).also { if (threshold != null) trace("record $seq to the sync (live from $threshold)") }
            else -> trace("record $seq dropped (live from $threshold, no sync)") // late, after its fetch ended: the next sync fetches it again
        }
    }

    private fun onLine(line: String) {
        val ev = EdgeEvent.parse(line) ?: return
        if (ev is EdgeEvent.Response && ev.id != null) synchronized(lock) { pending.remove(ev.id) }?.complete(ev)
        _events.tryEmit(ev)
    }

    private fun failPending(reason: String) {
        val all = synchronized(lock) { pending.values.toList().also { pending.clear() } }
        all.forEach { it.completeExceptionally(EdgeRequestException(reason)) }
    }

    override suspend fun request(timeoutMs: Long, build: (id: Long) -> String): EdgeEvent.Response {
        if (transport.state.value !is EdgeLinkState.Connected) throw EdgeRequestException("not connected")
        val deferred = CompletableDeferred<EdgeEvent.Response>()
        val id = synchronized(lock) { (nextId++).also { pending[it] = deferred } }
        try {
            val bytes = NdjsonFramer.frame(build(id))
            writes.withLock { NdjsonFramer.chunks(bytes, transport.maxWrite).forEach { transport.write(it) } }
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    override suspend fun requestEcho(echoId: Long, line: String, timeoutMs: Long): EdgeEvent.Response {
        if (transport.state.value !is EdgeLinkState.Connected) throw EdgeRequestException("not connected")
        val deferred = CompletableDeferred<EdgeEvent.Response>()
        synchronized(lock) {
            if (echoId in pending) throw EdgeRequestException("a request for this node is still waiting")
            pending[echoId] = deferred
        }
        try {
            val bytes = NdjsonFramer.frame(line)
            writes.withLock { NdjsonFramer.chunks(bytes, transport.maxWrite).forEach { transport.write(it) } }
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            synchronized(lock) { if (pending[echoId] === deferred) pending.remove(echoId) }
        }
    }

    override suspend fun fetchRecords(from: Long, limit: Int, onRecord: suspend (EdgeRecordCodec.Result) -> Unit): FetchResult = syncs.withLock {
        val sink = Channel<ByteArray>(Channel.UNLIMITED)
        // A hand-off shares the byte stream: resetting mid-record would misalign it.
        if (liveFrom == null) assembler.reset()
        recordSink = sink
        val done = CompletableDeferred<EdgeEvent.SyncDone>()
        val watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) { done.complete(events.filterIsInstance<EdgeEvent.SyncDone>().first()) }
        try {
            val resp = request { EdgeCommands.sync(it, from, limit) }
            if (!resp.ok) throw EdgeRequestException(resp.error ?: "sync refused")
            val oldest = resp.body.long("oldest") ?: 0
            var received = 0
            var corrupt = 0
            var finished: EdgeEvent.SyncDone? = null
            suspend fun take(bytes: ByteArray) {
                val r = EdgeRecordCodec.decode(bytes)
                if (r is EdgeRecordCodec.Result.Corrupt) corrupt++
                received++
                onRecord(r)
            }
            while (true) {
                val f = finished
                if (f != null) {
                    // Records and sync_done travel on different characteristics: drain what is left.
                    if (received >= f.sent) break
                    // select with onTimeout, never a receive under withTimeout: a receive cancelled as a
                    // record is handed over loses it (kotlinx.coroutines "undelivered elements").
                    take(select<ByteArray?> { sink.onReceive { it }; onTimeout(DRAIN_MS) { null } } ?: break)
                    continue
                }
                val got = select<ByteArray?> {
                    sink.onReceive { it }
                    done.onAwait { finished = it; null }
                    onTimeout(syncIdleMs) { null }
                }
                if (got == null && finished == null) throw EdgeRequestException("sync stalled after $received records")
                if (got != null) take(got)
            }
            val f = finished!!
            if (!f.ok) throw EdgeRequestException("sync ended with an error after $received records")
            FetchResult(next = f.next, head = f.head, oldest = oldest, more = f.more, received = received, corrupt = corrupt)
        } finally {
            watcher.cancel()
            recordSink = null
            sink.close()
        }
    }

    override suspend fun linkRssi(): Int? = transport.readRssi()

    override suspend fun close() = transport.close()

    private companion object {
        const val DRAIN_MS = 1_500L
    }
}
