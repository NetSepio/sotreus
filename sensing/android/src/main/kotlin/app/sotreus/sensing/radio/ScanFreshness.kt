package app.sotreus.sensing.radio

/** Converts hardware timestamps to wall time and rejects cached/repeated radio measurements. */
internal class ScanFreshness {
    private val seen = LinkedHashMap<String, Long>()
    private var lastPrunedUs = 0L

    @Synchronized
    fun measuredAt(bssid: String, timestampUs: Long, elapsedUs: Long, wallMs: Long): Long? {
        if (bssid.isBlank() || timestampUs <= 0 || timestampUs > elapsedUs) return null
        val ageUs = elapsedUs - timestampUs
        if (ageUs > MAX_AGE_MS * 1_000 || timestampUs <= (seen[bssid] ?: 0)) return null
        if (elapsedUs - lastPrunedUs >= MAX_AGE_MS * 1_000) {
            seen.entries.removeAll { elapsedUs - it.value > MAX_AGE_MS * 1_000 }
            lastPrunedUs = elapsedUs
        }
        if (seen.size >= 8_192 && bssid !in seen) seen.remove(seen.keys.first())
        seen[bssid] = timestampUs
        return wallMs - ageUs / 1_000
    }

    companion object { const val MAX_AGE_MS = 30_000L }
}
