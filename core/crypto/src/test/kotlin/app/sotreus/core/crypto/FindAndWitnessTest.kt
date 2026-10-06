package app.sotreus.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class FindAndWitnessTest {
    @Test
    fun lookupIsStableCaseInsensitiveAndDomainSeparated() {
        val a = Find.lookup("ble:5A:A1:0E:7C:21:4B")
        assertEquals(16, a.size)
        assertArrayEquals(a, Find.lookup("BLE:5a:a1:0e:7c:21:4b"))
        assertNotEquals(a.toHex(), Find.lookup("ble:5A:A1:0E:7C:21:4C").toHex())
        assertNotEquals(a.toHex(), Sha256.hash("ble:5A:A1:0E:7C:21:4B".toByteArray()).copyOf(16).toHex())
    }

    @Test
    fun sealOpensOnlyForTheRecipient() {
        val owner = Find.generateKeyPair()
        val other = Find.generateKeyPair()
        val msg = """{"at":1791263690000,"lat":1.2834,"lon":103.8607}""".toByteArray()
        val box = Find.seal(owner.publicKey, msg)
        assertArrayEquals(msg, Find.open(owner.privateKey, owner.publicKey, box))
        assertNull(Find.open(other.privateKey, other.publicKey, box))
        val tampered = box.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(Find.open(owner.privateKey, owner.publicKey, tampered))
        // Two seals of the same message differ (fresh ephemeral key and nonce).
        assertNotEquals(box.toHex(), Find.seal(owner.publicKey, msg).toHex())
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val okm = Hkdf.sha256(
            ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes(),
            salt = "000102030405060708090a0b0c".hexToBytes(),
            info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes(),
            length = 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }

    @Test
    fun witnessTokensRotateAndRecordsVerify() {
        val k = Witness.generateKeys(SecureRandom())
        val slot = Witness.slot(1_791_263_690_000)
        val t1 = Witness.token(k.secret, slot)
        assertEquals(16, t1.size)
        assertArrayEquals(t1, Witness.token(k.secret, slot))
        assertNotEquals(t1.toHex(), Witness.token(k.secret, slot + 1).toHex())
        assertArrayEquals(k.signingPublic, Witness.publicKey(k.signingPrivate))

        val bytes = Witness.recordBytes(k.signingPublic, slot, t1, 12_834_000, 1_038_607_000, 1_791_265_500_000)
        val sig = Witness.sign(k.signingPrivate, bytes)
        assertTrue(Witness.verify(k.signingPublic, bytes, sig))
        val moved = Witness.recordBytes(k.signingPublic, slot, t1, 12_834_001, 1_038_607_000, 1_791_265_500_000)
        assertFalse(Witness.verify(k.signingPublic, moved, sig))
        assertFalse(Witness.verify(Witness.generateKeys().signingPublic, bytes, sig))
        assertEquals(slot * 300_000, Witness.slotStartMs(slot))
    }
}
