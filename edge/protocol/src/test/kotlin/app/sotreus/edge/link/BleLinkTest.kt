package app.sotreus.edge.link

import app.sotreus.edge.fake.FakeEdge
import app.sotreus.edge.protocol.BleLinkResult
import app.sotreus.edge.protocol.EdgeCapabilities
import app.sotreus.edge.protocol.bool
import app.sotreus.edge.protocol.str
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ble_link` (protocol "Link-layer packets"): the cap, the command, and which link follows it. */
class BleLinkTest {
    private fun TestScope.link(edge: FakeEdge) = NdjsonEdgeLink(edge, backgroundScope).also { runCurrent() }

    @Test fun theCapIsReadAndUnknownOrMissingMeansNo() {
        assertTrue(EdgeCapabilities.parse(JsonObject(mapOf("ble_full_size" to JsonPrimitive(true)))).bleFullSize)
        assertFalse(EdgeCapabilities.parse(JsonObject(mapOf("placement" to JsonPrimitive(true)))).bleFullSize)
        assertFalse(EdgeCapabilities.parse(null).bleFullSize)
    }

    @Test fun beforeTheEdgesStepTheCurrentLinkFollows() = runTest {
        val edge = FakeEdge()
        val sent = ArrayList<JsonObject>()
        edge.onCommand = { sent += it }
        val r = link(edge).bleLink(true)
        assertEquals(BleLinkResult(fullSize = true, thisLinkFullSize = true), r)
        assertEquals("ble_link", sent.single().str("cmd"))
        assertEquals(true, sent.single().bool("full_size"))
    }

    @Test fun afterTheStepOnlyTheNextLinkFollows() = runTest {
        val edge = FakeEdge().apply { linkSettled = true }
        assertEquals(BleLinkResult(fullSize = true, thisLinkFullSize = false), link(edge).bleLink(true))
    }

    @Test fun withoutAValueItOnlyReads() = runTest {
        val edge = FakeEdge().apply { fullSize = true; linkFullSize = true }
        val sent = ArrayList<JsonObject>()
        edge.onCommand = { sent += it }
        assertEquals(BleLinkResult(fullSize = true, thisLinkFullSize = true), link(edge).bleLink(null))
        assertNull(sent.single()["full_size"])
        assertTrue(edge.fullSize)
    }
}
