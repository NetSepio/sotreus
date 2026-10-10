package app.sotreus.intelligence

/**
 * Open Location Code ("plus code") encoding, reimplemented from the published algorithm
 * (github.com/google/open-location-code). Only the pair section is produced: 10 digits is a cell of
 * about 14 × 14 m, short enough to tag an observation with where the phone was.
 */
object PlusCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val BASE = 20
    private const val SEPARATOR_POSITION = 8
    private const val PAIR_LENGTH = 10

    /** Integer grid of the reference implementation (1/8000° pairs × the 5-digit grid section). */
    private const val LAT_MULTIPLIER = 8000L * 3125L
    private const val LON_MULTIPLIER = 8000L * 1024L
    private const val LAT_GRID = 3125L
    private const val LON_GRID = 1024L

    /** About 14 m: the length used to tag observations. */
    const val TAG_LENGTH = 10

    /** About 5.5 km: what a privacy-reduced export keeps. */
    const val COARSE_LENGTH = 6

    /** Encodes [lat], [lon] as a plus code of an even [length] from 2 to 10, e.g. `7FG49QCJ+2V`. */
    fun encode(lat: Double, lon: Double, length: Int = TAG_LENGTH): String {
        require(length in 2..PAIR_LENGTH && length % 2 == 0) { "length must be even, 2..10" }
        var lng = lon
        while (lng < -180.0) lng += 360.0
        while (lng >= 180.0) lng -= 360.0
        // Same rounding as the reference so floating point noise can't move a value into the next cell.
        var latVal = Math.round((lat.coerceIn(-90.0, 90.0) + 90.0) * LAT_MULTIPLIER * 1e6) / 1_000_000 / LAT_GRID
        var lonVal = Math.round((lng + 180.0) * LON_MULTIPLIER * 1e6) / 1_000_000 / LON_GRID
        // Latitude 90 belongs to the top row; longitude 180 wraps to -180.
        latVal = latVal.coerceAtMost(180L * 8000L - 1)
        lonVal %= 360L * 8000L
        val digits = CharArray(PAIR_LENGTH)
        for (i in PAIR_LENGTH / 2 - 1 downTo 0) {
            digits[i * 2] = ALPHABET[(latVal % BASE).toInt()]
            digits[i * 2 + 1] = ALPHABET[(lonVal % BASE).toInt()]
            latVal /= BASE
            lonVal /= BASE
        }
        return format(String(digits, 0, length))
    }

    /** Shortens a code to [length] digits (even, at most 8), padding like the reference, e.g. `7FG49Q00+`. */
    fun coarsen(code: String, length: Int): String {
        require(length in 2..SEPARATOR_POSITION && length % 2 == 0) { "length must be even, 2..8" }
        val digits = code.replace("+", "").trimEnd('0')
        return format(digits.take(length))
    }

    private fun format(digits: String): String {
        val padded = digits.padEnd(SEPARATOR_POSITION, '0')
        return padded.substring(0, SEPARATOR_POSITION) + "+" + padded.substring(SEPARATOR_POSITION)
    }
}
