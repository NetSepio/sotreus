package app.sotreus.edge.link

import app.sotreus.edge.fake.FakeEdge
import app.sotreus.edge.protocol.EdgeBodies
import app.sotreus.edge.protocol.EdgeRecordCodec
import app.sotreus.edge.protocol.EdgeRecordType
import app.sotreus.edge.protocol.long
import app.sotreus.edge.protocol.str
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Delete after sync and the phone hand-off (protocol §2, §4): nothing is confirmed, acknowledged or passed by the cursor before it is in the database. */
class DeleteAfterSyncTest {
    private fun TestScope.link(edge: FakeEdge) = NdjsonEdgeLink(edge, backgroundScope).also { runCurrent() }

    private fun FakeEdge.fill(n: Int) = (0 until n).map {
        addRecord(EdgeRecordType.WIFI_AP, EdgeBodies.ap("24:2F:D0:BD:0F:%02X".format(it % 256), 6, -60, ssid = "Net$it"), 1_000L * it, 1_000L * it + 500)
    }

    private fun seqs(page: List<EdgeRecordCodec.Result>) = page.mapNotNull { (it as? EdgeRecordCodec.Result.Ok)?.record?.header?.seq }.toSet()

    /** The phone's "database": what was committed, checked against every command the Edge receives. */
    /** [skipped]: records unreadable on the Edge itself (corrupt twice), which may be passed. */
    private class Db(val edge: FakeEdge, all: List<Long>, val skipped: Set<Long> = emptySet()) {
        val stored = HashSet<Long>()
        val everStored = all.toSet()
        var violations = ArrayList<String>()

        init {
            edge.onCommand = { req ->
                val seq = req.long("seq")
                when (req.str("cmd")) {
                    "sync_confirm", "stream_ack" -> {
                        val missing = everStored.filter { it <= seq!! && it !in stored && it !in skipped }
                        if (missing.isNotEmpty()) violations += "${req.str("cmd")} $seq before ${missing.take(3)} were stored"
                    }
                }
            }
        }

        fun checkCursor(cursor: Long) {
            val missing = everStored.filter { it < cursor && it !in stored && it !in skipped }
            if (missing.isNotEmpty()) violations += "cursor $cursor ahead of ${missing.take(3)}"
        }
    }

    @Test fun confirmationAndCursorNeverRunAheadOfTheDatabase() = runTest {
        val edge = FakeEdge()
        val db = Db(edge, edge.fill(1_200))
        val l = link(edge)
        var failOnce = true
        var cursor = 1L
        // First run: the database write of the second page fails.
        val first = runCatching {
            EdgeSyncLoop.run(l, cursor, store = { page ->
                if (db.stored.size >= 500 && failOnce) { failOnce = false; error("disk full") }
                db.stored += seqs(page); seqs(page)
            }, saveCursor = { cursor = it; db.checkCursor(it) })
        }
        assertTrue(first.isFailure)
        assertEquals(501L, cursor)
        assertEquals(listOf(500L), edge.confirms)
        // Second run resumes from the saved cursor and finishes.
        val done = EdgeSyncLoop.run(l, cursor, store = { page -> db.stored += seqs(page); seqs(page) }, saveCursor = { cursor = it; db.checkCursor(it) })
        assertEquals(emptyList<String>(), db.violations)
        assertEquals(1_201L, done.cursor)
        assertEquals(1_200L, done.deletedThrough)
        assertTrue(edge.records.isEmpty())
        assertEquals(listOf(500L, 1_000L, 1_200L), edge.confirms)
    }

    @Test fun aCorruptRecordBlocksConfirmationUntilSeenTwice() = runTest {
        val edge = FakeEdge()
        val db = Db(edge, edge.fill(30), skipped = setOf(10))
        edge.records[9][100] = (edge.records[9][100].toInt() xor 1).toByte() // seq 10, broken in flash
        val l = link(edge)
        val seen = HashMap<Long, Int>()
        var cursor = 1L
        val r = EdgeSyncLoop.run(
            l, cursor,
            store = { page -> db.stored += seqs(page); seqs(page) },
            saveCursor = { cursor = it; db.checkCursor(it) },
            skipCorrupt = { seq -> (seen.merge(seq, 1, Int::plus) ?: 0) >= 2 },
        )
        assertEquals(emptyList<String>(), db.violations)
        assertEquals(listOf(9L, 30L), edge.confirms)
        assertEquals(31L, r.cursor)
        assertFalse(10L in db.stored)
    }

