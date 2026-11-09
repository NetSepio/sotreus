package app.sotreus.edge.protocol

/** What one handshake learns about records the Edge's restarts lost since this phone's last handshake. */
data class RestartLossPlan(
    /** Seqs to check: the records in them this phone does not hold were lost. */
    val ranges: List<LongRange>,
    /** Restarts whose loss is not known: after a power loss or the reset pin, with older firmware, or pushed out of the list. */
    val notKnown: Long,
)

object RestartLossPlanner {
    private val NONE = RestartLossPlan(emptyList(), 0)

    /**
     * [lastBoot] is the Edge's boot at this phone's previous handshake (null: none, so nothing
     * before this link is judged), [boot] the boot now, [report] `status.log.restart_loss` (null
     * from older firmware). Each restart is judged once: only those out of [lastBoot] or later are
     * new, and [lastBoot] moves to [boot] with the result. The boot counter goes up by exactly 1
     * per boot; it starts again at 1 only when the Edge's storage is erased, which also gives it a
     * new identity, so a lower boot on a known Edge is one restart whose loss is not known.
     */
    fun plan(lastBoot: Long?, boot: Long, report: EdgeRestartLoss?): RestartLossPlan {
        if (lastBoot == null || boot == lastBoot) return NONE
        if (boot < lastBoot) return RestartLossPlan(emptyList(), 1)
        if (report == null) return RestartLossPlan(emptyList(), boot - lastBoot)
        // The count started again at since_boot: the restarts from lastBoot into it are not known.
        var notKnown = (report.sinceBoot - lastBoot).coerceAtLeast(0)
        // Ranges pushed out of the list are the oldest; restarts before the first listed one are not known.
        if (report.records > report.ranges.sumOf { it.size }) {
            val firstListed = report.ranges.minOfOrNull { it.boot } ?: boot
            notKnown += (firstListed - maxOf(lastBoot, report.sinceBoot)).coerceAtLeast(0)
        }
        val ranges = report.ranges.filter { it.boot in lastBoot until boot }.map { it.from..it.to }
        return RestartLossPlan(ranges, notKnown)
    }
}
