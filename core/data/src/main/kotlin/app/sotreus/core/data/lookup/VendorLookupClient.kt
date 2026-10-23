package app.sotreus.core.data.lookup

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Narrow network boundary: only a validated 24-bit vendor prefix can leave the phone. */
class VendorLookupClient internal constructor(private val calls: Call.Factory) {
    @Inject constructor() : this(OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build())

    data class Record(val vendor: String?, val blockType: String?, val updated: String?)
    class RateLimited(val waitMs: Long) : IOException()

    suspend fun lookup(prefix: String): Record {
        require(prefix.matches(Regex("[0-9A-F]{6}")))
        return suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(Request.Builder().url("https://api.maclookup.app/v2/macs/$prefix")
                .header("Accept", "application/json").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (it.code == 429) throw RateLimited(retryDelay(it.header("Retry-After"), System.currentTimeMillis()))
                            if (!it.isSuccessful) throw IOException("Vendor service HTTP ${it.code}")
                            val body = it.body
                            val source = body.source()
                            source.request(MAX_RESPONSE_BYTES + 1)
                            if (source.buffer.size > MAX_RESPONSE_BYTES) throw IOException("Oversized vendor response")
                            parse(prefix, source.readUtf8())
                        }
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }

    companion object {
        const val SOURCE = "MACLookup"
        const val SOURCE_URL = "https://maclookup.app/api-v2/documentation"
        private const val MAX_RESPONSE_BYTES = 32_768L
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class Reply(
            val success: Boolean, val found: Boolean = false, val company: String = "",
            val macPrefix: String = "", val blockType: String = "", val updated: String = "",
        )

        internal fun parse(prefix: String, text: String): Record {
            val reply = json.decodeFromString<Reply>(text)
            if (!reply.success) throw IOException("Vendor request rejected")
            if (!reply.found) return Record(null, null, null)
            val matched = reply.macPrefix.replace(":", "").uppercase()
            // This provider documents /24 matching. Do not accept a more specific block for a /24 query.
            if (matched != prefix || reply.blockType != "MA-L" || reply.company.isBlank()) throw IOException("Unverified vendor prefix")
            return Record(reply.company.filterNot(Char::isISOControl).take(200), reply.blockType,
                reply.updated.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) })
        }

        internal fun retryDelay(header: String?, nowMs: Long): Long {
            val seconds = header?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(86_400)
            val delay = seconds?.times(1_000) ?: runCatching {
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs
            }.getOrDefault(60_000)
            return delay.coerceIn(2_000, 86_400_000)
        }
    }
}
