package app.sotreus.core.crypto

import java.io.ByteArrayOutputStream

/**
 * Deterministic CBOR (RFC 8949 §4.2.1 core deterministic encoding) for the small value set proof
 * records use: integers, text, bytes, booleans, null, arrays and text-keyed maps. Map keys are
 * sorted by their encoded bytes; integers use the shortest form. The same record always encodes
 * to the same bytes, on any device.
 */
sealed interface Cbor {
    data class Int(val value: Long) : Cbor
    data class Text(val value: String) : Cbor
    class Bytes(val value: ByteArray) : Cbor
    data class Bool(val value: Boolean) : Cbor
    data object Null : Cbor
    data class Array(val items: List<Cbor>) : Cbor
    data class Map(val entries: kotlin.collections.Map<String, Cbor>) : Cbor

    fun encode(): ByteArray = ByteArrayOutputStream().also { write(this, it) }.toByteArray()

    companion object {
        fun map(vararg pairs: Pair<String, Cbor>) = Map(linkedMapOf(*pairs))
        fun of(value: Long) = Int(value)
        fun of(value: String) = Text(value)
        fun ofNullable(value: String?) = value?.let(::Text) ?: Null
        fun ofNullable(value: Long?) = value?.let(::Int) ?: Null

        private fun head(major: kotlin.Int, length: Long, out: ByteArrayOutputStream) {
            val mt = major shl 5
            when {
                length < 24 -> out.write(mt or length.toInt())
                length <= 0xFF -> { out.write(mt or 24); out.write(length.toInt()) }
                length <= 0xFFFF -> { out.write(mt or 25); out.write(u16(length)) }
                length <= 0xFFFF_FFFFL -> { out.write(mt or 26); out.write(u32be(length.toInt())) }
                else -> { out.write(mt or 27); out.write(u64be(length)) }
            }
        }

        private fun u16(v: Long) = byteArrayOf((v ushr 8).toByte(), v.toByte())

        private fun write(value: Cbor, out: ByteArrayOutputStream) {
            when (value) {
                is Int -> if (value.value >= 0) head(0, value.value, out) else head(1, -1 - value.value, out)
                is Text -> value.value.toByteArray(Charsets.UTF_8).let { head(3, it.size.toLong(), out); out.write(it) }
                is Bytes -> { head(2, value.value.size.toLong(), out); out.write(value.value) }
                is Bool -> out.write(if (value.value) 0xF5 else 0xF4)
                Null -> out.write(0xF6)
                is Array -> { head(4, value.items.size.toLong(), out); value.items.forEach { write(it, out) } }
                is Map -> {
                    head(5, value.entries.size.toLong(), out)
                    value.entries
                        .map { (k, v) -> Text(k).encode() to v }
                        .sortedWith { a, b -> compareBytes(a.first, b.first) }
                        .forEach { (k, v) -> out.write(k); write(v, out) }
                }
            }
        }

        private fun compareBytes(a: ByteArray, b: ByteArray): kotlin.Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }
    }
}
