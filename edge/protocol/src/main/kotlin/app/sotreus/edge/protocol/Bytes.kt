package app.sotreus.edge.protocol

/** Little-endian reads over a byte array, offsets relative to [base]. */
internal class LeReader(private val b: ByteArray, private val base: Int = 0) {
    fun u8(off: Int): Int = b[base + off].toInt() and 0xFF
    fun s8(off: Int): Int = b[base + off].toInt()
    fun u16(off: Int): Int = u8(off) or (u8(off + 1) shl 8)
    fun u32(off: Int): Long = u16(off).toLong() or (u16(off + 2).toLong() shl 16)
    fun s32(off: Int): Int = u32(off).toInt()
    fun s64(off: Int): Long = u32(off) or (u32(off + 4) shl 32)
    fun bytes(off: Int, len: Int): ByteArray = b.copyOfRange(base + off, base + off + len)
}

/** Little-endian writes, used by the record encoder (tests and the fake Edge). */
internal class LeWriter(val b: ByteArray, private val base: Int = 0) {
    fun u8(off: Int, v: Int) { b[base + off] = v.toByte() }
    fun u16(off: Int, v: Int) { u8(off, v); u8(off + 1, v ushr 8) }
    fun u32(off: Int, v: Long) { u16(off, (v and 0xFFFF).toInt()); u16(off + 2, ((v ushr 16) and 0xFFFF).toInt()) }
    fun s64(off: Int, v: Long) { u32(off, v and 0xFFFFFFFFL); u32(off + 4, (v ushr 32) and 0xFFFFFFFFL) }
    fun bytes(off: Int, src: ByteArray) { src.copyInto(b, base + off) }
}

object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { val v = it.toInt() and 0xFF; append(DIGITS[v ushr 4]); append(DIGITS[v and 0x0F]) }
    }

    /** Null on odd length or a non-hex character. */
    fun decode(hex: String): ByteArray? {
        val s = hex.trim()
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = s[2 * i].digitToIntOrNull(16) ?: return null
            val lo = s[2 * i + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/** MAC addresses in display order, upper case, colon separated, as the firmware prints them. */
object Mac {
    fun format(bytes: ByteArray): String = Hex.encode(bytes).chunked(2).joinToString(":")

    fun parse(text: String): ByteArray? {
        val hex = text.filter { it != ':' && it != '-' }
        if (hex.length != 12) return null
        return Hex.decode(hex)
    }

    /** Locally administered bit: a private (random) address with no vendor prefix. */
    fun isLocallyAdministered(mac: String): Boolean {
        val first = mac.take(2).toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }
}

/** CRC-32 (zlib / IEEE 802.3, reflected 0xEDB88320), the record trailer. Pure Kotlin for KMP. */
object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Long {
        var c = 0xFFFFFFFF.toInt()
        for (i in offset until offset + length) c = table[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        return (c xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL
    }
}

/** FNV-1a-32 of `salt ‖ data`, matching the firmware's `edge_fnv1a` for probe names. */
object ProbeNameHash {
    fun of(salt: ByteArray, name: ByteArray): Long {
        var h = 0x811C9DC5.toInt()
        for (b in salt) h = (h xor (b.toInt() and 0xFF)) * 0x01000193
        for (b in name) h = (h xor (b.toInt() and 0xFF)) * 0x01000193
        return h.toLong() and 0xFFFFFFFFL
    }

    /** Hash of a network name the user typed, with the Edge's `info.probe_salt` (hex). */
    fun of(saltHex: String, name: String): Long? = Hex.decode(saltHex)?.let { of(it, name.encodeToByteArray()) }
}
