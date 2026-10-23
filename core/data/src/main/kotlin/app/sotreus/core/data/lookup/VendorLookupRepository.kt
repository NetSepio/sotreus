package app.sotreus.core.data.lookup

import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.VendorLookupDao
import app.sotreus.core.database.entity.VendorLookupEntity
import app.sotreus.core.model.BleAddressType
import app.sotreus.core.model.RadioKind
import app.sotreus.intelligence.Fingerprint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface VendorLookupOutcome {
    data class Found(val record: VendorLookupEntity, val cached: Boolean) : VendorLookupOutcome
    data object Disabled : VendorLookupOutcome
    data object UnsupportedAddress : VendorLookupOutcome
    data object Unavailable : VendorLookupOutcome
    data class RetryLater(val seconds: Long) : VendorLookupOutcome
}

@Singleton
class VendorLookupRepository internal constructor(
    private val cache: VendorLookupDao,
    private val client: VendorLookupClient,
    private val enabled: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Inject constructor(cache: VendorLookupDao, client: VendorLookupClient, settings: SettingsRepository) :
        this(cache, client, { settings.current().onlineVendorLookup })

    private val requests = Mutex()
    private var nextRequestMs = 0L

    /** Serialised with requests so a completed in-flight lookup cannot repopulate a cleared cache. */
    suspend fun clearCache() = requests.withLock { cache.deleteAll() }

    /** Called only on an explicit user action. Positive and negative answers survive restarts. */
    suspend fun lookup(kind: RadioKind, type: BleAddressType, address: String): VendorLookupOutcome = requests.withLock {
        val prefix = Fingerprint.vendorLookupPrefix(kind, type, address) ?: return@withLock VendorLookupOutcome.UnsupportedAddress
        if (!enabled()) return@withLock VendorLookupOutcome.Disabled
        val wall = now()
        cache.get(prefix)?.takeIf { it.fetchedAtMs <= wall && it.expiresAtMs > wall }?.let {
            return@withLock VendorLookupOutcome.Found(it, cached = true)
        }
        val remaining = nextRequestMs - monotonicMs()
        if (remaining > 0) return@withLock VendorLookupOutcome.RetryLater((remaining + 999) / 1_000)
        nextRequestMs = monotonicMs() + 1_000
        try {
            val record = client.lookup(prefix)
            // Consent may have been revoked while the request was in flight.
            if (!enabled()) return@withLock VendorLookupOutcome.Disabled
            val fetchedAt = now()
            val row = VendorLookupEntity(prefix, record.vendor, record.blockType, record.updated, fetchedAt,
                fetchedAt + if (record.vendor != null) POSITIVE_TTL_MS else NEGATIVE_TTL_MS)
            cache.deleteExpired(fetchedAt)
            cache.upsert(row)
            VendorLookupOutcome.Found(row, cached = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VendorLookupClient.RateLimited) {
            nextRequestMs = monotonicMs() + e.waitMs
            VendorLookupOutcome.RetryLater((e.waitMs + 999) / 1_000)
        } catch (_: Exception) {
            nextRequestMs = monotonicMs() + 5_000
            VendorLookupOutcome.Unavailable
        }
    }

    companion object {
        const val POSITIVE_TTL_MS = 30 * 24 * 60 * 60_000L
        const val NEGATIVE_TTL_MS = 24 * 60 * 60_000L
    }
}
