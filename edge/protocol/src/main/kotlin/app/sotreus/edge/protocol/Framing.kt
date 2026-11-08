package app.sotreus.edge.protocol

/**
 * Reassembles the `control` / `events` byte stream: UTF-8 JSON objects, each ending in `\n`,
 * split into chunks that follow the ATT MTU (protocol §1). Bytes are buffered until a newline,
 * so a multi-byte character split across chunks decodes correctly.
 */
class NdjsonFramer(private val maxLineBytes: Int = 64 * 1024) {
    private var buf = ByteArray(1024)
    private var fill = 0
    private var discarding = false

    /** Lines dropped because they exceeded [maxLineBytes]. */
    var overflows: Int = 0
        private set

    fun feed(chunk: ByteArray): List<String> {
        val out = ArrayList<String>(1)
        for (b in chunk) {
            if (b == NEWLINE) {
                if (!discarding && fill > 0) {
                    val line = buf.decodeToString(0, fill).trim()
                    if (line.isNotEmpty()) out += line
                }
                fill = 0
                discarding = false
                continue
            }
            if (discarding) continue
            if (fill == maxLineBytes) {
                overflows++
                discarding = true
                fill = 0
                continue
            }
            if (fill == buf.size) buf = buf.copyOf((buf.size * 2).coerceAtMost(maxLineBytes))
            buf[fill++] = b
        }
        return out
    }

    fun reset() {
        fill = 0
        discarding = false
    }

    companion object {
        private const val NEWLINE = '\n'.code.toByte()

        /** One command line, newline-terminated. */
        fun frame(json: String): ByteArray = (json.trimEnd('\n') + "\n").encodeToByteArray()

        /** Splits [bytes] into writes of at most [size] bytes (MTU − 3). */
        fun chunks(bytes: ByteArray, size: Int): List<ByteArray> {
            require(size > 0)
            return (bytes.indices step size).map { bytes.copyOfRange(it, (it + size).coerceAtMost(bytes.size)) }
        }
    }
}

/** Reassembles back-to-back 256-byte records from `records` notifications by counting bytes. */
class RecordStreamAssembler(private val size: Int = EdgeProtocol.RECORD_SIZE) {
    private val buf = ByteArray(size)
    private var fill = 0

    fun feed(chunk: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(2)
        var i = 0
        while (i < chunk.size) {
            val n = minOf(size - fill, chunk.size - i)
            chunk.copyInto(buf, fill, i, i + n)
            fill += n
            i += n
            if (fill == size) {
                out += buf.copyOf()
                fill = 0
            }
        }
        return out
    }

    /** Bytes held for an incomplete record (non-zero after a dropped link means a partial record). */
    val pending: Int get() = fill

    fun reset() {
        fill = 0
    }
}
