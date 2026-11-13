package app.sotreus.edge.link

import app.sotreus.edge.fake.FakeEdge
import app.sotreus.edge.protocol.EdgeEvent
import app.sotreus.edge.protocol.int
import app.sotreus.edge.protocol.long
import app.sotreus.edge.protocol.mesh.MeshDelivery
import app.sotreus.edge.protocol.mesh.MeshNodeId
import app.sotreus.edge.protocol.str
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mesh calls over the Bluetooth link against the fake Edge (protocol §2 `mesh_*`). */
class MeshLinkTest {
    private fun TestScope.link(edge: FakeEdge) = NdjsonEdgeLink(edge, backgroundScope).also { runCurrent() }

    private fun node(num: Long, short: String) = FakeEdge.obj("num" to num, "long_name" to "Node $short", "short_name" to short, "has_public_key" to true)

    @Test fun sendingNeedsTheRadioRunning() = runTest {
        val edge = FakeEdge()
        val l = link(edge)
        val refused = runCatching { l.meshSend("hi", MeshNodeId.BROADCAST, 0) }.exceptionOrNull()
        assertTrue(refused is EdgeRequestException)
        assertTrue(refused!!.message!!.contains("not running"))
        edge.mode = "mesh"
        val id = l.meshSend("hi", MeshNodeId.BROADCAST, 0)
        assertTrue(id > 0)
        assertEquals("broadcast", edge.mesh.sends.single().str("to"))
    }

    @Test fun tooLongTextIsRefusedBeforeItLeaves() = runTest {
        val edge = FakeEdge().apply { mode = "mesh" }
        val l = link(edge)
        assertTrue(runCatching { l.meshSend("a".repeat(201), MeshNodeId.BROADCAST, 0) }.exceptionOrNull() is EdgeRequestException)
        assertTrue(edge.mesh.sends.isEmpty())
    }

    @Test fun receivedTextAndDeliveryArriveAsEvents() = runTest {
        val edge = FakeEdge().apply { mode = "mesh" }
        val l = link(edge)
        edge.mesh.hear(node(0xA1B2C3D4L, "BASE"))
        val got = async { l.events.filterIsInstance<EdgeEvent.MeshRx>().first() }
        runCurrent()
        edge.mesh.receive(0xA1B2C3D4L, "Reached the ridge")
        runCurrent()
        val m = got.await().message
        assertEquals("Reached the ridge", m.text)
        assertEquals("BASE", m.peerShortName)

        val id = l.meshSend("Great", 0xA1B2C3D4L, 0)
        val tx = async { l.events.filterIsInstance<EdgeEvent.MeshTx>().first() }
        runCurrent()
        edge.mesh.deliver(id, "delivered")
        runCurrent()
        assertEquals(MeshDelivery.DELIVERED, tx.await().delivery)
        val sent = l.meshMessages().last()
        assertTrue(sent.outgoing && sent.pki)
        assertEquals(MeshDelivery.DELIVERED, sent.delivery)
    }

    @Test fun nodesArePagedWhenTheEdgeCanAndMergedByNumber() = runTest {
        val edge = FakeEdge().apply { mode = "mesh" }
        repeat(40) { edge.mesh.nodes[it + 1L] = node(it + 1L, "N$it") }
        val asked = ArrayList<JsonObject>()
        edge.onCommand = { asked += it }
        val l = link(edge)
        val all = l.meshNodes(paged = true)
        assertEquals(40, all.size)
        assertEquals(listOf(16, 16, 16), asked.filter { it.str("cmd") == "mesh_nodes" }.map { it.int("limit") })
        asked.clear()
        assertEquals(40, l.meshNodes(paged = false).size)
        assertNull(asked.single()["limit"])
    }

