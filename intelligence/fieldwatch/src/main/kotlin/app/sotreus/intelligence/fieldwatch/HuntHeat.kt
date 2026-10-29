/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit b67f207).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed; adapted to Sotreus modules.
 */
package app.sotreus.intelligence.fieldwatch

/**
 * Where this phone heard the hunted radio, painted as one ramp.
 * 0 is poor, drawn blue. 1 is powerful, drawn red.
 * The map fades that color out. It does not paint a hard disc of one hue.
 */
object HuntHeat {
    /** A new spot. Shorter than this updates the hear already here. */
    const val STEP_M = 1.5
    const val CAP = 1_200
    /** Solid color this far off a line, then a fade out to [RIBBON_M]. */
    const val COVER_M = 3.6
    /** How far the cloud spreads from a line. Past this the weight is zero. */
    const val RIBBON_M = 6.0
    /** Lines at most this far apart fill the ground between them. */
    const val CLOSE_M = 12.0
    /** Consecutive hears farther apart than this stay separate. */
    const val BRIDGE_M = 22.0
    /** Opening view: meters from the middle of the map to the edge. */
    const val OPEN_HALF_M = 12f
    /** Closest pinch. The live-path map stays wider than this. */
    const val PINCH_HALF_M = 6f
    /** Barely there. This end of the ramp stays blue. */
    const val QUIET_DBM = -100
    /** Red by here, before the phone is standing on the radio. */
    const val POWER_DBM = -50
    /**
     * How far past the nearest bit of the walk a dab may still tint the shade.
     * Farther than this, the cloud fades but the color stays the path's.
     */
    const val SHADE_M = 1.8

    private val stops = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
    private val colors = intArrayOf(
        0xFF1E4BFF.toInt(),
        0xFF00C8FF.toInt(),
        0xFF2DFF6A.toInt(),
        0xFFFFE033.toInt(),
        0xFFFF7A00.toInt(),
        0xFFFF1E1E.toInt(),
    )

    /** Consecutive hears within [BRIDGE_M] are one walk. */
    fun connects(distM: Double): Boolean = distM >= 0.0 && distM <= BRIDGE_M

    /** Two parts of the walk close enough to fill the ground between them. */
    fun bridges(distM: Double): Boolean = distM >= 0.8 && distM <= CLOSE_M

    /**
     * Strength at [distM] meters off a line.
     * Solid through [COVER_M], then a smooth fade. Full across the core, so a line has no hot center.
     */
    fun ribbonWeight(distM: Double): Float {
        if (distM < 0.0 || distM >= RIBBON_M) return 0f
        if (distM <= COVER_M) return 1f
        val t = ((RIBBON_M - distM) / (RIBBON_M - COVER_M)).toFloat()
        return t * t * (3f - 2f * t)
    }

    /** Level along a connected walk. 0 is the first hear, 1 is the next. */
    fun alongLevel(start: Float, end: Float, t: Double): Float {
        val u = t.coerceIn(0.0, 1.0).toFloat()
        return start + (end - start) * u
    }

    /**
     * How much a dab this far from the nearest path still tints the shade.
     * 0 on the path, 0 at [SHADE_M]. The wide cloud uses [ribbonWeight] for fade.
     */
    fun shadeWeight(extraM: Double): Float {
        if (extraM < 0.0 || extraM >= SHADE_M) return 0f
        val t = (1.0 - extraM / SHADE_M).toFloat()
        return t * t
    }

    /**
     * Pull of one dab on a cell. A louder reading leads, and a neighbor
     * still mixes in the shade between the two.
     */
    fun mixWeight(shade: Float, heard: Float): Float {
        if (shade <= 0f || heard < 0f) return 0f
        return shade * (0.25f + 0.75f * heard.coerceIn(0f, 1f))
    }

    /**
     * Smooth ramp. −90 stays blue, the middle shades step with the signal,
     * and −50 and louder is red.
     */
    fun level(rssi: Int): Float? {
        if (!Rssi.measured(rssi)) return null
        val t = ((rssi - QUIET_DBM).toFloat() / (POWER_DBM - QUIET_DBM)).coerceIn(0f, 1f)
        return t * kotlin.math.sqrt(t)
    }

    /**
     * A reading can color the spot where the phone is.
     * After the radio has been quiet, the walk continues with no new color.
     */
    fun paintable(rssi: Int, heardAt: Long, now: Long): Boolean {
        if (level(rssi) == null) return false
        return now - heardAt <= Hunt.QUIET_MS
    }

