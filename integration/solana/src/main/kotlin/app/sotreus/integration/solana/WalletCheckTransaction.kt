package app.sotreus.integration.solana

import java.io.ByteArrayOutputStream

/**
 * A mainnet legacy transaction that moves 0 lamports from the wallet back to itself and attaches
 * one fixed memo. The memo is not a proof and contains no observation, place or identifier.
 */
object WalletCheckTransaction {
    /** System program. 32 zero bytes, base58 11111111111111111111111111111111. */
    val SYSTEM_PROGRAM_ID = ByteArray(32)

    /** Transfer instruction index inside the system program. */
    private const val TRANSFER = 2

    const val MEMO = "SOTREUS_WALLET_CHECK"

    fun build(payer: ByteArray, recentBlockhash: ByteArray, memoProgram: ByteArray): ByteArray {
        require(payer.size == 32 && recentBlockhash.size == 32 && memoProgram.size == 32)
        val memo = MEMO.toByteArray(Charsets.UTF_8)
        val transfer = ByteArray(12).also { it[0] = TRANSFER.toByte() }
        val message = ByteArrayOutputStream().apply {
            write(1) // required signatures: the payer
            write(0) // read-only signed accounts
            write(2) // read-only unsigned: system program, memo program
            compactU16(3)
            write(payer)
            write(SYSTEM_PROGRAM_ID)
            write(memoProgram)
            write(recentBlockhash)
            compactU16(2)
            write(1) // system program
            compactU16(2)
            write(0) // source
            write(0) // destination, the same account
            compactU16(transfer.size)
            write(transfer)
            write(2) // memo program
            compactU16(0)
            compactU16(memo.size)
            write(memo)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            compactU16(1)
            write(ByteArray(64))
            write(message)
        }.toByteArray()
    }
}
