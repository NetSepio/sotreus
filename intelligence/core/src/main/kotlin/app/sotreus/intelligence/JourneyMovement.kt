package app.sotreus.intelligence

import kotlin.math.*

/**
 * Evidence that one broadcast address was heard while the phone moved. Session-scoped and
 * bounded, with coordinates held only in memory. Does not link rotating addresses or infer intent.
 */
class JourneyMovement {
    data class Fix(val atMs: Long, val lat: Double, val lon: Double, val accuracyM: Float?)
    data class Evidence(val locations: Int, val durationMs: Long, val separationM: Int, val samples: Int)
    private data class Track(
        val firstMs: Long,
        var lastMs: Long,
        var samples: Int,
        val anchors: MutableList<Fix>,
        var separationM: Double = 0.0,
    )
    private val tracks = java.util.LinkedHashMap<String, Track>(16, 0.75f, true)
    private var lastPrunedMs: Long? = null

    fun clear() { tracks.clear(); lastPrunedMs = null }

    fun observe(id: String, atMs: Long, fix: Fix?) {
        if (fix == null || !valid(fix) || abs(atMs - fix.atMs) > FIX_SKEW_MS) return
        if (lastPrunedMs == null || atMs - lastPrunedMs!! >= 30_000) {
            tracks.entries.removeAll { atMs - it.value.lastMs > MAX_GAP_MS }
            lastPrunedMs = atMs
        }
        val track = tracks[id]?.takeIf { atMs - it.lastMs <= MAX_GAP_MS }
        if (track != null && atMs <= track.lastMs) return
        if (track == null) {
            if (tracks.size >= MAX_TRACKS) tracks.remove(tracks.keys.first())
            tracks[id] = Track(atMs, atMs, 1, mutableListOf(fix))
            return
        }
        if (atMs - track.lastMs < SAMPLE_STEP_MS) return
        track.lastMs = atMs
        track.samples++
        val lastAnchor = track.anchors.last()
        if (fix.atMs - lastAnchor.atMs < ANCHOR_STEP_MS) return
        // Every new anchor must be outside every previous anchor's radio/GPS uncertainty region.
        if (track.anchors.any { distance(it, fix) < MIN_SEPARATION_M + it.accuracyM!! + fix.accuracyM!! }) return
        track.separationM = maxOf(track.separationM, track.anchors.maxOf { distance(it, fix) })
        if (track.anchors.size < MAX_ANCHORS) track.anchors += fix
    }

    fun evidence(id: String, now: Long): Evidence? {
        val t = tracks[id] ?: return null
        if (now < t.lastMs || now - t.lastMs > MAX_GAP_MS || t.anchors.size < MIN_LOCATIONS ||
            t.samples < MIN_SAMPLES || t.lastMs - t.firstMs < MIN_DURATION_MS || t.separationM < MIN_SPAN_M
        ) return null
        return Evidence(t.anchors.size, t.lastMs - t.firstMs, t.separationM.roundToInt(), t.samples)
    }

    private fun valid(f: Fix) = f.lat.isFinite() && f.lon.isFinite() && f.lat in -90.0..90.0 &&
        f.lon in -180.0..180.0 && f.accuracyM != null && f.accuracyM.isFinite() && f.accuracyM in 0f..MAX_ACCURACY_M

    private fun distance(a: Fix, b: Fix): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    companion object {
        const val MIN_DURATION_MS = 8 * 60_000L
        const val MIN_LOCATIONS = 3
        const val MIN_SAMPLES = 12
        const val MIN_SEPARATION_M = 300.0
        const val MIN_SPAN_M = 600.0
        const val MAX_GAP_MS = 120_000L
        private const val SAMPLE_STEP_MS = 10_000L
        private const val ANCHOR_STEP_MS = 2 * 60_000L
        private const val FIX_SKEW_MS = 20_000L
        private const val MAX_ACCURACY_M = 50f
        private const val MAX_TRACKS = 2_048
        private const val MAX_ANCHORS = 64
    }
}
