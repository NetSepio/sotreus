package app.sotreus.integration.solana

import java.io.ByteArrayOutputStream

/**
 * A legacy Solana transaction with one Memo instruction, built by hand so that nothing but the
 * memo text can enter it. The fee payer is the wallet; the memo is the batch commitment.
 */
object MemoTransaction {
    /** SPL Memo program v2. */
    const val MEMO_PROGRAM_ID = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"

    /** The only text that goes on-chain: schema tag + 32-byte commitment as hex. */
    fun memoFor(commitmentHex: String): String {
        require(commitmentHex.length == 64 && commitmentHex.all { it in "0123456789abcdef" }) { "commitment must be 32 bytes of lowercase hex" }
        return "SOTREUS_BATCH_V1:$commitmentHex"
    }

    fun build(payer: ByteArray, recentBlockhash: ByteArray, memo: String, memoProgram: ByteArray): ByteArray {
        require(payer.size == 32 && recentBlockhash.size == 32 && memoProgram.size == 32)
        val data = memo.toByteArray(Charsets.UTF_8)
        val message = ByteArrayOutputStream().apply {
            write(1) // required signatures: the payer
            write(0) // read-only signed accounts
            write(1) // read-only unsigned accounts: the memo program
            compactU16(2)
            write(payer)
            write(memoProgram)
            write(recentBlockhash)
            compactU16(1) // one instruction
            write(1) // program id index
            compactU16(0) // no accounts: the memo is not tied to any signer list
            compactU16(data.size)
            write(data)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            compactU16(1)
            write(ByteArray(64)) // signature placeholder filled by the wallet
            write(message)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.compactU16(value: Int) {
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v == 0) { write(b); return }
            write(b or 0x80)
        }
    }
}
