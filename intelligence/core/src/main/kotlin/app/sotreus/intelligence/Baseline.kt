package app.sotreus.intelligence

/**
 * Place baseline rules (screen 09; architecture handoff §11). A place's baseline is learned from
 * completed visits: an entity seen in at least 70 % of them is normally present, 20–70 % occasional.
 */
object Baseline {
    const val NORMAL_SHARE = 0.70
    const val OCCASIONAL_SHARE = 0.20

    enum class Standing { NORMAL, OCCASIONAL, RARE, NEW }

    fun standing(visitsWithEntity: Int, completedVisits: Int): Standing = when {
        visitsWithEntity == 0 || completedVisits == 0 -> Standing.NEW
        visitsWithEntity.toDouble() / completedVisits >= NORMAL_SHARE -> Standing.NORMAL
        visitsWithEntity.toDouble() / completedVisits >= OCCASIONAL_SHARE -> Standing.OCCASIONAL
        else -> Standing.RARE
    }

    /** Usually-present entities that were not observed this visit ("NS-Guest not observed"). */
    fun missing(normal: Set<String>, seenThisVisit: Set<String>): Set<String> = normal - seenThisVisit
}
