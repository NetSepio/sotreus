package app.sotreus.edge.link

import app.sotreus.edge.fake.FakeEdge
import app.sotreus.edge.protocol.EdgeRestartLoss
import app.sotreus.edge.protocol.EdgeRestartLoss.Range
import app.sotreus.edge.protocol.RestartLossPlan
import app.sotreus.edge.protocol.RestartLossPlanner.plan
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `status.log.restart_loss` (protocol "Records lost at a restart"): parsing, and which restarts one handshake judges. */
class RestartLossTest {
    private fun TestScope.link(edge: FakeEdge) = NdjsonEdgeLink(edge, backgroundScope).also { runCurrent() }
    private fun report(since: Long, vararg ranges: Range, records: Long = ranges.sumOf { it.size }) =
        EdgeRestartLoss(since, records, ranges.toList())

    @Test fun theStatusCarriesItAndOlderFirmwareLeavesItOut() = runTest {
        val edge = FakeEdge()
        val l = link(edge)
        assertNull(l.status().log!!.restartLoss)
        edge.restartLoss = Json.parseToJsonElement(
            """{"since_boot": 57, "records": 3, "ranges": [{"boot": 58, "from": 30650, "to": 30652}], "future_key": 1}""",
        ).jsonObject
        assertEquals(report(57, Range(58, 30650, 30652)), l.status().log!!.restartLoss)
    }

    @Test fun malformedRangesAreLeftOutAndRangesComeOldestFirst() {
        val o = Json.parseToJsonElement(
            """{"since_boot": 50, "records": 9, "ranges": [{"boot": 60, "from": 10, "to": 12}, {"boot": 55, "from": 5, "to": 4}, {"boot": 52, "from": 1, "to": 1}, {"boot": 53}]}""",
        ).jsonObject
        assertEquals(EdgeRestartLoss(50, 9, listOf(Range(52, 1, 1), Range(60, 10, 12))), EdgeRestartLoss.parse(o))
        assertNull(EdgeRestartLoss.parse(Json.parseToJsonElement("""{"records": 3}""").jsonObject))
    }

    @Test fun nothingIsJudgedBeforeTheFirstHandshakeOrWithoutARestart() {
        val r = report(57, Range(58, 100, 102))
        assertEquals(RestartLossPlan(emptyList(), 0), plan(lastBoot = null, boot = 59, report = r))
        assertEquals(RestartLossPlan(emptyList(), 0), plan(lastBoot = 59, boot = 59, report = r))
    }

    @Test fun onlyRestartsSinceTheLastHandshakeAreNew() {
        // This phone saw boot 58 and 59 before; 59's restart lost 200..201. 58's range was judged then.
        val r = report(57, Range(58, 100, 102), Range(59, 200, 201))
        assertEquals(RestartLossPlan(listOf(200L..201L), 0), plan(lastBoot = 59, boot = 60, report = r))
        // Away for two restarts: both are new.
        assertEquals(RestartLossPlan(listOf(100L..102L, 200L..201L), 0), plan(lastBoot = 58, boot = 60, report = r))
    }

    @Test fun aRestartThatLostNothingIsKnownToHaveLostNothing() {
        assertEquals(RestartLossPlan(emptyList(), 0), plan(lastBoot = 58, boot = 59, report = report(57)))
    }

    @Test fun aPowerLossOrTheResetPinIsNotKnown() {
        // Last seen on boot 58; unplugged, so the count starts again at 59.
        assertEquals(RestartLossPlan(emptyList(), 1), plan(lastBoot = 58, boot = 59, report = report(59)))
        // A software restart 58 -> 59 (its range wiped too), then a power loss into 60, then a crash 60 -> 61.
        assertEquals(RestartLossPlan(listOf(300L..300L), 2), plan(lastBoot = 58, boot = 61, report = report(60, Range(60, 300, 300))))
    }

    @Test fun olderFirmwareMakesEveryRestartNotKnown() {
        assertEquals(RestartLossPlan(emptyList(), 2), plan(lastBoot = 58, boot = 60, report = null))
    }

    @Test fun aBootCounterThatStartedAgainIsOneRestartNotKnown() {
        // Storage erased at boot: the counter is back at 1 and the old ranges are gone.
        assertEquals(RestartLossPlan(emptyList(), 1), plan(lastBoot = 58, boot = 1, report = report(1)))
    }

    @Test fun rangesPushedOutOfTheListAreNotKnown() {
        // 8 listed (boots 63..70), 2 more records lost before them: restarts out of 61 and 62 are not known.
        val listed = (63L..70L).map { Range(it, it * 10, it * 10) }.toTypedArray()
        val r = report(60, *listed, records = 10)
        val p = plan(lastBoot = 61, boot = 71, report = r)
        assertEquals(listed.map { it.from..it.to }, p.ranges)
        assertEquals(2, p.notKnown)
        // Already past them on the last handshake: nothing more.
        assertEquals(RestartLossPlan(emptyList(), 0), plan(lastBoot = 71, boot = 72, report = r))
    }
}
