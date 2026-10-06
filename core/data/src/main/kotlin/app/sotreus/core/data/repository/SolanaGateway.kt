package app.sotreus.core.data.repository

import app.sotreus.core.model.SolanaCluster

/**
 * Wallet and chain boundary (architecture handoff §8, §15). Implemented in :integration:solana with
 * Mobile Wallet Adapter; common code only sees this interface and narrow values (a public key, a
 * 32-byte commitment as hex). It never receives observations, places or identifiers.
 */
interface SolanaGateway {
    /** Opens the wallet on this phone and signs in with Sign In With Solana (not a transaction). */
    suspend fun signIn(cluster: SolanaCluster): WalletSignIn

    /** Asks the wallet to sign and send a Memo transaction carrying only the batch commitment. */
    suspend fun stampCommitment(commitmentHex: String, payer: String, cluster: SolanaCluster): String

    /**
     * Asks the wallet to sign a mainnet transaction that sends 0 lamports to itself and a fixed
     * memo ([walletCheckMemo]). No observation, place or identifier is included.
     */
    suspend fun sendWalletCheck(payer: String, cluster: SolanaCluster): String

    /** The only memo text [sendWalletCheck] puts on-chain. */
    fun walletCheckMemo(): String

    suspend fun confirmation(signature: String, cluster: SolanaCluster): ChainConfirmation

    suspend fun balanceLamports(address: String, cluster: SolanaCluster): Long?

    suspend fun disconnect()

    /** The SIWS statement shown before the wallet opens (screen S2). */
    fun signInPreview(address: String?, cluster: SolanaCluster): String
}

data class WalletSignIn(val publicKey: String, val walletLabel: String?, val signatureVerified: Boolean)

data class ChainConfirmation(val finalized: Boolean, val confirmed: Boolean, val failed: Boolean, val blockTimeMs: Long?)

class WalletException(message: String, val userCancelled: Boolean = false, val noWallet: Boolean = false) : Exception(message)
