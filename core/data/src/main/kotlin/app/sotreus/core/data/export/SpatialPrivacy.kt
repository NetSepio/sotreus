package app.sotreus.core.data.export

import app.sotreus.core.crypto.Sha256
import app.sotreus.core.crypto.toHex
import java.security.SecureRandom
import kotlin.math.roundToLong

/** One export gets one random identifier namespace, shared by all of its files. */
class SpatialPrivacy(private val options: ExportOptions) {
    private val salt = ByteArray(16).also(SecureRandom()::nextBytes)
    fun id(raw: String) = if (options.hashIdentifiers) Sha256.hmac(salt, raw.toByteArray()).copyOf(8).toHex() else raw
    fun fix(at: Long, lat: Double?, lon: Double?): SpatialFix? {
        if (options.coordinates == ExportOptions.Coordinates.NONE || lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val time = if (options.timeBucketMinutes > 0) at - at % (options.timeBucketMinutes * 60_000L) else at
        return SpatialFix(time, if (options.coordinates == ExportOptions.Coordinates.COARSE) (lat * 100).roundToLong() / 100.0 else lat,
            if (options.coordinates == ExportOptions.Coordinates.COARSE) (lon * 100).roundToLong() / 100.0 else lon)
    }
}
