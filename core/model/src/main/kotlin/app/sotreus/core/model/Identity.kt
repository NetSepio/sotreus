package app.sotreus.core.model

/** What the running device is, decided at runtime. Drives the auth/settings UI. */
data class DeviceProfile(
    val isSolanaMobile: Boolean,
    val manufacturer: String,
    val model: String,
)

/**
 * What this install can offer (handoff §22). Derived from the device at runtime: Solana Mobile
 * devices get wallet sign-in and proof stamping; every device gets the local profile.
 */
data class AppCapabilities(
    val localProfile: Boolean,
    val solanaWalletAuth: Boolean,
    val onChainProofStamping: Boolean,
    val nearbyFriendPresence: Boolean,
    val edgeSupport: Boolean,
) {
    companion object {
        fun forDevice(device: DeviceProfile) = AppCapabilities(
            localProfile = true,
            solanaWalletAuth = device.isSolanaMobile,
            onChainProofStamping = device.isSolanaMobile,
            nearbyFriendPresence = true,
            edgeSupport = false,
        )
    }
}

enum class SolanaCluster(val chainId: String, val rpcUrl: String) {
    DEVNET("solana:devnet", "https://api.devnet.solana.com"),
    MAINNET_BETA("solana:mainnet", "https://api.mainnet-beta.solana.com"),
}

enum class ProofState { PENDING, SUBMITTED, FINALIZED, FAILED }

enum class LinkedIdentityKind { SOLANA_WALLET }
