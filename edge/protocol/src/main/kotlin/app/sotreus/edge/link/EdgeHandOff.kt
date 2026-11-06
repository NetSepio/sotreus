package app.sotreus.edge.link

import app.sotreus.edge.protocol.EdgeCommands
import app.sotreus.edge.protocol.EdgeEvent
import app.sotreus.edge.protocol.EdgeRecordCodec
import app.sotreus.edge.protocol.long
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone hand-off while connected (protocol §4 "Phone hand-off"): after `stream_start` the Edge
 * sends each new record to the phone instead of storing it, numbered one after another from
 * `stored_head + 1`. Records are committed through [start]'s `store` and acknowledged with
 * `stream_ack` per [StreamAckPolicy].
 *
 * `stream_ack` is cumulative, so only an unbroken run of committed records is acknowledged. A
 * record that is missing (it went to flash, or was lost in transit), unreadable, or not committed
 * ends the hand-off with `stream_stop`: the Edge then stores everything not acknowledged, and a
 * sync brings it in. Either way the caller hears of the end through `onStopped`.
 */
class EdgeHandOff(
    private val link: EdgeLink,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val policy: StreamAckPolicy = StreamAckPolicy(),
) {
    sealed interface Start {
        /** Running; records up to [storedHead] were stored before and still need a sync. */
        data class Started(val storedHead: Long) : Start

        /** The Edge does not know `stream_start` (older firmware). */
        data object Unsupported : Start
        data class Refused(val reason: String) : Start
    }

    @Volatile var active: Boolean = false
        private set

    /** Every handed-off record up to here is committed (cumulative), or null before the first. */
    @Volatile var committedThrough: Long? = null
        private set

    private val acks = Mutex()
    private var jobs: List<Job> = emptyList()
    private var ended = AtomicBoolean(true)
    private var onStopped: suspend (String?) -> Unit = {}

    /**
     * Starts the hand-off and its workers. [store] commits a batch and returns the seqs now in the
     * database; [onCommitted] runs with [committedThrough] whenever it moves; [onStopped] runs once
     * when the hand-off ends for any reason other than [stop], with the reason.
     *
     * [from] is the sync cursor: no sync runs now, so any record at or above it that arrives before
     * the `stream_start` response is already handed off and is kept for the workers.
     */
    suspend fun start(
        store: suspend (List<EdgeRecordCodec.Result>) -> Set<Long>,
        onCommitted: suspend (Long) -> Unit = {},
        onStopped: suspend (String?) -> Unit = {},
        from: Long = 1,
    ): Start {
        if (active) stop()
        link.routeLiveFrom(from)
        val r = try {
            link.request { EdgeCommands.streamStart(it) }
        } catch (e: Exception) {
            link.routeLiveFrom(null)
            throw e
        }
        if (!r.ok) {
            link.routeLiveFrom(null)
            val e = r.error.orEmpty()
            return if (e.contains("unknown", ignoreCase = true)) Start.Unsupported else Start.Refused(e)
        }
        val storedHead = r.body.long("stored_head") ?: 0
        // The seq the first handed-off record carries, set under the Edge's numbering lock. It can be
        // well above stored_head + 1 (the seq floor after a restart, records handed off before).
        // Older firmware omits it: then the first record above stored_head starts the run, since
        // every stored record is at or below stored_head.
        val firstSeq = r.body.long("first_seq")
        link.routeLiveFrom(firstSeq ?: (storedHead + 1))
        policy.reset()
        committedThrough = null
        this.onStopped = onStopped
        ended = AtomicBoolean(false)
        active = true
        var expected: Long? = firstSeq
        val batches = Channel<EdgeRecordCodec.Result>(Channel.UNLIMITED)
        jobs = listOf(
            scope.launch { link.liveRecords.collect { batches.send(it) } },
            scope.launch {
                while (isActive && active) {
                    val batch = ArrayList<EdgeRecordCodec.Result>()
                    batch += batches.receive()
                    // Collect what arrives close together, up to one ack's worth.
                    while (batch.size < BATCH_MAX) batch += withTimeoutOrNull(BATCH_WAIT_MS) { batches.receive() } ?: break
                    val floor = expected ?: (storedHead + 1)
                    val fresh = batch.filter { seqOf(it)?.let { s -> s >= floor } ?: true }
                    if (fresh.isEmpty()) continue // repeats of records already handled
                    val committed = try {
                        store(fresh)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptySet()
                    }
                    val now = clock()
                    var ack: Long? = null
                    var hole: String? = null
                    for (r in fresh.sortedBy { seqOf(it) ?: Long.MAX_VALUE }) {
                        val seq = seqOf(r)
                        val want = expected ?: seq
                        when {
                            r !is EdgeRecordCodec.Result.Ok -> hole = "a record arrived unreadable"
                            seq != want -> hole = "a record did not arrive"
                            seq !in committed -> hole = "a record was not saved"
                        }
                        if (hole != null) break
                        policy.committed(seq!!, now)?.let { ack = it }
                        expected = seq + 1
                    }
                    ack?.let { send(it) }
                    val through = expected?.minus(1)
                    if (through != null && through > (committedThrough ?: storedHead)) {
                        committedThrough = through
                        onCommitted(through)
                    }
                    if (hole != null) {
                        // Acknowledge the unbroken run, then let the Edge store the rest.
                        policy.flush()?.let { send(it) }
                        end(hole, sendStop = true, self = currentCoroutineContext()[Job])
                    }
                }
            },
            scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    policy.due(clock())?.let { send(it) }
                }
            },
            scope.launch {
                val stop = link.events.filterIsInstance<EdgeEvent.Stream>().first { !it.started }
                end(stop.reason, sendStop = false, self = currentCoroutineContext()[Job])
            },
        )
        return Start.Started(storedHead)
    }

    private suspend fun send(seq: Long) = acks.withLock {
        runCatching { link.request { EdgeCommands.streamAck(it, seq) } }
    }

    /** Ends the hand-off on the phone side without calling `onStopped`; the Edge stores what was not acknowledged. */
    suspend fun stop() {
        if (!ended.compareAndSet(false, true)) return
        finish(except = null)
        withContext(NonCancellable) { runCatching { link.request { EdgeCommands.streamStop(it) } } }
    }

    /** The link went away: the Edge stores what was not acknowledged by itself. */
    fun linkDown() {
        if (!ended.compareAndSet(false, true)) return
        finish(except = null)
    }

    private suspend fun end(reason: String?, sendStop: Boolean, self: Job?) {
        if (!ended.compareAndSet(false, true)) return
        finish(except = self)
        if (sendStop) withContext(NonCancellable) { runCatching { link.request { EdgeCommands.streamStop(it) } } }
        onStopped(reason)
    }

    private fun finish(except: Job?) {
        active = false
        link.routeLiveFrom(null)
        val current = jobs
        jobs = emptyList()
        current.forEach { if (it !== except) it.cancel() }
    }

    private fun seqOf(r: EdgeRecordCodec.Result): Long? = when (r) {
        is EdgeRecordCodec.Result.Ok -> r.record.header.seq
        is EdgeRecordCodec.Result.Corrupt -> r.seq
    }

    private companion object {
        const val BATCH_MAX = 16
        const val BATCH_WAIT_MS = 150L
        const val TICK_MS = 500L
    }
}
