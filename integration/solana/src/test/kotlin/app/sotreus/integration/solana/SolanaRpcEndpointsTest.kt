package app.sotreus.integration.solana

import app.sotreus.core.model.SolanaCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaRpcEndpointsTest {
    @Test
    fun blankConfigurationUsesPublicMainnet() {
        val choice = SolanaRpcEndpoints.resolve("  ") as SolanaRpcEndpoints.Choice.Use
        assertEquals(SolanaRpcEndpoints.PUBLIC_MAINNET, choice.url)
        assertNull(choice.apiKey)
    }

    @Test
    fun publicMainnetNeverReceivesProviderCredentials() {
        val choice = SolanaRpcEndpoints.resolve("https://api.mainnet-beta.solana.com/", "provider-key") as SolanaRpcEndpoints.Choice.Use
        assertEquals(SolanaRpcEndpoints.PUBLIC_MAINNET, choice.url)
        assertNull(choice.apiKey)
    }

    @Test
    fun nownodesMainnetKeepsDashboardPath() {
        val choice = SolanaRpcEndpoints.resolve("https://sol.nownodes.io/example-key/") as SolanaRpcEndpoints.Choice.Use
        assertEquals("https://sol.nownodes.io/example-key", choice.url)
        assertNull(choice.apiKey)
    }

    @Test
    fun nownodesRootUsesHeaderAuthentication() {
        val choice = SolanaRpcEndpoints.resolve("https://sol.nownodes.io/", "provider-key") as SolanaRpcEndpoints.Choice.Use
        assertEquals(SolanaRpcEndpoints.NOWNODES_MAINNET, choice.url)
        assertEquals("provider-key", choice.apiKey)
    }

    @Test
    fun keyWithoutUrlSelectsNownodesMainnet() {
        assertEquals(SolanaRpcEndpoints.Choice.Use(SolanaRpcEndpoints.NOWNODES_MAINNET, "provider-key"), SolanaRpcEndpoints.resolve("", "provider-key"))
    }

    @Test
    fun unauthenticatedNownodesIsRejected() {
        assertTrue(SolanaRpcEndpoints.resolve("https://sol.nownodes.io/") is SolanaRpcEndpoints.Choice.Rejected)
    }

    @Test
    fun otherNetworksAndLookalikeHostsAreRejected() {
        listOf(
            "https://api.devnet.solana.com",
            "https://api.testnet.solana.com",
            "https://sol-devnet.nownodes.io/example-key",
            "https://sol-testnet.nownodes.io/example-key",
            "https://sol.nownodes.io.evil.example/example-key",
            "https://evil.nownodes.io/example-key",
            "https://sol.nownodes.io@evil.example/example-key",
        ).forEach { url -> assertTrue(url, SolanaRpcEndpoints.resolve(url) is SolanaRpcEndpoints.Choice.Rejected) }
    }

    @Test
    fun invalidOrUnsafeUrlsAreRejected() {
        listOf(
            "not a URL",
            "http://sol.nownodes.io/example-key",
            "https://sol.nownodes.io:8443/example-key",
            "https://user:password@sol.nownodes.io/example-key",
            "https://sol.nownodes.io/example-key#fragment",
            "https://api.mainnet-beta.solana.com/example-key",
            "https://api.mainnet-beta.solana.com?network=devnet",
        ).forEach { url -> assertTrue(url, SolanaRpcEndpoints.resolve(url) is SolanaRpcEndpoints.Choice.Rejected) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun historicalDevnetClusterCannotMakeRpcCalls() {
        SolanaRpcEndpoints.requireMainnet(SolanaCluster.DEVNET)
    }

    @Test
    fun mainnetClusterIsAllowed() {
        SolanaRpcEndpoints.requireMainnet(SolanaCluster.MAINNET_BETA)
        assertEquals("solana:mainnet", SolanaCluster.MAINNET_BETA.chainId)
    }
}
