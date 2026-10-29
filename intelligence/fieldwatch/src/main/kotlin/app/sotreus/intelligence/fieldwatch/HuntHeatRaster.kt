/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit b67f207, HuntHeatMap.kt).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed; the raster returns ARGB pixels instead of an Android
 * bitmap so it stays JVM-only and testable.
 */
package app.sotreus.intelligence.fieldwatch

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One picture of a hunt: [cols] × [rows] premultiplied ARGB, north-up, inside the given bounds. */
class HuntHeatField(
    val pixels: IntArray,
    val cols: Int,
    val rows: Int,
    val north: Double,
    val west: Double,
    val south: Double,
    val east: Double,
    val hotLat: Double?,
    val hotLon: Double?,
)

/**
 * One picture of the hunt, in meters, so a pinch only moves it.
 * Consecutive hears are one line. Color changes along that line.
 * Close parts of the walk fill the ground between them. A far gap stays open.
 * The rim fades that color out. It does not slide the color back toward blue.
 */
fun rasterHuntHeat(trail: List<GpsSample>): HuntHeatField? {
    val hears = ArrayList<Pair<GpsSample, Float>>(trail.size)
    for (sample in trail) {
        val level = HuntHeat.level(sample.rssi) ?: continue
        hears.add(sample to level)
    }
    if (hears.isEmpty()) return null
    var lat0 = 0.0
    var lon0 = 0.0
    for ((sample, _) in hears) {
        lat0 += sample.lat
        lon0 += sample.lon
    }
    lat0 /= hears.size
    lon0 /= hears.size
    val cos0 = cos(Math.toRadians(lat0)).coerceAtLeast(0.2)
    val mLon = 111_320.0 * cos0
    val mLat = 110_540.0
    var minE = Double.POSITIVE_INFINITY
    var maxE = Double.NEGATIVE_INFINITY
    var minN = Double.POSITIVE_INFINITY
    var maxN = Double.NEGATIVE_INFINITY
    val east = DoubleArray(hears.size)
    val north = DoubleArray(hears.size)
    for (i in hears.indices) {
        val sample = hears[i].first
        val e = (sample.lon - lon0) * mLon
        val n = (sample.lat - lat0) * mLat
        east[i] = e
        north[i] = n
        if (e < minE) minE = e
        if (e > maxE) maxE = e
        if (n < minN) minN = n
        if (n > maxN) maxN = n
    }
    val pad = HuntHeat.RIBBON_M
    minE -= pad
    maxE += pad
    minN -= pad
    maxN += pad
    val spanE = (maxE - minE).coerceAtLeast(1.0)
    val spanN = (maxN - minN).coerceAtLeast(1.0)
    var cell = max(CELL_M, max(spanE, spanN) / MAX_HEAT_PX)
    val blobReach = ceil(HuntHeat.RIBBON_M / cell).toInt().coerceAtLeast(1)
    val stamps = hears.size.toLong() * blobReach * blobReach * 8L
    if (stamps > STAMP_BUDGET) cell *= sqrt(stamps / STAMP_BUDGET.toDouble())
    val cols = ceil(spanE / cell).toInt().coerceIn(1, MAX_HEAT_PX)
    val rows = ceil(spanN / cell).toInt().coerceIn(1, MAX_HEAT_PX)
    val count = cols * rows
    val sumW = FloatArray(count)
    val sumHw = FloatArray(count)
    val sumHl = FloatArray(count)
    val peak = FloatArray(count)
    val minDist = FloatArray(count) { Float.POSITIVE_INFINITY }
    val blob = HuntHeat.RIBBON_M
    val linked = BooleanArray(hears.size)
    fun visit(
        e0: Double,
        n0: Double,
        l0: Float,
        e1: Double,
        n1: Double,
        l1: Float,
        onDab: (index: Int, cover: Float, dist: Double, heard: Float) -> Unit,
    ) {
        val dx = e1 - e0
        val dy = n1 - n0
        val len2 = dx * dx + dy * dy
        val colLo = (((min(e0, e1) - blob) - minE) / cell).toInt().coerceIn(0, cols - 1)
        val colHi = (((max(e0, e1) + blob) - minE) / cell).toInt().coerceIn(0, cols - 1)
        val rowLo = ((maxN - (max(n0, n1) + blob)) / cell).toInt().coerceIn(0, rows - 1)
        val rowHi = ((maxN - (min(n0, n1) - blob)) / cell).toInt().coerceIn(0, rows - 1)
        for (row in rowLo..rowHi) {
            val cn = maxN - (row + 0.5) * cell
            val base = row * cols
            for (col in colLo..colHi) {
                val ce = minE + (col + 0.5) * cell
                val t = if (len2 < 1e-4) 0.0 else ((ce - e0) * dx + (cn - n0) * dy) / len2
                val u = t.coerceIn(0.0, 1.0)
                val dist = hypot(ce - (e0 + u * dx), cn - (n0 + u * dy))
                val w = HuntHeat.ribbonWeight(dist)
                if (w <= 0f) continue
                onDab(base + col, w, dist, HuntHeat.alongLevel(l0, l1, u))
            }
        }
    }
    fun nearest(e0: Double, n0: Double, l0: Float, e1: Double, n1: Double, l1: Float) {
        visit(e0, n0, l0, e1, n1, l1) { i, _, dist, _ ->
            if (dist < minDist[i]) minDist[i] = dist.toFloat()
        }
    }
    fun paint(e0: Double, n0: Double, l0: Float, e1: Double, n1: Double, l1: Float) {
        visit(e0, n0, l0, e1, n1, l1) { i, w, dist, heard ->
            val extra = (dist - minDist[i]).coerceAtLeast(0.0)
            val hw = HuntHeat.mixWeight(HuntHeat.shadeWeight(extra), heard)
            sumW[i] += w
            sumHw[i] += hw
            sumHl[i] += hw * heard
            if (w > peak[i]) peak[i] = w
        }
    }
    for (h in 1 until hears.size) {
        val dist = hypot(east[h] - east[h - 1], north[h] - north[h - 1])
        if (!HuntHeat.connects(dist)) continue
        linked[h] = true
        linked[h - 1] = true
    }
    val close = HuntHeat.CLOSE_M
    val bins = HashMap<Long, MutableList<Int>>()
    fun binKey(bx: Int, by: Int): Long = (bx.toLong() shl 32) xor (by.toLong() and 0xffffffffL)
    for (h in hears.indices) {
        val bx = floor(east[h] / close).toInt()
        val by = floor(north[h] / close).toInt()
        bins.getOrPut(binKey(bx, by)) { ArrayList() }.add(h)
    }
    fun eachStroke(draw: (Double, Double, Float, Double, Double, Float) -> Unit) {
        for (h in 1 until hears.size) {
            val dist = hypot(east[h] - east[h - 1], north[h] - north[h - 1])
            if (!HuntHeat.connects(dist)) continue
            draw(
                east[h - 1], north[h - 1], hears[h - 1].second,
                east[h], north[h], hears[h].second,
            )
        }
        for (h in hears.indices) {
            if (linked[h]) continue
            val level = hears[h].second
            draw(east[h], north[h], level, east[h], north[h], level)
        }
        for (h in hears.indices) {
            val bx = floor(east[h] / close).toInt()
            val by = floor(north[h] / close).toInt()
            for (oy in -1..1) {
                for (ox in -1..1) {
                    val list = bins[binKey(bx + ox, by + oy)] ?: continue
                    for (j in list) {
                        if (j <= h) continue
                        val dist = hypot(east[j] - east[h], north[j] - north[h])
                        if (!HuntHeat.bridges(dist)) continue
                        if (j == h + 1 && HuntHeat.connects(dist)) continue
                        draw(
                            east[h], north[h], hears[h].second,
                            east[j], north[j], hears[j].second,
                        )
                    }
                }
            }
        }
    }
    eachStroke(::nearest)
    eachStroke(::paint)
    val pixels = IntArray(count)
    for (i in 0 until count) {
        val weight = sumW[i]
        if (weight < 0.02f) continue
        val fade = peak[i]
        if (fade < 0.04f) continue
        val a = (fade * 208f).toInt().coerceIn(0, 208)
        if (a == 0) continue
        val heatW = sumHw[i]
        val level = if (heatW > 1e-4f) sumHl[i] / heatW else 0f
        val argb = HuntHeat.colorArgb(level)
        val pr = ((argb ushr 16) and 0xFF) * a / 255
        val pg = ((argb ushr 8) and 0xFF) * a / 255
        val pb = (argb and 0xFF) * a / 255
        pixels[i] = (a shl 24) or (pr shl 16) or (pg shl 8) or pb
    }
    val widthM = cols * cell
    val heightM = rows * cell
    val spot = HuntHeat.hotSpot(sumHw, sumHl, peak, cols)
    val hotLat = spot?.let { lat0 + (maxN - it.second * cell) / mLat }
    val hotLon = spot?.let { lon0 + (minE + it.first * cell) / mLon }
    return HuntHeatField(
        pixels = pixels,
        cols = cols,
        rows = rows,
        north = lat0 + maxN / mLat,
        west = lon0 + minE / mLon,
        south = lat0 + (maxN - heightM) / mLat,
        east = lon0 + (minE + widthM) / mLon,
        hotLat = hotLat,
        hotLon = hotLon,
    )
}

private const val MAX_HEAT_PX = 640
private const val CELL_M = 0.18
private const val STAMP_BUDGET = 900_000L

