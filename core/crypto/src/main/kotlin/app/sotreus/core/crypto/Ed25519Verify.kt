package app.sotreus.core.crypto

import org.bouncycastle.math.ec.rfc8032.Ed25519

/** Verifies an Ed25519 signature, e.g. a Sign In With Solana message signed by the wallet. */
object Ed25519Verify {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        publicKey.size == Ed25519.PUBLIC_KEY_SIZE &&
            signature.size == Ed25519.SIGNATURE_SIZE &&
            Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
}
