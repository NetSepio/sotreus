package app.sotreus.core.crypto

import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.SecureRandom

/**
 * Fixed-witness attestation.
 *
 * A witness is a Sotreus phone left at a saved place. It advertises a rotating [token] per 5-minute
 * [slot] (no stable identifier on air). Some time after each slot ends it publishes a [signed]
 * record — "this witness, at this place, broadcast this token in this slot" — that anyone can
 * [verify] with the witness's Ed25519 key. A traveller who heard the token and committed it in a
 * stamped proof before the record was published shows they were near the witness in that slot.
 */
object Witness {
    const val TOKEN_DOMAIN = "SOTREUS_WITNESS_V1"
    const val RECORD_TYPE = "witness_slot"
    const val SLOT_SECONDS = 300L
    const val TOKEN_BYTES = 16

    class Keys(val secret: ByteArray, val signingPrivate: ByteArray, val signingPublic: ByteArray)

    fun generateKeys(random: SecureRandom = SecureRandom()): Keys {
        val secret = ByteArray(32).also(random::nextBytes)
        val priv = ByteArray(Ed25519.SECRET_KEY_SIZE).also(random::nextBytes)
        val pub = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
        Ed25519.generatePublicKey(priv, 0, pub, 0)
        return Keys(secret, priv, pub)
    }

    fun publicKey(signingPrivate: ByteArray): ByteArray =
        ByteArray(Ed25519.PUBLIC_KEY_SIZE).also { Ed25519.generatePublicKey(signingPrivate, 0, it, 0) }

    fun slot(unixMs: Long): Long = Math.floorDiv(unixMs / 1000, SLOT_SECONDS)

    fun slotStartMs(slot: Long): Long = slot * SLOT_SECONDS * 1000

    fun token(secret: ByteArray, slot: Long): ByteArray = Sha256.hmac(secret, ascii(TOKEN_DOMAIN), u64be(slot)).copyOf(TOKEN_BYTES)

    /** The bytes a witness signs. Deterministic CBOR, so any verifier rebuilds them exactly. */
    fun recordBytes(witnessPublic: ByteArray, slot: Long, token: ByteArray, latE7: Long, lonE7: Long, publishedAtMs: Long): ByteArray =
        Cbor.map(
            "type" to Cbor.of(RECORD_TYPE),
            "v" to Cbor.of(1),
            "witness" to Cbor.Bytes(witnessPublic),
            "slot" to Cbor.of(slot),
            "token" to Cbor.Bytes(token),
            "lat_e7" to Cbor.of(latE7),
            "lon_e7" to Cbor.of(lonE7),
            "published_at_ms" to Cbor.of(publishedAtMs),
        ).encode()

    fun sign(signingPrivate: ByteArray, message: ByteArray): ByteArray =
        ByteArray(Ed25519.SIGNATURE_SIZE).also { Ed25519.sign(signingPrivate, 0, message, 0, message.size, it, 0) }

    fun verify(witnessPublic: ByteArray, message: ByteArray, signature: ByteArray): Boolean = Ed25519Verify.verify(witnessPublic, message, signature)
}
