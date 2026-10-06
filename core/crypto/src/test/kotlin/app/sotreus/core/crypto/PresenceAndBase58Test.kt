package app.sotreus.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceAndBase58Test {

    @Test
    fun tokenMatchesReference() {
        val secret = ByteArray(32) { it.toByte() }
        assertEquals("46f6d9002371f395ecfb3f5980d36951", Presence.token(secret, 5_866_666).toHex())
    }

    @Test
    fun bothFriendsDeriveTheSameSecretAndOthersCannot() {
        val ana = Presence.generateKeyPair()
        val kiran = Presence.generateKeyPair()
        val eve = Presence.generateKeyPair()
        val anaSide = Presence.pairwiseSecret(ana.privateKey, ana.publicKey, kiran.publicKey)
        val kiranSide = Presence.pairwiseSecret(kiran.privateKey, kiran.publicKey, ana.publicKey)
        assertArrayEquals(anaSide, kiranSide)
        assertNotEquals(anaSide.toHex(), Presence.pairwiseSecret(eve.privateKey, eve.publicKey, ana.publicKey).toHex())
    }

    @Test
    fun tokensRotateEachEpochAndTolerateOneEpochOfSkew() {
        val secret = ByteArray(32) { 7 }
        val now = 1_760_000_000L
        val token = Presence.token(secret, Presence.epoch(now))
        assertNotEquals(token.toHex(), Presence.token(secret, Presence.epoch(now) + 1).toHex())
        assertTrue(Presence.matches(secret, token, now + Presence.EPOCH_SECONDS))
        assertFalse(Presence.matches(secret, token, now + 3 * Presence.EPOCH_SECONDS))
        assertFalse(Presence.matches(ByteArray(32) { 8 }, token, now))
    }

    @Test
    fun base58RoundTripsWithLeadingZeros() {
        val raw = byteArrayOf(0, 0, 1, 2, 3, -1)
        assertArrayEquals(raw, Base58.decode(Base58.encode(raw)))
        // The System Program id is 32 zero bytes.
        assertEquals("11111111111111111111111111111111", Base58.encode(ByteArray(32)))
        assertEquals(32, Base58.decode("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr").size)
        assertEquals("7xKp…3fQe", Base58.abbreviate("7xKpAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA3fQe"))
    }
}
