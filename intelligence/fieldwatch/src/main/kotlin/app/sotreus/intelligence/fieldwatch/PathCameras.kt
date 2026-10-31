/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit 4f74cec).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed; report text left to Sotreus string resources, so the
 * rows are an enum instead of language-pack keys.
 */
package app.sotreus.intelligence.fieldwatch

import kotlin.math.cos
import kotlin.math.hypot

/** Rows of the "known cameras on path" count. Gunshot detectors are not cameras and have no row. */
enum class CameraRow { FLOCK, PLATE, SPEED, RED, CAMERA }

/**
 * Cameras from the lists on this phone that the path came near.
 * A stored direction counts only when it pointed toward the path.
 * No stored direction counts as on the path. A direction that pointed
 * away is left out. Gunshot detectors are not cameras and are left out.
 * The corridor is [FlockApproach.PATH_M].
 */
object PathCameras {
    private const val STEP_M = 40.0
    private val searchM = FlockApproach.PATH_M + STEP_M / 2.0

    data class Count(val row: CameraRow, val facing: Int, val near: Int)

    data class Tally(
        val pathReady: Boolean,
        val counts: List<Count>,
        /** Facing the path, or on the path with no stored direction. */
        val cameras: List<FlockCamera> = emptyList(),
    )

    fun tally(path: List<GpsSample>, near: (Double, Double, Double) -> List<FlockCamera>): Tally {
        val kept = keptPoints(path)
        if (kept.isEmpty()) return Tally(pathReady = false, counts = emptyList())
        val best = HashMap<Seen, Hit>()
        for (i in kept.indices) {
            val here = kept[i]
            val prev = if (i == 0) here else kept[i - 1]
            val found = near(here.lat, here.lon, searchM)
            for (cam in found) {
                val row = rowOf(cam) ?: continue
                val approach = approach(prev.lat, prev.lon, here.lat, here.lon, cam.lat, cam.lon)
                if (approach.meters > FlockApproach.PATH_M) continue
                val rank = if (cam.facings.isEmpty()) {
                    NEAR
                } else if (looking(cam, approach.lat, approach.lon)) {
                    FACING
                } else {
                    AWAY
                }
                val id = Seen(cam.key, row)
                val old = best[id]
                if (old == null || rank > old.rank) best[id] = Hit(rank, cam)
            }
        }
        val facing = IntArray(CameraRow.entries.size)
        val onPath = IntArray(CameraRow.entries.size)
        val cameras = ArrayList<FlockCamera>()
        for ((id, hit) in best) {
            when (hit.rank) {
                FACING -> {
                    facing[id.row.ordinal]++
                    cameras.add(hit.camera)
                }
                NEAR -> {
                    onPath[id.row.ordinal]++
                    cameras.add(hit.camera)
                }
            }
        }
        cameras.sortBy { it.key }
        val counts = CameraRow.entries.mapNotNull { row ->
            val face = facing[row.ordinal]
            val nearCount = onPath[row.ordinal]
            if (face == 0 && nearCount == 0) null else Count(row, face, nearCount)
        }
        return Tally(pathReady = true, counts, cameras)
    }

    fun rowOf(cam: FlockCamera): CameraRow? = when (cam.kind) {
        SensorKind.GUNSHOT -> null
        SensorKind.PLATE -> if (cam.flock) CameraRow.FLOCK else CameraRow.PLATE
        SensorKind.SPEED -> CameraRow.SPEED
        SensorKind.RED -> CameraRow.RED
        SensorKind.CAMERA -> CameraRow.CAMERA
    }

    private data class Seen(val key: Long, val row: CameraRow)

    private data class Hit(val rank: Int, val camera: FlockCamera)

    private fun looking(cam: FlockCamera, lat: Double, lon: Double): Boolean {
        val toPath = Geo.bearingDegrees(cam.lat, cam.lon, lat, lon)
        return cam.facings.any { FlockApproach.delta(it.toDouble(), toPath) <= FlockApproach.LOOK_DEG }
    }

    private fun keptPoints(path: List<GpsSample>): List<GpsSample> {
        val valid = path.filter { PayloadLocation.validCoord(it.lat, it.lon) }
        if (valid.isEmpty()) return emptyList()
        val out = ArrayList<GpsSample>()
        var last = valid.first()
        out.add(last)
        for (i in 1 until valid.size) {
            val sample = valid[i]
            if (Geo.meters(last.lat, last.lon, sample.lat, sample.lon) >= STEP_M) {
                out.add(sample)
                last = sample
            }
        }
        val end = valid.last()
        if (out.last() !== end && Geo.meters(out.last().lat, out.last().lon, end.lat, end.lon) > 0.5) {
            out.add(end)
        }
        return out
    }

    private data class Approach(val meters: Double, val lat: Double, val lon: Double)

    /** Closest point on the segment from [a] to [b], in local meters. */
    private fun approach(aLat: Double, aLon: Double, bLat: Double, bLon: Double, pLat: Double, pLon: Double): Approach {
        val eastScale = 111_320.0 * cos(Math.toRadians(aLat)).coerceAtLeast(0.2)
        val bx = (bLon - aLon) * eastScale
        val by = (bLat - aLat) * 111_320.0
        val px = (pLon - aLon) * eastScale
        val py = (pLat - aLat) * 111_320.0
        val len2 = bx * bx + by * by
        val t = if (len2 < 1.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val cx = bx * t
        val cy = by * t
        return Approach(hypot(px - cx, py - cy), aLat + cy / 111_320.0, aLon + cx / eastScale)
    }

    private const val AWAY = 0
    private const val NEAR = 1
    private const val FACING = 2
}
