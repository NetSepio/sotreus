package app.sotreus.core.data.mesh

import app.sotreus.core.database.entity.MeshNodeEntity
import app.sotreus.edge.protocol.EdgeRecordHeader
import app.sotreus.edge.protocol.MeshTextRecord
import app.sotreus.edge.protocol.mesh.MeshDelivery
import app.sotreus.edge.protocol.mesh.MeshMessage
import app.sotreus.edge.protocol.mesh.MeshNode
import app.sotreus.edge.protocol.mesh.MeshNodeId
import app.sotreus.edge.protocol.mesh.MeshPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRowsTest {
    private val self = 0x5AC1E0D2L
    private val base = 0xA1B2C3D4L
    private val now = 1_762_400_000_000L

    @Test fun conversationsAreChannelsOrTheOtherNode() {
        assertEquals("ch:1", MeshRows.conversation(base, MeshNodeId.BROADCAST, 1, outgoing = false))
        assertEquals("dm:$base", MeshRows.conversation(base, self, 0, outgoing = false))
        assertEquals("dm:$base", MeshRows.conversation(self, base, 0, outgoing = true))
        assertEquals(1, MeshRows.channelOf("ch:1"))
        assertNull(MeshRows.channelOf("dm:5"))
        assertEquals(base, MeshRows.peerOf("dm:$base"))
    }

    @Test fun aRecordAndAnEventForTheSameMessageShareTheirKey() {
        val header = EdgeRecordHeader(seq = 120, type = 4, flags = EdgeRecordHeader.FLAG_TIME_VALID, boot = 3, firstMs = now - 5_000, lastMs = now - 5_000, bodyLength = 40, format = 1)
        val rec = MeshRows.fromRecord("e1", header, MeshTextRecord(base, MeshNodeId.BROADCAST, 77, 0, hopLimit = 1, hopStart = 3, rssi = -98, snr = 6.5f, sent = false, text = "Reached the ridge"), now - 5_000, now)
        val ev = MeshRows.fromMessage("e1", MeshMessage(77, base, MeshNodeId.BROADCAST, 0, false, false, null, 4, null, false, null, -98, 6.5, "Reached the ridge", "BASE"), now)
        assertEquals(rec.fromNode to rec.packetId, ev.fromNode to ev.packetId)
        assertEquals(rec.conversation, ev.conversation)
        assertEquals(2, rec.hops)
        assertEquals(120L, rec.seq)
        assertTrue(rec.timeFromEdge)
        // The event has no Edge time: it is dated from its age, and a record fills the rest later.
        assertFalse(ev.timeFromEdge)
        assertEquals(now - 4_000, ev.atMs)
        assertNull(ev.seq)
        assertFalse(rec.read)
    }

    @Test fun sentRecordsAreReadAndCarryNoSignal() {
        val header = EdgeRecordHeader(130, 4, 0, 3, 1_000, 1_000, 30, 1)
        val sent = MeshRows.fromRecord("e1", header, MeshTextRecord(self, base, 9, 0, 3, 3, 0, 0f, sent = true, text = "On my way"), null, now)
        assertTrue(sent.outgoing && sent.read)
        assertNull(sent.rssi)
        assertNull(sent.hops)
        assertEquals(MeshDelivery.SENT.wire, sent.delivery)
        // No valid or anchored time: this phone's receipt time, said so.
        assertEquals(now, sent.atMs)
        assertFalse(sent.timeFromEdge)
    }

    @Test fun aNodeReportKeepsWhatItLeavesOut() {
        val prev = MeshNodeEntity("e1", base, longName = "Base Camp", shortName = "BASE", lat = 12.98, lon = 77.6, precisionBits = 13, positionMs = now - 60_000, battery = 80, lastHeardMs = now - 60_000)
        val report = MeshNode(base, null, null, null, "CLIENT", true, null, favorite = true, ignored = false, lastHeardAgoS = 10, lastHeardUnixS = null,
            rssi = -90, snr = 5.0, hopsAway = 2, position = null, device = null, environment = null, neighbors = emptyList())
        val merged = MeshRows.node("e1", report, prev, now)
        assertEquals("Base Camp", merged.longName)
        assertEquals(12.98, merged.lat!!, 0.0)
        assertEquals(80, merged.battery)
        assertEquals(now - 10_000, merged.lastHeardMs)
        assertTrue(merged.favorite && merged.hasKey)

        val moved = MeshRows.node("e1", report.copy(position = MeshPosition(12.99, 77.61, 900, null, now / 1000)), prev, now)
        assertNull("an exact report replaces the old precision", moved.precisionBits)
        assertEquals(now, moved.positionMs)
    }

    @Test fun neighboursRoundTrip() {
        val n = MeshNode(base, null, null, null, null, false, null, false, false, null, null, null, null, null, null, null, null,
            listOf(app.sotreus.edge.protocol.mesh.MeshNeighbor(0x11112222L, 7.0), app.sotreus.edge.protocol.mesh.MeshNeighbor(3L, null)))
        val row = MeshRows.node("e1", n, null, now)
        assertEquals(listOf(0x11112222L to 7.0f, 3L to null), MeshRows.neighbors(row.neighbors))
    }
}