    @Test fun configStatusAndRegions() = runTest {
        val edge = FakeEdge().apply { mode = "mesh" }
        val l = link(edge)
        val mesh = app.sotreus.edge.protocol.mesh.MeshConfig.parse(l.config().raw["mesh"] as JsonObject)!!
        assertEquals(2, mesh.channels.size)
        l.setConfig(app.sotreus.edge.protocol.mesh.MeshConfigPatch.positionClient())
        assertEquals("client", (edge.mesh.config["position"] as JsonObject).str("mode"))
        assertEquals(900, (edge.mesh.config["position"] as JsonObject).int("interval_s"))
        l.meshSetPosition(12.97, 77.59)
        assertEquals(12.97 to 77.59, edge.mesh.positions.single())
        assertEquals("IN", l.meshStatus().region)
        assertTrue("LONG_TURBO" in l.meshRegions().presets)
    }

    @Test fun aNodeCanBeRemovedOnlyWhileTheMeshRuns() = runTest {
        val edge = FakeEdge()
        edge.mesh.nodes[0xA1B2C3D4L] = node(0xA1B2C3D4L, "BASE")
        val asked = ArrayList<JsonObject>()
        edge.onCommand = { asked += it }
        val l = link(edge)
        val first = runCatching { l.meshRemoveNode(0xA1B2C3D4L, targetFields = true) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(first, first.contains("not running"))
        edge.mode = "mesh"
        l.meshRemoveNode(0xA1B2C3D4L, targetFields = true)
        assertTrue(0xA1B2C3D4L !in edge.mesh.nodes)
        assertEquals("true", asked.last()["remove"].toString())
        // With `mesh_target_fields` the node goes in `node`; `id` stays the request id.
        assertEquals("!a1b2c3d4", asked.last().str("node"))
        assertTrue(runCatching { l.meshRemoveNode(0xA1B2C3D4L, targetFields = true) }.exceptionOrNull()!!.message!!.contains("not found"))
        assertTrue(runCatching { l.meshRemoveNode(edge.mesh.nodeNum, targetFields = true) }.exceptionOrNull()!!.message!!.contains("own node"))
    }

    @Test fun favouriteAndIgnoreAreMatchedOnTheNode() = runTest {
        val edge = FakeEdge().apply { mode = "mesh" }
        edge.mesh.nodes[0x11112222L] = node(0x11112222L, "HILL")
        val l = link(edge)
        l.meshNode(0x11112222L, favorite = true)
        l.meshNode(0x11112222L, ignored = true)
        assertEquals("true", edge.mesh.nodes[0x11112222L]!!["favorite"].toString())
        assertEquals("true", edge.mesh.nodes[0x11112222L]!!["ignored"].toString())
    }

    @Test fun olderFirmwareGetsTheNodeInIdAndTheReplyIsMatchedOnIt() = runTest {
        val edge = FakeEdge(caps = FakeEdge.DEFAULT_CAPS - "mesh_target_fields").apply { mode = "mesh" }
        edge.mesh.nodes[0x11112222L] = node(0x11112222L, "HILL")
        val asked = ArrayList<JsonObject>()
        edge.onCommand = { asked += it }
        val l = link(edge)
        l.meshNode(0x11112222L, favorite = true, targetFields = false)
        assertEquals(0x11112222L, asked.last().long("id"))
        assertNull(asked.last()["node"])
        l.meshRemoveNode(0x11112222L, targetFields = false)
        assertTrue(0x11112222L !in edge.mesh.nodes)
    }

    @Test fun waypointsNameTheirTargetOnlyWhereTheEdgeCanTellItApart() = runTest {
        val edge = FakeEdge().apply { mode = "mesh" }
        val asked = ArrayList<JsonObject>()
        edge.onCommand = { asked += it }
        val l = link(edge)
        l.meshWaypoint(12.97, 77.59, "Ridge", targetFields = true)
        assertEquals(0L, asked.last().long("waypoint_id"))
        l.meshWaypoint(12.97, 77.59, "Ridge", targetFields = false)
        assertNull(asked.last()["waypoint_id"])
        assertTrue(runCatching { l.meshWaypoint(12.97, 77.59, "Ridge", id = 7, targetFields = false) }.exceptionOrNull() is EdgeRequestException)
        assertEquals(2, edge.mesh.waypoints.size)
    }
}
