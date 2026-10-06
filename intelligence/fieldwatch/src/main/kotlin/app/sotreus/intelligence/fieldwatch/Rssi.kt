/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit e71b9b2).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed.
 */
package app.sotreus.intelligence.fieldwatch

/**
 * BLE/Wi-Fi RSSI as this phone received it (dBm). Bluetooth uses 127 for
 * “not available”; that is not transmit power and not a real hear.
 */
object Rssi {
    fun measured(rssi: Int): Boolean = rssi in -127..126

    fun sessionRange(min: Int, max: Int, history: List<RssiSample> = emptyList()): String {
        val vals = ArrayList<Int>(history.size + 2)
        if (measured(min)) vals += min
        if (measured(max)) vals += max
        for (s in history) if (measured(s.rssi)) vals += s.rssi
        if (vals.isEmpty()) return "Not available"
        val lo = vals.min()
        val hi = vals.max()
        return if (lo == hi) "$lo dBm" else "$lo to $hi dBm"
    }

    fun lastMeasured(rssi: Int, history: List<RssiSample>): Int? {
        if (measured(rssi)) return rssi
        return history.asReversed().firstOrNull { measured(it.rssi) }?.rssi
    }
}
