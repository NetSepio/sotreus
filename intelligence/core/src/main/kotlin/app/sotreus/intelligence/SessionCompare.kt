package app.sotreus.intelligence

import kotlin.math.abs

/**
 * Compare two sessions (screen 12). Follows Fieldwatch SitDiff: rows are keyed by entity
 * (kind + address), so a BLE address rotation shows as a new row rather than a match.
 */
object SessionCompare {
    data class Radio(val entityId: String, val presentMs: Long, val avgRssi: Int)

    data class Changed(val a: Radio, val b: Radio)

    data class Result(
        val both: List<String>,
        val onlyA: List<Radio>,
        val onlyB: List<Radio>,
        val changed: List<Changed>,
    )

    /** "Changed" = in both, but present for under half / over double the time, or ≥ 10 dB apart. */
    fun compare(a: List<Radio>, b: List<Radio>): Result {
        val am = a.associateBy { it.entityId }
        val bm = b.associateBy { it.entityId }
        val both = am.keys.intersect(bm.keys)
        val changed = both.mapNotNull { id ->
            val x = am.getValue(id)
            val y = bm.getValue(id)
            val ratio = (y.presentMs + 1).toDouble() / (x.presentMs + 1)
            if (ratio < 0.5 || ratio > 2.0 || abs(x.avgRssi - y.avgRssi) >= 10) Changed(x, y) else null
        }
        return Result(
            both = both.sorted(),
            onlyA = (am.keys - both).map(am::getValue).sortedByDescending { it.presentMs },
            onlyB = (bm.keys - both).map(bm::getValue).sortedByDescending { it.presentMs },
            changed = changed.sortedByDescending { abs(it.a.presentMs - it.b.presentMs) },
        )
    }
}
