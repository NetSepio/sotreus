package app.sotreus.core.data.lookup

import app.sotreus.core.database.dao.VendorLookupDao
import app.sotreus.core.database.entity.VendorLookupEntity
import app.sotreus.core.model.BleAddressType
import app.sotreus.core.model.RadioKind
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okio.Timeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class VendorLookupTest {
    private class Cache : VendorLookupDao {
        val rows = MutableStateFlow<Map<String, VendorLookupEntity>>(emptyMap())
        override fun observe(prefix: String): Flow<VendorLookupEntity?> = rows.map { it[prefix] }
        override suspend fun get(prefix: String) = rows.value[prefix]
        override suspend fun upsert(value: VendorLookupEntity) { rows.value += value.prefix to value }
        override suspend fun deleteAll() { rows.value = emptyMap() }
        override suspend fun deleteExpired(now: Long) { rows.value = rows.value.filterValues { it.expiresAtMs >= now } }
    }

    private class Harness {
        val cache = Cache()
        var allowed = true
        var wall = 1_000_000L
        var mono = 1_000L
        var calls = 0
        var code = 200
        var body = """{"success":true,"found":true,"company":"Example","macPrefix":"001122","blockType":"MA-L","updated":"2026-10-01"}"""
        var onRequest: () -> Unit = {}
        val paths = mutableListOf<String>()
        val client = VendorLookupClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            paths += chain.request().url.toString()
            onRequest()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                .header("Retry-After", "120").body(body.toResponseBody()).build()
        }.build())
        fun repository() = VendorLookupRepository(cache, client, { allowed }, { wall }, { mono })
        suspend fun query(repo: VendorLookupRepository = repository()) = repo.lookup(RadioKind.WIFI, BleAddressType.PUBLIC, "00:11:22:33:44:55")
    }

    @Test fun disabledOrPrivateAddressesNeverReachNetwork() = runTest {
        val h = Harness()
        h.allowed = false
        assertEquals(VendorLookupOutcome.Disabled, h.query())
        h.allowed = true
        assertEquals(VendorLookupOutcome.UnsupportedAddress, h.repository().lookup(RadioKind.WIFI, BleAddressType.PUBLIC, "02:11:22:33:44:55"))
        assertEquals(VendorLookupOutcome.UnsupportedAddress, h.repository().lookup(RadioKind.BLE, BleAddressType.UNKNOWN, "00:11:22:33:44:55"))
        assertEquals(0, h.calls)
    }

    @Test fun requestsOnlyVendorPrefixAndCachesAcrossRepositoryInstances() = runTest {
        val h = Harness()
        val first = h.query() as VendorLookupOutcome.Found
        assertFalse(first.cached)
        assertEquals("Example", first.record.vendor)
        assertEquals(listOf("https://api.maclookup.app/v2/macs/001122"), h.paths)
        val again = h.query() as VendorLookupOutcome.Found
        assertTrue(again.cached)
        assertEquals(1, h.calls)
    }

    @Test fun concurrentLookupsForSamePrefixSharePersistedResult() = runTest {
        val h = Harness()
        val repo = h.repository()
        val a = async { h.query(repo) }
        val b = async { h.query(repo) }
        assertTrue(a.await() is VendorLookupOutcome.Found)
        assertTrue(b.await() is VendorLookupOutcome.Found)
        assertEquals(1, h.calls)
    }

    @Test fun missingVendorsAreNegativelyCachedForOneDay() = runTest {
        val h = Harness()
        h.body = """{"success":true,"found":false}"""
        val first = h.query() as VendorLookupOutcome.Found
        assertNull(first.record.vendor)
        assertEquals(VendorLookupRepository.NEGATIVE_TTL_MS, first.record.expiresAtMs - first.record.fetchedAtMs)
        h.query()
        assertEquals(1, h.calls)
        h.wall += VendorLookupRepository.NEGATIVE_TTL_MS + 1
        h.query()
        assertEquals(2, h.calls)
    }

    @Test fun rateLimitsBlockRepeatedRequestsWithoutSleeping() = runTest {
        val h = Harness()
        h.code = 429
        val repo = h.repository()
        assertEquals(VendorLookupOutcome.RetryLater(120), h.query(repo))
        assertEquals(VendorLookupOutcome.RetryLater(120), h.query(repo))
        assertEquals(1, h.calls)
        h.mono += 120_001
        h.code = 200
        assertTrue(h.query(repo) is VendorLookupOutcome.Found)
        assertEquals(2, h.calls)
    }

    @Test fun errorsDoNotReplaceSavedResults() = runTest {
        val h = Harness()
        val first = h.query() as VendorLookupOutcome.Found
        h.wall += VendorLookupRepository.POSITIVE_TTL_MS + 1
        h.code = 503
        assertEquals(VendorLookupOutcome.Unavailable, h.query())
        assertEquals(first.record, h.cache.get("001122"))
    }

    @Test fun revokedConsentDoesNotPersistInFlightResult() = runTest {
        val h = Harness()
        h.onRequest = { h.allowed = false }
        assertEquals(VendorLookupOutcome.Disabled, h.query())
        assertTrue(h.cache.rows.value.isEmpty())
    }

    @Test fun malformedOrOversizedAnswersAreNotCached() = runTest {
        for (body in listOf("not json", " ".repeat(40_000))) {
            val h = Harness()
            h.body = body
            assertEquals(VendorLookupOutcome.Unavailable, h.query())
            assertTrue(h.cache.rows.value.isEmpty())
        }
    }

    @Test fun rejectsUnmatchedAndOverlySpecificRegistryRecords() {
        for (fields in listOf("\"macPrefix\":\"AABBCC\",\"blockType\":\"MA-L\"", "\"macPrefix\":\"0011223\",\"blockType\":\"MA-M\"")) {
            assertThrows(IOException::class.java) {
                VendorLookupClient.parse("001122", """{"success":true,"found":true,"company":"Wrong",$fields}""")
            }
        }
    }

    @Test fun retryAfterSupportsSecondsAndHttpDatesWithBounds() {
        assertEquals(120_000L, VendorLookupClient.retryDelay("120", 0))
        assertEquals(60_000L, VendorLookupClient.retryDelay(null, 0))
        assertEquals(86_400_000L, VendorLookupClient.retryDelay(Long.MAX_VALUE.toString(), 0))
        assertEquals(120_000L, VendorLookupClient.retryDelay("Thu, 01 Jan 1970 00:02:00 GMT", 0))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancellingLookupCancelsHttpCallAndDoesNotCacheResult() = runTest {
        var cancelled = false
        val factory = Call.Factory { request ->
            val delegate = OkHttpClient().newCall(request)
            object : Call by delegate {
                override fun enqueue(responseCallback: Callback) = Unit
                override fun cancel() { cancelled = true; delegate.cancel() }
            }
        }
        val cache = Cache()
        val repo = VendorLookupRepository(cache, VendorLookupClient(factory), { true })
        val job = launch { repo.lookup(RadioKind.WIFI, BleAddressType.PUBLIC, "00:11:22:33:44:55") }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(cache.rows.value.isEmpty())
    }
}
