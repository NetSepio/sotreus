package app.sotreus.integration.solana

import java.io.ByteArrayOutputStream

internal fun ByteArrayOutputStream.compactU16(value: Int) {
    var v = value
    while (true) {
        val b = v and 0x7F
        v = v ushr 7
        if (v == 0) {
            write(b)
            return
        }
        write(b or 0x80)
    }
}