    @Test fun olderFirmwareWithoutConfirmStillSyncs() = runTest {
        val edge = FakeEdge()
        edge.supportsDeleteAfterSync = false
        edge.fill(40)
        val l = link(edge)
        val r = EdgeSyncLoop.run(l, 1, store = { seqs(it) }, saveCursor = {}, pageSize = 10)
        assertFalse(r.confirmSupported)
        assertNull(r.deletedThrough)
        assertEquals(41L, r.cursor)
        assertEquals(40, edge.records.size)
    }

    @Test fun gapsInSequenceNumbersAreNormal() = runTest {
        val edge = FakeEdge()
        val a = edge.fill(10)
        edge.skipSeqs(5) // handed off to a phone, never stored
        val b = edge.fill(10)
        val db = Db(edge, a + b)
        val l = link(edge)
        val r = EdgeSyncLoop.run(l, 1, store = { page -> db.stored += seqs(page); seqs(page) }, saveCursor = { db.checkCursor(it) })
        assertEquals(26L, r.cursor)
        assertEquals(listOf(25L), edge.confirms)
        assertEquals(emptyList<String>(), db.violations)
    }

    // ---- Ack policy -------------------------------------------------------------------------------

    @Test fun acksEverySixteenRecordsOrTwoSeconds() {
        val p = StreamAckPolicy()
        (1L..15L).forEach { assertNull(p.committed(it, 0)) }
        assertEquals(16L, p.committed(16, 10))
        assertNull(p.committed(17, 100))
        assertNull(p.due(2_099))
        assertEquals(17L, p.due(2_100))
        assertNull(p.due(9_000))
        // Old or repeated seqs never trigger or lower an ack.
        assertNull(p.committed(5, 9_000))
        assertNull(p.due(20_000))
        assertEquals(17L, p.lastAcked)
    }

    // ---- Hand-off ---------------------------------------------------------------------------------

    @Test fun handOffAcksOnlyCommittedRecords() = runTest {
        val edge = FakeEdge()
        edge.fill(5)
        val l = link(edge)
        val db = Db(edge, emptyList())
        val h = EdgeHandOff(l, backgroundScope, clock = { testScheduler.currentTime })
        val started = h.start(store = { batch -> val s = seqs(batch); db.stored += s; s })
        assertEquals(EdgeHandOff.Start.Started(5), started)
        val live = edge.fill(20)
        val db2 = Db(edge, live).also { it.stored += db.stored }
        edge.onCommand = { req ->
            if (req.str("cmd") == "stream_ack") {
                val missing = live.filter { it <= req.long("seq")!! && it !in db.stored }
                if (missing.isNotEmpty()) db2.violations += "ack before $missing"
            }
        }
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(emptyList<String>(), db2.violations)
        assertEquals(live.toSet(), db.stored)
        assertEquals(listOf(21L, 25L), edge.acks)
        assertTrue(edge.unacked.isEmpty())
        assertEquals(5, edge.records.size) // nothing handed off was stored on the Edge
    }

    /** Records spaced exactly one batch wait apart: none may vanish between the link and the store. */
    @Test fun liveRecordsAtTheBatchWaitAreNeverLost() = runTest {
        val edge = FakeEdge()
        val l = link(edge)
        val stored = HashSet<Long>()
        var ended: String? = null
        val h = EdgeHandOff(l, backgroundScope, clock = { testScheduler.currentTime })
        h.start(store = { batch -> val s = seqs(batch); stored += s; s }, onStopped = { ended = it })
        val live = ArrayList<Long>()
        backgroundScope.launch {
            repeat(60) {
                live += edge.fill(1)
                kotlinx.coroutines.delay(150)
            }
        }
        advanceTimeBy(60 * 150L + 3_000)
        runCurrent()
        assertNull(ended)
        assertEquals(live.toSet(), stored)
        assertEquals(live.last(), edge.acks.last())
    }

    @Test fun aRecordNotSavedEndsTheHandOffAndStaysOnTheEdge() = runTest {
        val edge = FakeEdge()
        val l = link(edge)
        var stoppedWith: String? = "not stopped"
        val h = EdgeHandOff(l, backgroundScope, clock = { testScheduler.currentTime })
        h.start(store = { emptySet() }, onStopped = { stoppedWith = it }) // the database refuses everything
        val live = edge.fill(3)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(edge.acks.isEmpty())
        assertEquals("a record was not saved", stoppedWith)
        assertFalse(h.active)
        assertFalse(edge.streaming)
        assertEquals(live, edge.records.map { FakeEdge.seqOf(it) })
    }

