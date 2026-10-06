package app.sotreus.integration.solana

import android.net.Uri
import androidx.activity.ComponentActivity
import app.sotreus.core.crypto.Base58
import app.sotreus.core.crypto.Ed25519Verify
import app.sotreus.core.data.repository.ChainConfirmation
import app.sotreus.core.data.repository.SolanaGateway
import app.sotreus.core.data.repository.WalletException
import app.sotreus.core.data.repository.WalletSignIn
import app.sotreus.core.data.security.SecretBox
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.model.SolanaCluster
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana
import javax.inject.Inject
import javax.inject.Singleton

/** Holds the Mobile Wallet Adapter activity bridge, which must be created in Activity.onCreate. */
@Singleton
class WalletActivityBridge @Inject constructor() {
    @Volatile var sender: ActivityResultSender? = null
        private set

    fun attach(activity: ComponentActivity) {
        sender = ActivityResultSender(activity)
    }
}

/**
 * Mobile Wallet Adapter gateway (architecture handoff §8, §15). Devnet only in V1. Keys and seed
 * never leave the wallet; Sotreus keeps the public key and a Keystore-wrapped auth token.
 */
@Singleton
class MwaSolanaGateway @Inject constructor(
    private val bridge: WalletActivityBridge,
    private val rpc: SolanaRpc,
    private val settings: SettingsRepository,
    private val box: SecretBox,
) : SolanaGateway {
    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse(MwaConfig.IDENTITY_URI),
            iconUri = Uri.parse(MwaConfig.ICON_PATH),
            identityName = MwaConfig.IDENTITY_NAME,
        ),
    ).apply { blockchain = Solana.Devnet }

    private fun sender(): ActivityResultSender = bridge.sender ?: throw WalletException("No activity to open the wallet from")

    private suspend fun restoreToken() {
        if (adapter.authToken == null) {
            settings.walletAuthTokenWrapped()?.let { w -> runCatching { adapter.authToken = String(box.unwrap(w)) } }
        }
    }

    private suspend fun saveToken(token: String?) {
        settings.setWalletAuthTokenWrapped(token?.let { box.wrap(it.toByteArray()) })
    }

    override suspend fun signIn(cluster: SolanaCluster): WalletSignIn {
        requireDevnet(cluster)
        val result = adapter.signIn(sender(), SignInWithSolana.Payload(MwaConfig.IDENTITY_DOMAIN, MwaConfig.SIGN_IN_STATEMENT))
        return when (result) {
            is TransactionResult.Success -> {
                val sir = result.payload
                saveToken(result.authResult.authToken)
                WalletSignIn(
                    publicKey = Base58.encode(sir.publicKey),
                    walletLabel = result.authResult.accountLabel,
                    signatureVerified = Ed25519Verify.verify(sir.publicKey, sir.signedMessage, sir.signature),
                )
            }
            is TransactionResult.NoWalletFound -> throw WalletException(result.message, noWallet = true)
            is TransactionResult.Failure -> throw WalletException(result.message, userCancelled = result.e.javaClass.simpleName.contains("Declined", true))
        }
    }

    override suspend fun stampCommitment(commitmentHex: String, payer: String, cluster: SolanaCluster): String {
        requireDevnet(cluster)
        val memo = MemoTransaction.memoFor(commitmentHex)
        val blockhash = Base58.decode(rpc.latestBlockhash(cluster))
        restoreToken()
        val result = adapter.transact(sender()) { auth ->
            val payerKey = auth.accounts.firstOrNull()?.publicKey ?: auth.publicKey
            val tx = MemoTransaction.build(payerKey, blockhash, memo, Base58.decode(MemoTransaction.MEMO_PROGRAM_ID))
            signAndSendTransactions(arrayOf(tx))
        }
        return when (result) {
            is TransactionResult.Success -> {
                saveToken(result.authResult.authToken)
                Base58.encode(result.payload.signatures.first())
            }
            is TransactionResult.NoWalletFound -> throw WalletException(result.message, noWallet = true)
            is TransactionResult.Failure -> throw WalletException(result.message, userCancelled = result.e.javaClass.simpleName.contains("Declined", true))
        }
    }

    override suspend fun confirmation(signature: String, cluster: SolanaCluster): ChainConfirmation {
        val s = rpc.signatureStatus(cluster, signature) ?: return ChainConfirmation(finalized = false, confirmed = false, failed = false, blockTimeMs = null)
        val finalized = s.confirmationStatus == "finalized"
        return ChainConfirmation(
            finalized = finalized && !s.failed,
            confirmed = s.confirmationStatus == "confirmed" || finalized,
            failed = s.failed,
            blockTimeMs = if (finalized) s.slot?.let { rpc.blockTimeMs(cluster, it) } else null,
        )
    }

    override suspend fun balanceLamports(address: String, cluster: SolanaCluster): Long? = runCatching { rpc.balance(cluster, address) }.getOrNull()

    override suspend fun requestDevnetAirdrop(address: String): String = rpc.requestAirdrop(address, 1_000_000_000L)

    override suspend fun disconnect() {
        restoreToken()
        bridge.sender?.let { runCatching { adapter.disconnect(it) } }
        adapter.authToken = null
        saveToken(null)
    }

    override fun signInPreview(address: String?, cluster: SolanaCluster): String = buildString {
        append(MwaConfig.IDENTITY_DOMAIN).append(" wants you to sign in with your Solana account:\n")
        append(address?.let(Base58::abbreviate) ?: "…").append("\n\n")
        append(MwaConfig.SIGN_IN_STATEMENT).append("\n\n")
        append("Chain: ").append(cluster.name.lowercase().substringBefore('_'))
    }

    private fun requireDevnet(cluster: SolanaCluster) {
        // V1 is devnet only; mainnet stays behind a later, explicit product decision.
        if (cluster != SolanaCluster.DEVNET) throw WalletException("Mainnet is not available in this version")
    }
}

/**
 * MWA app identity. The domain is a placeholder until Digital Asset Links are hosted at
 * https://<domain>/.well-known/assetlinks.json for the release signing key (handoff §8).
 */
object MwaConfig {
    const val IDENTITY_DOMAIN = "sotreus.app"
    const val IDENTITY_URI = "https://sotreus.app"
    const val ICON_PATH = "favicon.ico"
    const val IDENTITY_NAME = "Sotreus"
    const val SIGN_IN_STATEMENT = "Sign in to Sotreus. This does not authorise any transaction or share any observations."
}
