package app.sotreus.intelligence

import app.sotreus.intelligence.fieldwatch.Observation
import app.sotreus.intelligence.fieldwatch.RadioFacts
import app.sotreus.intelligence.fieldwatch.RadioKind
import app.sotreus.intelligence.fieldwatch.Sighting
import app.sotreus.intelligence.fieldwatch.TrackerWatch

/**
 * Finder tags that say they are away from their owner and stay with this phone (opt-in), on Fieldwatch's
 * [TrackerWatch]: 30 minutes with no gap over 20, at three places at least 100 m apart. A tag that says it
 * is with its owner starts over. The places are this phone's, held in memory only; what comes out is a
 * time and a count. A SmartTag is followed across address changes by its own separated id.
 */
class SeparatedTags {
    data class Evidence(val minutes: Int, val places: Int)

    private var episodes = emptyList<TrackerWatch.Episode>()
    private val packets = HashMap<String, RadioFacts>()

    /** Only while the owner has it on: nothing is kept otherwise. */
    @Volatile var enabled = false
        private set

    @Synchronized fun turn(on: Boolean) {
        if (enabled == on) return
        enabled = on
        clear()
    }

    /** Every advertisement: keeps the newest one that can say separated or with its owner. */
    fun hear(key: String, o: Observation) {
        if (!enabled || o.kind != RadioKind.BLE || !canSay(o.facts)) return
        synchronized(this) { packets[key] = o.facts }
    }

    /**
     * The radios heard since the last pass. [mine] are the keys the owner labelled Mine, Expected or
     * Ignore. [here] is this phone's place, only from an accurate fresh fix.
     */
    @Synchronized fun pass(rows: List<Sighting>, mine: Set<String>, here: TrackerWatch.Spot?, now: Long) {
        if (!enabled) return
        val hears = rows.filter { it.kind == RadioKind.BLE }.map { TrackerWatch.Hear(it, packets[it.key] ?: RadioFacts.Empty) }
        packets.clear()
        episodes = TrackerWatch.follow(episodes, hears, mine, here?.lat, here?.lon, now, enabled = true).episodes
    }

    @Synchronized fun evidence(key: String): Evidence? = episodes.firstOrNull { it.deviceKey == key }?.let { e ->
        val span = e.lastHeard - e.startedAt
        if (span < TrackerWatch.FOLLOW_MS || e.spots.size < TrackerWatch.FOLLOW_PLACES) null
        else Evidence((span / 60_000).toInt(), e.spots.size)
    }

    @Synchronized fun clear() {
        episodes = emptyList()
        packets.clear()
    }

    private fun canSay(f: RadioFacts): Boolean =
        f.mfgRecords.any { it.companyId == APPLE } || f.serviceData.any { r -> FINDER_SERVICES.any { r.uuid.equals(it, true) || r.uuid.startsWith("0000$it", true) } }

    private companion object {
        const val APPLE = 0x004C

        /** SmartTag, DULT and Find Hub service data. */
        val FINDER_SERVICES = listOf("FD5A", "FCB2", "FEAA")
    }
}