    @Test fun aRecordLostInTransitIsNeverAcknowledged() = runTest {
        val edge = FakeEdge()
        edge.fill(5)
        val l = link(edge)
        var stoppedWith: String? = null
        var through: Long? = null
        val h = EdgeHandOff(l, backgroundScope, clock = { testScheduler.currentTime })
        h.start(store = { seqs(it) }, onCommitted = { through = it }, onStopped = { stoppedWith = it })
        edge.loseInTransit = { it == 8L }
        edge.fill(10) // seqs 6..15; 8 never reaches the phone
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(7L), edge.acks)
        assertEquals(7L, through)
        assertEquals("a record did not arrive", stoppedWith)
        // The Edge kept everything from the missing record on, for the next sync.
        assertEquals((1L..5L) + (8L..15L), edge.records.map { FakeEdge.seqOf(it) })
    }

    @Test fun theEdgeEndingTheHandOffIsReported() = runTest {
        val edge = FakeEdge()
        val l = link(edge)
        var stoppedWith: String? = null
        val h = EdgeHandOff(l, backgroundScope, clock = { testScheduler.currentTime })
        h.start(store = { seqs(it) }, onStopped = { stoppedWith = it })
        edge.fill(3)
        runCurrent()
        edge.stopStream("no confirmation from the app")
        runCurrent()
        assertEquals("no confirmation from the app", stoppedWith)
        assertFalse(h.active)
    }

    @Test fun catchUpSyncAndLiveRecordsStayApart() = runTest {
        val edge = FakeEdge()
        val before = edge.fill(10)
        val l = link(edge)
        val live = ArrayList<Long>()
        val h = EdgeHandOff(l, backgroundScope, clock = { testScheduler.currentTime })
        val start = h.start(store = { batch -> seqs(batch).also { live += it } }) as EdgeHandOff.Start.Started
        // Catch-up of what was stored before stream_start, while a new record is handed off.
        val synced = ArrayList<Long>()
        val fetch = async { l.fetchRecords(1, 500) { r -> synced += (r as EdgeRecordCodec.Result.Ok).record.header.seq } }
        runCurrent()
        val handed = edge.fill(1)
        runCurrent()
        fetch.await()
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(10L, start.storedHead)
        assertEquals(before, synced)
        assertEquals(handed, live)
    }

    @Test fun aRecordSentBeforeTheStartResponseIsKept() = runTest {
        val edge = FakeEdge()
        edge.fill(4)
        val l = link(edge)
        val stored = ArrayList<Long>()
        edge.recordBeforeStartResponse = { edge.recordBeforeStartResponse = null; edge.fill(1) }
        val h = EdgeHandOff(l, backgroundScope, clock = { testScheduler.currentTime })
        val start = h.start(store = { seqs(it).also { s -> stored += s } }, from = 5) as EdgeHandOff.Start.Started
        runCurrent()
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(4L, start.storedHead)
        assertEquals(listOf(5L), stored)
        assertEquals(listOf(5L), edge.acks)
        assertTrue(h.active)
    }

    @Test fun theFirstLiveSeqCanJumpPastStoredHead() {
        for (reports in listOf(true, false)) runTest {
            val edge = FakeEdge()
            edge.reportsFirstSeq = reports
            edge.fill(5)
            edge.skipSeqs(1_000) // the seq floor after a restart, or records handed off before
            val l = link(edge)
            var stoppedWith: String? = null
            val stored = ArrayList<Long>()
            val h = EdgeHandOff(l, backgroundScope, clock = { testScheduler.currentTime })
            h.start(store = { seqs(it).also { s -> stored += s } }, onStopped = { stoppedWith = it }, from = 6)
            val live = edge.fill(3)
            runCurrent()
            advanceTimeBy(2_500)
            runCurrent()
            assertEquals("first_seq reported: $reports", null, stoppedWith)
            assertEquals(live, stored)
            assertEquals(listOf(live.last()), edge.acks)
            assertTrue(h.active)
        }
    }

    @Test fun olderFirmwareHasNoHandOff() = runTest {
        val edge = FakeEdge()
        edge.supportsDeleteAfterSync = false
        val l = link(edge)
        val h = EdgeHandOff(l, backgroundScope)
        assertEquals(EdgeHandOff.Start.Unsupported, h.start(store = { emptySet() }))
        assertFalse(h.active)
        launch { }.join()
        if (edge.streaming) fail("the Edge should not be streaming")
    }
}
