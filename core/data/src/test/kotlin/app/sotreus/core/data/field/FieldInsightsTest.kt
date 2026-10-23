package app.sotreus.core.data.field

import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.dao.TrackerStopDao
import app.sotreus.core.database.entity.TrackerStopEntity
import app.sotreus.core.model.UserEntityState
import app.sotreus.intelligence.SignatureClassifier
import app.sotreus.intelligence.fieldwatch.*
import app.sotreus.sensing.LocationSource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class FieldInsightsTest {
    private val storage = MemoryStops()
    private val sessions = Proxy.newProxyInstance(SessionDao::class.java.classLoader, arrayOf(SessionDao::class.java)) { _, method, _ ->
        error("Unexpected session call: ${method.name}")
    } as SessionDao
    private val insights = FieldInsights(storage, sessions, SignatureClassifier())
    private val base = 1_700_000_000_000L
    private fun radio(at: Long, address: String? = "Public") = observationSighting(Observation(
        RadioKind.BLE, "00:11:22:33:44:55", "", -60, 0, 0, false, emptyList(),
        0x004C, "12", "", "", at, facts = RadioFacts(addressType = address),
    ))
    private suspend fun hear(seconds: Long, lat: Double = 12.0, gps: Boolean = true,
                             enabled: Boolean = true, state: UserEntityState = UserEntityState.UNCLASSIFIED,
                             address: String? = "Public", simulated: Boolean = false) {
        val at = base + seconds * 1_000
        insights.stop(radio(at, address), if (gps) LocationSource.Fix(at, lat, 77.0, 5f) else null,
            1L, state, enabled, simulated)
    }

    @Test fun continuousMovementAndGpsOutageDoNotInventQuietReturns() = runTest {
        hear(0); hear(10)
        for (s in 20L..2_000L step 10) hear(s, lat = 12.02, gps = false)
        hear(2_010, lat = 12.02); hear(2_020, lat = 12.02)
        assertEquals(1, storage.rows.size)
        assertEquals(2, storage.rows.single().hits)
        assertEquals(0L, storage.rows.single().returnGapMs)
    }

    @Test fun quietReturnAtAnotherPlaceNeedsTwoSeparatedSamples() = runTest {
        hear(0); hear(10)
        hear(1_300, lat = 12.02)
        assertEquals(2, storage.rows.size)
        assertEquals(1, storage.rows.last().hits)
        hear(1_305, lat = 12.02)
        assertEquals(1, storage.rows.last().hits)
        hear(1_310, lat = 12.02)
        assertEquals(2, storage.rows.last().hits)
        assertEquals(1_290_000L, storage.rows.last().returnGapMs)
    }

    @Test fun historyRequiresConsentKnownPublicAddressAndUnexcludedState() = runTest {
        hear(0, enabled = false); hear(10, address = null); hear(20, address = "Random")
        hear(30, state = UserEntityState.MINE); hear(40, state = UserEntityState.EXPECTED)
        hear(50, state = UserEntityState.IGNORE); hear(60, simulated = true)
        assertTrue(storage.rows.isEmpty()); assertTrue(storage.memory.isEmpty())
        hear(70); hear(80)
        assertEquals(2, storage.rows.single().hits)
    }

    @Test fun invalidAndMockFixesCannotCreateStopsButKeepContinuity() = runTest {
        val at = base
        for (fix in listOf(LocationSource.Fix(at, Double.NaN, 77.0, 5f), LocationSource.Fix(at, 12.0, 77.0, 5f, mock = true),
            LocationSource.Fix(at, 12.0, 77.0, 100f), LocationSource.Fix(at - 30_000, 12.0, 77.0, 5f))) {
            storage.memory.clear()
            insights.stop(radio(at), fix, 1L, UserEntityState.UNCLASSIFIED, true, false)
        }
        assertTrue(storage.rows.isEmpty()); assertEquals(base, storage.memory.values.single())
    }

    @Test fun clearingDeletesCoordinateFreeContinuityToo() = runTest {
        hear(0); hear(10); insights.clearStops()
        assertTrue(storage.rows.isEmpty()); assertTrue(storage.memory.isEmpty())
    }

    @Test fun floodFlagsAreWrittenOncePerSessionAndExpireFromLiveEvidence() = runTest {
        var writes = 0
        val dao = Proxy.newProxyInstance(SessionDao::class.java.classLoader, arrayOf(SessionDao::class.java)) { _, method, _ ->
            when (method.name) {
                "markFlood" -> { writes++; Unit }
                "insertEvent" -> 1L
                else -> error("Unexpected session call: ${method.name}")
            }
        } as SessionDao
        val subject = FieldInsights(storage, dao, SignatureClassifier())
        repeat(10) { n -> subject.hear(Observation(RadioKind.BLE, "02:00:00:00:00:%02X".format(n), "", -48,
            0, 0, false, emptyList(), 0x004C, "070100", "", "", base,
            facts = RadioFacts(mfgRecords = listOf(MfgRecord(0x004C, "070100")))), false, null) }
        subject.tick(base, 1L, null)
        assertEquals(10, subject.quiet.size)
        assertEquals(1, writes)
        subject.tick(base + 1_000, 1L, null)
        assertEquals(1, writes)
        subject.tick(base + 2_000, 2L, null)
        assertEquals(2, writes)
        subject.tick(base + 16 * 60_000, null, null)
        assertTrue(subject.quiet.isEmpty())
    }

    private class MemoryStops : TrackerStopDao {
        val rows = mutableListOf<TrackerStopEntity>()
        val memory = mutableMapOf<String, Long>()
        override suspend fun lastHeard(stableId: String) = memory[stableId]
        override suspend fun remember(stableId: String, atMs: Long) { memory[stableId] = atMs }
        override suspend fun pruneMemory(cutoff: Long) { memory.entries.removeAll { it.value < cutoff } }
        override suspend fun clearMemory() { memory.clear() }
        override fun observeStable(stableId: String) = flowOf(rows.filter { it.stableId == stableId && it.hits >= 2 })
        override fun observe(entityId: String) = flowOf(rows.filter { it.entityId == entityId && it.hits >= 2 })
        override suspend fun latest(stableId: String) = rows.filter { it.stableId == stableId }.sortedByDescending { it.lastMs }.take(8)
        override suspend fun insert(row: TrackerStopEntity): Long { val id = (rows.maxOfOrNull { it.id } ?: 0) + 1; rows += row.copy(id = id); return id }
        override suspend fun update(row: TrackerStopEntity) { rows[rows.indexOfFirst { it.id == row.id }] = row }
        override suspend fun prune(cutoff: Long) { rows.removeAll { it.lastMs < cutoff } }
        override suspend fun cap(stableId: String) { val keep = latest(stableId).map { it.id }; rows.removeAll { it.stableId == stableId && it.id !in keep } }
        override suspend fun capAll() = Unit
        override suspend fun deleteForSession(sessionId: Long) { rows.removeAll { it.sessionId == sessionId } }
        override suspend fun clear() { rows.clear() }
    }
}
