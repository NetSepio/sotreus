package app.sotreus.edge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTimeAndSyncTest {
    private fun header(timeValid: Boolean, boot: Int, first: Long, last: Long) =
        EdgeRecordHeader(1, 1, if (timeValid) 1 else 0, boot, first, last, 80, 1)

    @Test fun validTimeIsUsedAsIs() {
        assertEquals(1000L..2000L, EdgeTime.wallWindow(header(true, 3, 1000, 2000), emptyMap()))
    }

    @Test fun uptimeRecordsNeedAnAnchorForTheirBoot() {
        val anchor = EdgeTime.anchor(uptimeMs = 60_000, phoneNowMs = 1_762_400_060_000)
        assertEquals(1_762_400_000_000, anchor)
        val anchors = mapOf(EdgeTime.bootKey(12) to anchor)
        assertEquals(1_762_400_005_000L..1_762_400_065_000L, EdgeTime.wallWindow(header(false, 12, 5_000, 65_000), anchors))
        // Another boot: time not known.
        assertNull(EdgeTime.wallWindow(header(false, 11, 5_000, 65_000), anchors))
    }

    @Test fun bootKeyUsesTheLow16Bits() {
        assertEquals(0x0001, EdgeTime.bootKey(0x10001))
    }

    @Test fun lastBeforeFirstIsClamped() {
        assertEquals(1000L..1000L, EdgeTime.wallWindow(header(true, 1, 1000, 900), emptyMap()))
    }

    @Test fun clockOffsetUsesHalfTheRoundTrip() {
        // Sent at 1000, answered at 1200: the Edge read its clock at ~1100.
        assertEquals(400L, EdgeTime.clockOffset(edgeUnixMs = 1500, sentAtMs = 1000, receivedAtMs = 1200))
        assertTrue(EdgeTime.needsSetTime(null))
        assertTrue(EdgeTime.needsSetTime(-1500))
        assertFalse(EdgeTime.needsSetTime(400))
    }

    @Test fun syncPlans() {
        assertEquals(SyncPlan(from = 10, lost = 0, upToDate = true), SyncPlanner.plan(cursor = 10, oldest = 0, head = 0))
        assertEquals(SyncPlan(from = 231, lost = 0, upToDate = true), SyncPlanner.plan(cursor = 231, oldest = 1, head = 230))
        assertEquals(SyncPlan(from = 100, lost = 0, upToDate = false), SyncPlanner.plan(cursor = 100, oldest = 1, head = 230))
        // The ring wrapped past the cursor while the phone was away.
        assertEquals(SyncPlan(from = 1500, lost = 1400, upToDate = false), SyncPlanner.plan(cursor = 100, oldest = 1500, head = 16000))
        // A gap while the ring is not full is records sent to the phone or deleted, not lost.
        assertEquals(SyncPlan(from = 1500, lost = 0, upToDate = false), SyncPlanner.plan(cursor = 100, oldest = 1500, head = 1600, ringFull = false))
        assertEquals(231L, SyncPlanner.startFromNow(230))
        assertEquals(1L, SyncPlanner.importAll(0))
        assertEquals(1500L, SyncPlanner.importAll(1500))
        assertEquals(501L, SyncPlanner.advance(1, 500))
        assertEquals(600L, SyncPlanner.advance(600, 500))
        assertEquals(7L, SyncPlanner.advance(7, null))
    }
}
