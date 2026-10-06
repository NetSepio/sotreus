package app.sotreus.core.crypto

import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Lost-device finding.
 *
 * The owner publishes a [lookup] for a lost device (a domain-separated hash of its radio
 * fingerprint) and a fresh X25519 public key. A finder whose phone sees a device with the same
 * lookup [seal]s a sighting to that key; only the owner can [open] it. The relay in between holds
 * lookups and opaque ciphertext.
 */
object Find {
    const val LOOKUP_DOMAIN = "SOTREUS_FIND_LOOKUP_V1"
    const val SEAL_DOMAIN = "SOTREUS_FIND_SEAL_V1"
    const val LOOKUP_BYTES = 16
    private const val NONCE_BYTES = 12

    fun lookup(fingerprintKey: String): ByteArray =
        Sha256.hash(ascii(LOOKUP_DOMAIN), fingerprintKey.uppercase().toByteArray(Charsets.UTF_8)).copyOf(LOOKUP_BYTES)

    fun generateKeyPair(random: SecureRandom = SecureRandom()) = Presence.generateKeyPair(random)

    /** ephemeral public key (32) ‖ nonce (12) ‖ AES-256-GCM ciphertext and tag. */
    fun seal(recipientPublic: ByteArray, plaintext: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val eph = generateKeyPair(random)
        val key = sharedKey(eph.privateKey, eph.publicKey, recipientPublic, recipientPublic)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(ascii(SEAL_DOMAIN))
        return eph.publicKey + nonce + cipher.doFinal(plaintext)
    }

    /** Returns null when the box was not sealed to this key or was altered. */
    fun open(recipientPrivate: ByteArray, recipientPublic: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < X25519.POINT_SIZE + NONCE_BYTES + 16) return null
        val ephPublic = sealed.copyOfRange(0, X25519.POINT_SIZE)
        val nonce = sealed.copyOfRange(X25519.POINT_SIZE, X25519.POINT_SIZE + NONCE_BYTES)
        val key = runCatching { sharedKey(recipientPrivate, ephPublic, ephPublic, recipientPublic) }.getOrNull() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(ascii(SEAL_DOMAIN))
            cipher.doFinal(sealed, X25519.POINT_SIZE + NONCE_BYTES, sealed.size - X25519.POINT_SIZE - NONCE_BYTES)
        }.getOrNull()
    }

    /** X25519 agreement, then HKDF-SHA256 bound to both public keys. */
    private fun sharedKey(myPrivate: ByteArray, ephPublic: ByteArray, peerPublic: ByteArray, recipientPublic: ByteArray): ByteArray {
        val shared = ByteArray(X25519.POINT_SIZE)
        check(X25519.calculateAgreement(myPrivate, 0, peerPublic, 0, shared, 0)) { "invalid key" }
        return Hkdf.sha256(shared, salt = ephPublic + recipientPublic, info = ascii(SEAL_DOMAIN), length = 32)
    }
}

/** RFC 5869 HKDF with HMAC-SHA256. */
object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Sha256.hmac(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = Sha256.hmac(prk, t, info, byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }
}
