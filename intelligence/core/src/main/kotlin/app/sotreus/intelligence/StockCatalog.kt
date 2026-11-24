package app.sotreus.intelligence

import app.sotreus.intelligence.fieldwatch.DefaultCatalog
import app.sotreus.intelligence.fieldwatch.Fleet

/** The signature catalog bundled with this version, built once. */
object StockCatalog {
    /** Fieldwatch's catalog number for [DefaultCatalog]. */
    const val VERSION = 118

    val fleets: List<Fleet> by lazy(DefaultCatalog::fleets)

    val ids: Set<String> by lazy { fleets.mapTo(HashSet()) { it.id } }

    /** Bundled rows a later catalog dropped. A saved pack's copy of one does not come back. */
    val RETIRED = setOf("fleet-novatel")
}
