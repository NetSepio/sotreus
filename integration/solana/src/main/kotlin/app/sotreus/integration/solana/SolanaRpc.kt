package app.sotreus.integration.solana

import app.sotreus.core.model.SolanaCluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Solana JSON-RPC client (handoff §26 "SolanaRpcClient"). Its methods accept only
 * addresses, signatures and slots — never observation data.
 */
@Singleton
class SolanaRpc @Inject constructor() {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    data class Status(val confirmationStatus: String?, val failed: Boolean, val slot: Long?)

    suspend fun latestBlockhash(cluster: SolanaCluster): String =
        call(cluster, "getLatestBlockhash", buildJsonArray { add(buildJsonObject { put("commitment", "finalized") }) })
            .jsonObject["value"]!!.jsonObject["blockhash"]!!.jsonPrimitive.content

    suspend fun signatureStatus(cluster: SolanaCluster, signature: String): Status? {
        val value = call(
            cluster, "getSignatureStatuses",
            buildJsonArray { add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(signature)) }); add(buildJsonObject { put("searchTransactionHistory", true) }) },
        ).jsonObject["value"] as? JsonArray ?: return null
        val s = value.firstOrNull() as? JsonObject ?: return null
        return Status(
            confirmationStatus = s["confirmationStatus"]?.jsonPrimitive?.content,
            failed = s["err"] != null && s["err"] !is JsonNull,
            slot = s["slot"]?.jsonPrimitive?.long,
        )
    }

    suspend fun blockTimeMs(cluster: SolanaCluster, slot: Long): Long? =
        runCatching { call(cluster, "getBlockTime", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(slot)) }).jsonPrimitive.long * 1000 }.getOrNull()

    suspend fun balance(cluster: SolanaCluster, address: String): Long =
        call(cluster, "getBalance", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(address)) }).jsonObject["value"]!!.jsonPrimitive.long

    private fun endpoint(cluster: SolanaCluster): SolanaRpcEndpoints.Choice.Use {
        SolanaRpcEndpoints.requireMainnet(cluster)
        return when (val choice = SolanaRpcEndpoints.resolve(BuildConfig.NOWNODES_SOLANA_RPC_URL, BuildConfig.NOWNODES_SOLANA_API_KEY)) {
            is SolanaRpcEndpoints.Choice.Use -> choice
            is SolanaRpcEndpoints.Choice.Rejected -> throw IllegalStateException(choice.reason)
        }
    }

    private suspend fun call(cluster: SolanaCluster, method: String, params: JsonArray): JsonElement = call(endpoint(cluster), method, params)

    private suspend fun call(endpoint: SolanaRpcEndpoints.Choice.Use, method: String, params: JsonArray): JsonElement = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }.toString()
        val request = Request.Builder()
            .url(endpoint.url)
            .apply { endpoint.apiKey?.let { header("api-key", it) } }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Solana RPC HTTP ${response.code}" }
            val text = response.body.string()
            val obj = json.parseToJsonElement(text).jsonObject
            obj["error"]?.takeUnless { it is JsonNull }?.let { err ->
                val code = (err as? JsonObject)?.get("code")?.jsonPrimitive?.content.orEmpty()
                throw IllegalStateException("Solana RPC error $code")
            }
            obj["result"] ?: throw IllegalStateException("Empty RPC result")
        }
    }
}
