package app.sotreus.edge.protocol

/**
 * Wall time for Edge records (protocol §4 "Time"). A record with TIME_VALID carries Unix ms. One
 * without it carries ms since its boot; it gets a wall time only if this phone anchored that
 * boot (saw it while connected). Otherwise the time is not known and the record is never shown
 * as fresh or placed on a timeline by guesswork.
 */
object EdgeTime {
    /** Unix ms at which boot started, from a `status` read at [phoneNowMs]. */
    fun anchor(uptimeMs: Long, phoneNowMs: Long): Long = phoneNowMs - uptimeMs

    /** Anchors are keyed by the low 16 bits of the boot counter, as records carry it. */
    fun bootKey(boot: Long): Int = (boot and 0xFFFF).toInt()

    /** `first..last` in Unix ms, or null when the time is not known. */
    fun wallWindow(header: EdgeRecordHeader, anchors: Map<Int, Long>): LongRange? {
        if (header.timeValid) return header.firstMs..maxOf(header.firstMs, header.lastMs)
        val base = anchors[header.boot] ?: return null
        return (base + header.firstMs)..(base + maxOf(header.firstMs, header.lastMs))
    }

    /**
     * Edge clock minus phone clock, in ms (positive = Edge ahead). [edgeUnixMs] is `status.unix_ms`
     * from a request sent at [sentAtMs] and answered at [receivedAtMs]; half the round trip is
     * assumed to have passed before the Edge read its clock.
     */
    fun clockOffset(edgeUnixMs: Long, sentAtMs: Long, receivedAtMs: Long): Long =
        edgeUnixMs - (sentAtMs + (receivedAtMs - sentAtMs) / 2)

    /** Time to resend `set_time` (HANDOFF_V2_EDGE.md §4.4). */
    fun needsSetTime(offsetMs: Long?, limitMs: Long = 1_000): Boolean = offsetMs == null || kotlin.math.abs(offsetMs) > limitMs
}

/** Where a sync resumes and what was lost to the ring buffer (protocol §4, `sync`). */
data class SyncPlan(
    /** First seq to request. */
    val from: Long,
    /** Records overwritten on the Edge before this phone fetched them. */
    val lost: Long,
    val upToDate: Boolean,
)

object SyncPlanner {
    /**
     * [cursor] is the next seq this phone wants (it only advances after a page is stored).
     * [oldest] and [head] come from the `sync` response or `status.log`; head 0 = empty log.
     * Sequence numbers have gaps (records handed off to the phone, the seq floor after a restart,
     * records deleted after sync), so a jump to [oldest] counts as lost only when [ringFull]: the
     * ring overwrites records only once it is full.
     */
    fun plan(cursor: Long, oldest: Long, head: Long, ringFull: Boolean = true): SyncPlan = when {
        head == 0L || cursor > head -> SyncPlan(from = cursor, lost = 0, upToDate = true)
        oldest > cursor -> SyncPlan(from = oldest, lost = if (ringFull) oldest - cursor else 0, upToDate = false)
        else -> SyncPlan(from = cursor, lost = 0, upToDate = false)
    }

    /** Cursor for "start from now" on first pairing: skip what is already stored. */
    fun startFromNow(head: Long): Long = head + 1

    /** Cursor for "bring them in": everything still stored. */
    fun importAll(oldest: Long): Long = if (oldest == 0L) 1 else oldest

    /** The next cursor after storing a page that ended at [lastStoredSeq]. */
    fun advance(cursor: Long, lastStoredSeq: Long?): Long = if (lastStoredSeq == null) cursor else maxOf(cursor, lastStoredSeq + 1)
}
