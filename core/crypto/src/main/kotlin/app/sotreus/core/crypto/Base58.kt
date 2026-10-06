package app.sotreus.core.crypto

import java.math.BigInteger

/** Bitcoin-alphabet Base58, as used for Solana addresses and signatures. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var n = BigInteger(1, input)
        val sb = StringBuilder()
        while (n > BigInteger.ZERO) {
            val (q, r) = n.divideAndRemainder(BASE)
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        input.takeWhile { it.toInt() == 0 }.forEach { _ -> sb.append('1') }
        return sb.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        var n = BigInteger.ZERO
        input.forEach { c ->
            val digit = ALPHABET.indexOf(c)
            require(digit >= 0) { "invalid Base58 character" }
            n = n.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        val body = n.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        val zeros = input.takeWhile { it == '1' }.length
        return ByteArray(zeros) + if (n == BigInteger.ZERO) ByteArray(0) else body
    }

    /** "7xKp…3fQe": first and last four characters, for display only. */
    fun abbreviate(address: String): String =
        if (address.length <= 10) address else "${address.take(4)}…${address.takeLast(4)}"
}
