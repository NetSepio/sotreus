package app.sotreus.integration.solana

import android.net.Uri
import app.sotreus.core.crypto.Base58
import app.sotreus.core.crypto.Ed25519Verify
import app.sotreus.core.data.repository.ChainConfirmation
import app.sotreus.core.data.repository.SolanaGateway
import app.sotreus.core.data.repository.WalletException
import app.sotreus.core.data.repository.WalletSignIn
import app.sotreus.core.data.security.SecretBox
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.model.SolanaCluster
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.common.ProtocolContract
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mobile Wallet Adapter gateway (architecture handoff §8, §15). Mainnet only. Keys and seed
 * never leave the wallet; Sotreus keeps the public key and a Keystore-wrapped auth token.
 */
@Singleton
class MwaSolanaGateway @Inject constructor(
    private val session: WalletSession,
    private val rpc: SolanaRpc,
    private val settings: SettingsRepository,
    private val box: SecretBox,
) : SolanaGateway {
    @Volatile private var walletUriBase: Uri? = null
    @Volatile private var cachedToken: String? = null

    override suspend fun signIn(cluster: SolanaCluster): WalletSignIn {
        requireMainnet(cluster)
        val issuedAt = Instant.now()
        val payload = SignInWithSolana.Payload(
            MwaConfig.IDENTITY_DOMAIN, null as ByteArray?, MwaConfig.SIGN_IN_STATEMENT,
            Uri.parse(MwaConfig.IDENTITY_URI), "1", "mainnet", UUID.randomUUID().toString().replace("-", ""),
            issuedAt.toString(), issuedAt.plusSeconds(300).toString(), null, null, null,
        )
        val signed = open(cluster, chooseWallet = true) { client ->
            val auth = client.authorize(
                Uri.parse(MwaConfig.IDENTITY_URI),
                Uri.parse(MwaConfig.ICON_PATH),
                MwaConfig.IDENTITY_NAME,
                SolanaCluster.MAINNET_BETA.chainId,
                null, // The picker can select a different wallet; start a fresh authorization.
                null,
                null,
                payload,
            ).await()
            val result = auth.signInResult
            if (result != null) {
                SignedIn(auth, result.publicKey, result.signedMessage, result.signature)
            } else {
                // Some wallets (e.g. Jupiter) authorize but ignore the optional sign-in payload. Ask
                // the same wallet, in the same session, to sign the identical SIWS message instead.
                val account = auth.accounts.firstOrNull() ?: throw WalletException("The wallet did not return an account")
                val message = payload.prepareMessage(account.publicKey).toByteArray(Charsets.UTF_8)
                val out = client.signMessagesDetached(arrayOf(message), arrayOf(account.publicKey)).await().messages.firstOrNull()
                    ?: throw WalletException("The wallet did not sign the sign-in message")
                SignedIn(auth, account.publicKey, out.message, out.signatures.firstOrNull() ?: ByteArray(0))
            }
        }
        val auth = signed.auth
        val account = auth.accounts.firstOrNull { it.publicKey.contentEquals(signed.publicKey) }
        val verified = account != null &&
            signed.signedMessage.contentEquals(payload.prepareMessage(signed.publicKey).toByteArray(Charsets.UTF_8)) &&
            Ed25519Verify.verify(signed.publicKey, signed.signedMessage, signed.signature) &&
            Instant.now().isBefore(issuedAt.plusSeconds(300))
        if (verified) {
            saveToken(auth.authToken)
            rememberWallet(auth.walletUriBase)
            settings.setWalletPackage(session.lastChosenPackage())
        }
        return WalletSignIn(
            publicKey = Base58.encode(signed.publicKey),
            walletLabel = account?.accountLabel,
            signatureVerified = verified,
        )
    }

    override suspend fun stampCommitment(commitmentHex: String, payer: String, cluster: SolanaCluster): String {
        requireMainnet(cluster)
        val memo = MemoTransaction.memoFor(commitmentHex)
        return open(cluster) { client ->
            val auth = authorize(client)
            val key = auth.payerBytes(payer)
            val blockhash = Base58.decode(rpc.latestBlockhash(cluster))
            val tx = MemoTransaction.build(key, blockhash, memo, Base58.decode(MemoTransaction.MEMO_PROGRAM_ID))
            Base58.encode(client.signAndSend(tx).signatures.first())
        }
    }

    override suspend fun sendWalletCheck(payer: String, cluster: SolanaCluster): String {
        requireMainnet(cluster)
        return open(cluster) { client ->
            val auth = authorize(client)
            val key = auth.payerBytes(payer)
            val blockhash = Base58.decode(rpc.latestBlockhash(cluster))
            val tx = WalletCheckTransaction.build(key, blockhash, Base58.decode(MemoTransaction.MEMO_PROGRAM_ID))
            Base58.encode(client.signAndSend(tx).signatures.first())
        }
    }

    override fun walletCheckMemo(): String = WalletCheckTransaction.MEMO

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

    /**
     * Local only: Sotreus drops the auth token and the remembered wallet, so the token is never
     * presented again. No wallet is opened. A deauthorize round-trip would have to open a wallet,
     * and without a known handler Android hands that to whichever wallet is the system default
     * (often not the one that issued the token), which then stays on top of Sotreus waiting for a
     * session it cannot serve. The wallet's own connected-apps list can revoke access there.
     */
    override suspend fun disconnect() {
        walletUriBase = null
        saveToken(null)
        settings.setWalletPackage(null)
    }

    override fun signInPreview(address: String?, cluster: SolanaCluster): String = buildString {
        append(MwaConfig.IDENTITY_DOMAIN).append(" wants you to sign in with your Solana account:\n")
        append(address?.let(Base58::abbreviate) ?: "…").append("\n\n")
        append(MwaConfig.SIGN_IN_STATEMENT).append("\n\n")
        append("Chain: ").append(cluster.name.lowercase().substringBefore('_'))
    }

    private suspend fun authorize(client: MobileWalletAdapterClient): MobileWalletAdapterClient.AuthorizationResult {
        val auth = client.authorize(
            Uri.parse(MwaConfig.IDENTITY_URI),
            Uri.parse(MwaConfig.ICON_PATH),
            MwaConfig.IDENTITY_NAME,
            SolanaCluster.MAINNET_BETA.chainId,
            currentToken(),
            null,
            null,
            null,
        ).await()
        saveToken(auth.authToken)
        rememberWallet(auth.walletUriBase)
        return auth
    }

    /** Opens the wallet before any other suspend, then runs [block] on the session. */
    private suspend fun <T> open(cluster: SolanaCluster, chooseWallet: Boolean = false, block: suspend (MobileWalletAdapterClient) -> T): T {
        requireMainnet(cluster)
        return try {
            session.use(walletUriBase, if (chooseWallet) null else settings.walletPackage(), chooseWallet, block)
        } catch (e: WalletException) {
            throw e
        } catch (t: Throwable) {
            throw t.asWalletException()
        }
    }

    private suspend fun currentToken(): String? {
        cachedToken?.let { return it }
        val wrapped = settings.walletAuthTokenWrapped() ?: return null
        return runCatching { String(box.unwrap(wrapped)) }.getOrNull()?.also { cachedToken = it }
    }

    private suspend fun saveToken(token: String?) {
        cachedToken = token
        settings.setWalletAuthTokenWrapped(token?.let { box.wrap(it.toByteArray()) })
    }

    private fun rememberWallet(uri: Uri?) {
        walletUriBase = uri?.takeIf { it.scheme == "https" }
    }

    private fun requireMainnet(cluster: SolanaCluster) {
        if (cluster != SolanaCluster.MAINNET_BETA) throw WalletException("Only Solana mainnet is supported")
    }

    private fun <T> java.util.concurrent.Future<T>.await(timeoutSeconds: Long = 90): T = try {
        get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun MobileWalletAdapterClient.signAndSend(transaction: ByteArray) =
        signAndSendTransactions(arrayOf(transaction), null, null, null, null, null).await()
}

/** A verified-or-not sign-in, from the wallet's sign-in result or the signMessages fallback. */
private class SignedIn(
    val auth: MobileWalletAdapterClient.AuthorizationResult,
    val publicKey: ByteArray,
    val signedMessage: ByteArray,
    val signature: ByteArray,
)

private fun MobileWalletAdapterClient.AuthorizationResult.payerBytes(payer: String): ByteArray {
    val key = accounts.firstOrNull { Base58.encode(it.publicKey) == payer }?.publicKey
        ?: throw WalletException("The open wallet is not the linked wallet")
    if (key.size != 32) throw WalletException("The wallet did not return an account")
    return key
}

private fun Throwable.asWalletException(): WalletException {
    val remote = generateSequence(this) { it.cause }.filterIsInstance<JsonRpc20Client.JsonRpc20RemoteException>().firstOrNull()
    if (remote != null) {
        val declined = remote.code == ProtocolContract.ERROR_AUTHORIZATION_FAILED || remote.code == ProtocolContract.ERROR_NOT_SIGNED
        return WalletException(remote.message, userCancelled = declined)
    }
    if (generateSequence(this) { it.cause }.any { it is LocalAssociationScenario.ConnectionFailedException }) {
        return WalletException("The wallet did not connect. Nothing was sent.")
    }
    if (this is TimeoutException || this.cause is TimeoutException) {
        return WalletException("Timed out waiting for the wallet. Nothing was sent.")
    }
    return WalletException(message ?: javaClass.simpleName)
}

/**
 * MWA app identity. The domain is a placeholder until Digital Asset Links are hosted at
 * https://<domain>/.well-known/assetlinks.json for the release signing key (handoff §8).
 */
object MwaConfig {
    const val IDENTITY_DOMAIN = "sotreus.com"
    const val IDENTITY_URI = "https://sotreus.com"
    /**
     * Relative to [IDENTITY_URI] (MWA requires a relative icon URI). The leading slash makes the
     * URL right both for wallets that resolve it as a URI reference and for wallets that join the
     * two strings, which turned "apple-icon.png" into "https://sotreus.comapple-icon.png".
     */
    const val ICON_PATH = "/apple-icon.png"
    const val IDENTITY_NAME = "Sotreus"
    const val SIGN_IN_STATEMENT = "Sign in to Sotreus. This does not authorise any transaction or share any observations."
}
