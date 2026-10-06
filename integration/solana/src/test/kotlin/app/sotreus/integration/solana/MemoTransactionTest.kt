package app.sotreus.integration.solana

import app.sotreus.core.crypto.Base58
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoTransactionTest {
    private val payer = ByteArray(32) { 1 }
    private val blockhash = ByteArray(32) { 2 }
    private val memoProgram = Base58.decode(MemoTransaction.MEMO_PROGRAM_ID)
    private val commitment = "ab".repeat(32)

    @Test
    fun memoCarriesOnlyTheCommitment() {
        assertEquals("SOTREUS_BATCH_V1:$commitment", MemoTransaction.memoFor(commitment))
        // Anything that is not a 32-byte hex commitment is refused, so raw data cannot be stamped.
        assertThrows(IllegalArgumentException::class.java) { MemoTransaction.memoFor("Office 12.34,56.78") }
        assertThrows(IllegalArgumentException::class.java) { MemoTransaction.memoFor("AB".repeat(32)) }
    }

    @Test
    fun legacyLayoutIsExact() {
        val memo = MemoTransaction.memoFor(commitment)
        val tx = MemoTransaction.build(payer, blockhash, memo, memoProgram)
        assertEquals(1, tx[0].toInt())
        assertTrue(tx.copyOfRange(1, 65).all { it == 0.toByte() })
        val m = tx.copyOfRange(65, tx.size)
        assertArrayEquals(byteArrayOf(1, 0, 1, 2), m.copyOfRange(0, 4))
        assertArrayEquals(payer, m.copyOfRange(4, 36))
        assertArrayEquals(memoProgram, m.copyOfRange(36, 68))
        assertArrayEquals(blockhash, m.copyOfRange(68, 100))
        assertArrayEquals(byteArrayOf(1, 1, 0, memo.length.toByte()), m.copyOfRange(100, 104))
        assertEquals(memo, String(m.copyOfRange(104, m.size)))
        assertEquals(65 + 104 + memo.length, tx.size)
    }
}
