package app.sotreus.integration.solana

import app.sotreus.core.crypto.Base58
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletCheckTransactionTest {
    private val payer = ByteArray(32) { 7 }
    private val blockhash = ByteArray(32) { 9 }
    private val memoProgram = Base58.decode(MemoTransaction.MEMO_PROGRAM_ID)

    @Test
    fun memoIsFixedAndCarriesNoRecord() {
        assertEquals("SOTREUS_WALLET_CHECK", WalletCheckTransaction.MEMO)
        assertEquals("11111111111111111111111111111111", Base58.encode(WalletCheckTransaction.SYSTEM_PROGRAM_ID))
    }

    @Test
    fun selfTransferAndMemoLayout() {
        val tx = WalletCheckTransaction.build(payer, blockhash, memoProgram)
        assertEquals(1, tx[0].toInt())
        assertTrue(tx.copyOfRange(1, 65).all { it == 0.toByte() })
        val message = tx.copyOfRange(65, tx.size)
        assertArrayEquals(byteArrayOf(1, 0, 2, 3), message.copyOfRange(0, 4))
        assertArrayEquals(payer, message.copyOfRange(4, 36))
        assertArrayEquals(WalletCheckTransaction.SYSTEM_PROGRAM_ID, message.copyOfRange(36, 68))
        assertArrayEquals(memoProgram, message.copyOfRange(68, 100))
        assertArrayEquals(blockhash, message.copyOfRange(100, 132))
        // Two instructions. Transfer: program 1, accounts [0, 0], 12-byte data, index 2, 0 lamports.
        val transfer = byteArrayOf(2, 1, 2, 0, 0, 12, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertArrayEquals(transfer, message.copyOfRange(132, 150))
        val memo = WalletCheckTransaction.MEMO.toByteArray(Charsets.UTF_8)
        assertArrayEquals(byteArrayOf(2, 0, memo.size.toByte()), message.copyOfRange(150, 153))
        assertEquals(WalletCheckTransaction.MEMO, String(message.copyOfRange(153, message.size)))
    }
}