    /** Opaque ARGB on the ramp. The cloud applies its own fade. */
    fun colorArgb(level: Float): Int {
        val t = level.coerceIn(0f, 1f)
        var i = 0
        while (i < stops.lastIndex && t > stops[i + 1]) i++
        val start = stops[i]
        val end = stops[i + 1]
        val u = if (end == start) 0f else (t - start) / (end - start)
        return lerpArgb(colors[i], colors[i + 1], u)
    }

    /**
     * Paint this place. A louder reading while you are still here raises it.
     * A weaker one does not pull it down. The next spot starts about 1.5 m
     * away and keeps whatever strength is heard there. The oldest hear drops at [CAP].
     */
    fun note(
        trail: List<GpsSample>,
        lat: Double,
        lon: Double,
        rssi: Int,
        at: Long,
    ): List<GpsSample> {
        if (level(rssi) == null) return trail
        if (!PayloadLocation.validCoord(lat, lon)) return trail
        val last = trail.lastOrNull()
        if (last != null && Geo.meters(last.lat, last.lon, lat, lon) < STEP_M) {
            if (rssi < last.rssi + Hunt.STEP_DB) return trail
            return trail.dropLast(1) + last.copy(at = at, rssi = rssi)
        }
        val hear = GpsSample(at, lat, lon, rssi)
        return if (trail.size < CAP) trail + hear else trail.drop(1) + hear
    }

    /** Cloud already on the map, then this session. The oldest hear drops at [CAP]. */
    fun shown(base: List<GpsSample>, session: List<GpsSample>): List<GpsSample> {
        val combined = when {
            base.isEmpty() -> session
            session.isEmpty() -> base
            else -> base + session
        }
        return if (combined.size <= CAP) combined else combined.takeLast(CAP)
    }

    /** Loudest measured hear. A tie keeps the later one. */
    fun loudest(trail: List<GpsSample>): GpsSample? =
        trail.filter { level(it.rssi) != null }
            .maxWithOrNull(compareBy<GpsSample> { it.rssi }.thenBy { it.at })

    /**
     * Center of the hottest painted patch, in cell coordinates (column, row).
     * A faint cell does not count. Cells near the hottest level share the mark,
     * so it sits in the patch instead of on one pixel.
     */
    fun hotSpot(sumW: FloatArray, sumL: FloatArray, fade: FloatArray, cols: Int): Pair<Float, Float>? {
        if (cols <= 0 || sumW.size != sumL.size || sumW.size != fade.size || sumW.isEmpty()) return null
        var best = -1f
        for (i in sumW.indices) {
            val weight = sumW[i]
            if (weight < 0.02f || fade[i] < 0.5f) continue
            val value = sumL[i] / weight
            if (value > best) best = value
        }
        if (best < 0f) return null
        val floor = best - 0.03f
        var sumC = 0.0
        var sumR = 0.0
        var n = 0
        for (i in sumW.indices) {
            val weight = sumW[i]
            if (weight < 0.02f || fade[i] < 0.5f) continue
            if (sumL[i] / weight < floor) continue
            sumC += (i % cols) + 0.5
            sumR += (i / cols) + 0.5
            n++
        }
        if (n == 0) return null
        return (sumC / n).toFloat() to (sumR / n).toFloat()
    }

    /** Same radio keeps the cloud. Reset, or a different radio, starts over. */
    fun resumes(carriedKey: String?, deviceKey: String, fresh: Boolean): Boolean =
        !fresh && carriedKey != null && carriedKey == deviceKey

    /**
     * Follow window. The trail does not widen it. Pinch out shows the whole walk.
     * With no current fix, the last hear is the center.
     */
    fun followModel(
        hereLat: Double?,
        hereLon: Double?,
        trail: List<GpsSample>,
    ): SitPathPlot.Model {
        val here = if (PayloadLocation.validCoord(hereLat, hereLon)) {
            listOf(GpsSample(0L, hereLat!!, hereLon!!))
        } else {
            trail.takeLast(1)
        }
        return SitPathPlot.Model(
            samples = emptyList(),
            dots = emptyList(),
            lengthM = 0.0,
            spanM = 0.0,
            title = "",
            live = true,
            frameSamples = here,
            minHalfSpanM = OPEN_HALF_M,
        )
    }

    private fun lerpArgb(from: Int, to: Int, t: Float): Int {
        fun channel(color: Int, shift: Int) = (color ushr shift) and 0xFF
        fun mix(shift: Int): Int {
            val a = channel(from, shift)
            val b = channel(to, shift)
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
