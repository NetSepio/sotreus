package app.sotreus.edge.link

import app.sotreus.edge.protocol.EdgeCommands
import app.sotreus.edge.protocol.EdgeProtocol
import app.sotreus.edge.protocol.EdgeRecordCodec
import app.sotreus.edge.protocol.long

/**
 * Where a sync may move its cursor, and what it may confirm, given which records the phone has
 * committed (protocol §4 "Delete after sync"). Never ahead of the database: an uncommitted record,
 * or a corrupt one that is not yet allowed to be skipped, stops both.
 */
class SyncLedger(start: Long, private val skipCorrupt: (Long) -> Boolean = { false }) {
    var cursor: Long = start
        private set

    /** Highest seq confirmable so far (every received record up to it is committed). */
    var committedThrough: Long? = null
        private set

    data class Step(val cursor: Long, val committedThrough: Long?, val blocked: Boolean)

    fun afterPage(page: List<EdgeRecordCodec.Result>, committed: Set<Long>, next: Long): Step {
        var through: Long? = null
        var blockedAt: Long? = null
        var blocked = false
        for (r in page) {
            val seq = when (r) {
                is EdgeRecordCodec.Result.Ok -> r.record.header.seq
                is EdgeRecordCodec.Result.Corrupt -> r.seq
            }
            val passes = when (r) {
                is EdgeRecordCodec.Result.Ok -> seq in committed
                is EdgeRecordCodec.Result.Corrupt -> seq != null && skipCorrupt(seq)
            }
            if (!passes || seq == null) {
                blocked = true
                blockedAt = seq
                break
            }
            through = seq
        }
        cursor = when {
            !blocked -> maxOf(cursor, next)
            blockedAt != null -> maxOf(cursor, blockedAt)
            else -> maxOf(cursor, through?.plus(1) ?: cursor)
        }
        if (through != null) committedThrough = maxOf(committedThrough ?: through, through)
        return Step(cursor, through, blocked)
    }
}

/**
 * One sync run (protocol §2 `sync` / `sync_confirm`): fetch a page, let the caller store it, move the
 * cursor and confirm only what was committed, then the next page. Works over any [EdgeLink].
 */
object EdgeSyncLoop {
    data class Result(
        val cursor: Long,
        /** What the Edge reported erasing through (`deleted_through`), if anything was confirmed. */
        val deletedThrough: Long?,
        val received: Int,
        val corrupt: Int,
        /** False when the Edge does not know `sync_confirm` (older firmware). */
        val confirmSupported: Boolean,
    )

    /**
     * @param store commits a page to the database and returns the seqs now stored there.
     * @param saveCursor persists the cursor; called only after [store] returned.
     * @param confirm send `sync_confirm` after each committed page.
     */
    suspend fun run(
        link: EdgeLink,
        from: Long,
        store: suspend (List<EdgeRecordCodec.Result>) -> Set<Long>,
        saveCursor: suspend (Long) -> Unit,
        skipCorrupt: (Long) -> Boolean = { false },
        confirm: Boolean = true,
        pageSize: Int = EdgeProtocol.SYNC_PAGE,
    ): Result {
        val ledger = SyncLedger(from, skipCorrupt)
        var confirmSupported = confirm
        var confirmed: Long? = null
        var deletedThrough: Long? = null
        var received = 0
        var corrupt = 0
        var lastBlockedAt: Long? = null
        while (true) {
            val page = ArrayList<EdgeRecordCodec.Result>()
            val fetched = link.fetchRecords(ledger.cursor, pageSize) { page += it }
            received += fetched.received
            corrupt += fetched.corrupt
            val committed = store(page)
            val step = ledger.afterPage(page, committed, fetched.next)
            saveCursor(step.cursor)
            val through = ledger.committedThrough
            if (confirmSupported && through != null && (confirmed == null || through > confirmed)) {
                val r = link.request { EdgeCommands.syncConfirm(it, through) }
                when {
                    r.ok -> {
                        confirmed = through
                        deletedThrough = r.body.long("deleted_through") ?: through
                    }
                    r.error?.contains("unknown", ignoreCase = true) == true -> confirmSupported = false
                    // "busy erasing": the next page or the next sync confirms again.
                }
            }
            if (step.blocked) {
                // Stuck on the same record twice in a row: stop here; the next sync retries it.
                if (step.cursor == lastBlockedAt) break
                lastBlockedAt = step.cursor
                continue
            }
            lastBlockedAt = null
            if (!fetched.more || fetched.received == 0) break
        }
        return Result(ledger.cursor, deletedThrough, received, corrupt, confirmSupported)
    }
}

/**
 * When to send `stream_ack` during the hand-off: within [maxDelayMs] of the first unacknowledged
 * commit, or as soon as [maxRecords] are waiting. Cumulative, and only ever for committed seqs.
 */
class StreamAckPolicy(private val maxRecords: Int = 16, private val maxDelayMs: Long = 2_000) {
    private var committed: Long? = null
    private var acked: Long? = null
    private var waiting = 0
    private var firstWaitingAt: Long? = null

    val lastAcked: Long? get() = acked

    /** A record is committed. Returns the seq to acknowledge now, or null to wait. */
    @Synchronized
    fun committed(seq: Long, nowMs: Long): Long? {
        if (committed == null || seq > committed!!) committed = seq
        if (acked != null && seq <= acked!!) return null
        waiting++
        if (firstWaitingAt == null) firstWaitingAt = nowMs
        return if (waiting >= maxRecords) take() else null
    }

    /** Called on a timer: the seq to acknowledge if the delay ran out. */
    @Synchronized
    fun due(nowMs: Long): Long? {
        val since = firstWaitingAt ?: return null
        return if (nowMs - since >= maxDelayMs) take() else null
    }

    /** The seq to acknowledge now, without waiting (before the hand-off ends). */
    @Synchronized
    fun flush(): Long? = if (firstWaitingAt == null) null else take()

    private fun take(): Long? {
        val c = committed ?: return null
        waiting = 0
        firstWaitingAt = null
        if (acked != null && c <= acked!!) return null
        acked = c
        return c
    }

    @Synchronized
    fun reset() {
        committed = null
        acked = null
        waiting = 0
        firstWaitingAt = null
    }
}
