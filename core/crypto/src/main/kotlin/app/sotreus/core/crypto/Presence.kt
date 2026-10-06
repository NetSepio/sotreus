package app.sotreus.core.crypto

import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * Nearby-presence cryptography (architecture handoff §17.3).
 *
 * Each install has an app-generated X25519 presence key, separate from any account or wallet.
 * Pairing two friends (both scan each other's QR) derives a pairwise secret; while Nearby presence
 * is on, the phone advertises short tokens derived from each pairwise secret and a 5-minute epoch.
 * Tokens are unlinkable to anyone without the secret and change every epoch.
 */
object Presence {
    const val TOKEN_DOMAIN = "SOTREUS_PRESENCE_V1"
    const val PAIR_DOMAIN = "SOTREUS_PAIR_V1"
    const val EPOCH_SECONDS = 300L
    const val TOKEN_BYTES = 16

    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val priv = ByteArray(X25519.SCALAR_SIZE)
        X25519.generatePrivateKey(random, priv)
        val pub = ByteArray(X25519.POINT_SIZE)
        X25519.generatePublicKey(priv, 0, pub, 0)
        return KeyPair(priv, pub)
    }

    /** Same value on both phones: the public keys are ordered so either side derives it. */
    fun pairwiseSecret(myPrivate: ByteArray, myPublic: ByteArray, theirPublic: ByteArray): ByteArray {
        val shared = ByteArray(X25519.POINT_SIZE)
        check(X25519.calculateAgreement(myPrivate, 0, theirPublic, 0, shared, 0)) { "invalid peer key" }
        val (a, b) = listOf(myPublic, theirPublic).sortedWith { x, y -> x.toHex().compareTo(y.toHex()) }
        return Sha256.hash(ascii(PAIR_DOMAIN), shared, a, b)
    }

    fun epoch(unixSeconds: Long): Long = Math.floorDiv(unixSeconds, EPOCH_SECONDS)

    fun token(pairwiseSecret: ByteArray, epoch: Long): ByteArray =
        Sha256.hmac(pairwiseSecret, ascii(TOKEN_DOMAIN), u64be(epoch)).copyOf(TOKEN_BYTES)

    /** Accepts the current epoch and one either side, to tolerate clock skew between phones. */
    fun matches(pairwiseSecret: ByteArray, observed: ByteArray, unixSeconds: Long): Boolean {
        val e = epoch(unixSeconds)
        return (e - 1..e + 1).any { token(pairwiseSecret, it).contentEquals(observed) }
    }
}
