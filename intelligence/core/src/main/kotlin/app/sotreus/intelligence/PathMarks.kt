package app.sotreus.intelligence

import kotlin.math.abs

/**
 * Puts session events on this phone's path: each one sits at the path point taken closest in time,
 * when that point is within [maxGapMs]. This is where the phone was when it heard the radio, never
 * where the radio is. Events with no nearby point are left off rather than guessed.
 */
object PathMarks {
    data class PathPoint(val atMs: Long, val lat: Double, val lon: Double)

    data class Placed<T>(val item: T, val lat: Double, val lon: Double)

    const val MAX_GAP_MS = 60_000L

    fun <T> place(events: List<Pair<Long, T>>, path: List<PathPoint>, maxGapMs: Long = MAX_GAP_MS): List<Placed<T>> {
        if (path.isEmpty()) return emptyList()
        val sorted = path.sortedBy { it.atMs }
        val times = LongArray(sorted.size) { sorted[it].atMs }
        return events.mapNotNull { (at, item) ->
            val i = times.binarySearch(at).let { if (it >= 0) it else -it - 1 }
            val near = listOfNotNull(sorted.getOrNull(i - 1), sorted.getOrNull(i)).minByOrNull { abs(it.atMs - at) }
            near?.takeIf { abs(it.atMs - at) <= maxGapMs }?.let { Placed(item, it.lat, it.lon) }
        }
    }
}
