package app.sotreus.integration.solana

import java.net.URI
import app.sotreus.core.model.SolanaCluster

/**
 * Mainnet endpoints only. NOWNodes supports a full dashboard URL with a key in the path,
 * or its mainnet root URL with an API key supplied separately in the api-key header.
 */
object SolanaRpcEndpoints {
    const val PUBLIC_MAINNET = "https://api.mainnet-beta.solana.com"
    const val NOWNODES_MAINNET = "https://sol.nownodes.io"

    sealed interface Choice {
        data class Use(val url: String, val apiKey: String? = null) : Choice
        data class Rejected(val reason: String) : Choice
    }

    fun requireMainnet(cluster: SolanaCluster) {
        require(cluster == SolanaCluster.MAINNET_BETA) { "Only Solana mainnet is supported" }
    }

    fun resolve(configuredUrl: String, configuredApiKey: String = ""): Choice {
        val url = configuredUrl.trim()
        val apiKey = configuredApiKey.trim().ifEmpty { null }
        if (url.isEmpty()) {
            return if (apiKey == null) Choice.Use(PUBLIC_MAINNET)
            else Choice.Use(NOWNODES_MAINNET, apiKey)
        }
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return Choice.Rejected("Solana RPC URL is not a valid address")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return Choice.Rejected("Solana RPC URL must use https")
        }
        val host = uri.host?.lowercase() ?: return Choice.Rejected("Solana RPC URL has no host")
        if (uri.userInfo != null || uri.fragment != null || uri.port !in listOf(-1, 443)) {
            return Choice.Rejected("Solana RPC URL must not contain user information, a fragment or a custom port")
        }
        if (host == "api.mainnet-beta.solana.com") {
            if (uri.path !in listOf("", "/") || uri.query != null) {
                return Choice.Rejected("Use the public mainnet RPC root URL")
            }
            // Never send a provider credential to the public RPC.
            return Choice.Use(PUBLIC_MAINNET)
        }
        if (host == "sol.nownodes.io") {
            if (uri.path.orEmpty().trim('/').isEmpty() && apiKey == null) {
                return Choice.Rejected("NOWNodes requires a full dashboard URL or NOWNODES_SOLANA_API_KEY")
            }
            return Choice.Use(url.trimEnd('/'), apiKey)
        }
        return Choice.Rejected("Solana RPC URL must be the public mainnet or https://sol.nownodes.io")
    }
}
