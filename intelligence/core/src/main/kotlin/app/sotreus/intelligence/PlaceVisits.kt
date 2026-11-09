package app.sotreus.intelligence

/**
 * Visits from stored sightings filed under one place: the sightings, in time order, split wherever
 * nothing was heard there for [gapMs] or more (the live rule: coming back within the gap continues
 * the same visit). Each visit spans its first and last sighting.
 */
object PlaceVisits {
    data class Sighting(val atMs: Long, val entityId: String)

    data class Visit(val startMs: Long, val endMs: Long, val entities: Map<String, LongRange>)

    fun from(sightings: List<Sighting>, gapMs: Long): List<Visit> {
        if (sightings.isEmpty()) return emptyList()
        val visits = ArrayList<Visit>()
        var run = ArrayList<Sighting>()
        fun close() {
            val spans = run.groupBy { it.entityId }.mapValues { (_, s) -> s.minOf { it.atMs }..s.maxOf { it.atMs } }
            visits += Visit(run.first().atMs, run.last().atMs, spans)
            run = ArrayList()
        }
        for (s in sightings.sortedBy { it.atMs }) {
            if (run.isNotEmpty() && s.atMs - run.last().atMs >= gapMs) close()
            run += s
        }
        close()
        return visits
    }
}
