package app.sotreus.edge.link

import app.sotreus.edge.fake.FakeEdge
import app.sotreus.edge.protocol.DeploymentMode
import app.sotreus.edge.protocol.EdgeBodies
import app.sotreus.edge.protocol.EdgeEvent
import app.sotreus.edge.protocol.EdgeMode
import app.sotreus.edge.protocol.EdgePlacement
import app.sotreus.edge.protocol.EdgeRecordCodec
import app.sotreus.edge.protocol.EdgeRecordType
import app.sotreus.edge.protocol.SightingKind
import app.sotreus.edge.protocol.SyncPlanner
import app.sotreus.edge.protocol.WifiApSighting
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NdjsonEdgeLinkTest {
    private fun TestScope.link(edge: FakeEdge) = NdjsonEdgeLink(edge, backgroundScope).also { runCurrent() }

    private fun FakeEdge.fill(n: Int) = repeat(n) {
        addRecord(EdgeRecordType.WIFI_AP, EdgeBodies.ap("24:2F:D0:BD:0F:%02X".format(it % 256), 6, -60 - it % 30, ssid = "Net$it"), 1_000L * it, 1_000L * it + 500)
    }

    @Test fun infoStatusAndConfig() = runTest {
        val edge = FakeEdge(phoneNowMs = { 1_762_400_000_000 })
        val l = link(edge)
        val info = l.info()
        assertEquals("96FF", info.suffix)
        assertTrue(info.caps.liveSightings)
        assertEquals(EdgeMode.AWARENESS, l.status().mode)
        assertEquals(DeploymentMode.TRAVEL, l.config().placement!!.mode)
    }

    @Test fun responsesMatchTheirIdsWithEventsInBetween() = runTest {
        val edge = FakeEdge()
        val l = link(edge)
        val seen = async { l.events.filterIsInstance<EdgeEvent.Seen>().first() }
        runCurrent()
        edge.emit(FakeEdge.obj("ev" to "seen", "kind" to "ble", "addr" to "c4:5b:be:01:02:03", "rssi" to -61, "uptime_ms" to 5))
        val a = async { l.status() }
        val b = async { l.info() }
        assertEquals("C4:5B:BE:01:02:03", seen.await().address)
        assertEquals("9f3c00aa11bb22cc", b.await().deviceId)
        assertEquals(EdgeMode.AWARENESS, a.await().mode)
        val ids = edge.received.map { Regex("\"id\":(\\d+)").find(it)!!.groupValues[1] }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun commandsAreChunkedToTheMtu() = runTest {
        val edge = FakeEdge(mtu = 23)
        val l = link(edge)
        val c = l.setConfig(JsonObject(mapOf("placement" to EdgePlacement(DeploymentMode.STATIONARY, 12.9716, 77.5946, 8f, 1, "phone").toJson())))
        assertEquals(DeploymentMode.STATIONARY, c.placement!!.mode)
        assertEquals(12.9716, c.placement!!.lat!!, 1e-9)
        val cleared = l.setConfig(JsonObject(mapOf("placement" to JsonObject(mapOf("lat" to JsonNull)))))
        assertFalse(cleared.placement!!.positioned)
        assertEquals(DeploymentMode.STATIONARY, cleared.placement!!.mode)
    }

    @Test fun syncPagesThroughTheLog() = runTest {
        val edge = FakeEdge()
        edge.fill(1_200)
        val l = link(edge)
        val got = ArrayList<Long>()
        var cursor = SyncPlanner.importAll(edge.oldest)
        do {
            val page = l.fetchRecords(cursor, 500) { r -> got += (r as EdgeRecordCodec.Result.Ok).record.header.seq }
            cursor = page.next
        } while (page.more)
        assertEquals((1L..1_200L).toList(), got)
        assertEquals(1_201L, cursor)
        val empty = l.fetchRecords(cursor, 500) { fail("no records expected") }
        assertFalse(empty.more)
        assertEquals(0, empty.received)
    }

    @Test fun recordsSurviveSmallMtuAndCorruptOnesAreCounted() = runTest {
        val edge = FakeEdge(mtu = 23)
        edge.fill(5)
        edge.records[2][100] = (edge.records[2][100].toInt() xor 1).toByte()
        val l = link(edge)
        val ok = ArrayList<WifiApSighting>()
        val page = l.fetchRecords(1, 500) { r -> if (r is EdgeRecordCodec.Result.Ok) ok += r.record.body as WifiApSighting }
        assertEquals(5, page.received)
        assertEquals(1, page.corrupt)
        assertEquals(listOf("Net0", "Net1", "Net3", "Net4"), ok.map { it.ssid })
    }

    @Test fun aStalledSyncFails() = runTest {
        val edge = FakeEdge()
        edge.fill(20)
        edge.dropRecordsAfter = 10
        val l = link(edge)
        try {
            l.fetchRecords(1, 500) {}
            fail("expected a stall")
        } catch (e: EdgeRequestException) {
            assertTrue(e.message!!.contains("stalled after 10"))
        }
    }

    @Test fun aDroppedLinkFailsPendingRequests() = runTest {
        val edge = FakeEdge()
        val l = link(edge)
        edge.mute = true
        val pending = async { runCatching { l.status() } }
        runCurrent()
        edge.drop()
        runCurrent()
        assertTrue(pending.await().exceptionOrNull() is EdgeRequestException)
        try {
            l.status()
            fail("not connected")
        } catch (_: EdgeRequestException) {
        }
    }

    @Test fun requestsTimeOut() = runTest {
        val edge = FakeEdge()
        val l = link(edge)
        edge.mute = true
        assertTrue(runCatching { l.request(timeoutMs = 2_000) { "{\"cmd\":\"status\",\"id\":$it}" } }.isFailure)
    }

    @Test fun liveAllPagesAndKeepsTheStrongestFirst() = runTest {
        val edge = FakeEdge()
        repeat(150) { edge.open += FakeEdge.OpenSighting(SightingKind.BLE, "C4:5B:BE:01:%02X:%02X".format(it / 256, it % 256), -40 - it % 60, -40, 1_000, 3) }
        val l = link(edge)
        val (rows, uptime) = l.liveAll()
        assertEquals(150, rows.size)
        assertEquals(-40, rows.first().rssiLast)
        assertTrue(rows.zipWithNext().all { (a, b) -> a.rssiLast >= b.rssiLast })
        assertTrue(uptime!! > 0)
        edge.mode = "mesh"
        assertTrue(runCatching { l.liveAll() }.exceptionOrNull() is EdgeRequestException)
    }

    @Test fun setTimeMakesTheClockValid() = runTest {
        val edge = FakeEdge(phoneNowMs = { 1_762_400_000_000 })
        edge.timeValid = false
        val l = link(edge)
        assertNull(l.status().unixMs)
        l.setTime(1_762_400_000_000, 330)
        assertEquals(1_762_400_000_000, l.status().unixMs)
        assertEquals(-58, l.linkRssi())
        assertTrue(edge.received.any { it.contains("\"tz_offset_min\":330") })
        assertTrue(edge.received.none { it.contains(JsonPrimitive("12345678").content) })
    }
}
