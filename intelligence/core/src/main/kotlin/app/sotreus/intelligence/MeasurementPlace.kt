package app.sotreus.intelligence

import kotlin.math.abs

/** Never attach a delayed radio result to a new place or an incompatible phone-location fix. */
object MeasurementPlace {
    fun resolve(selectedId: Long?, automatic: Boolean, changedAtMs: Long, observedAtMs: Long,
        area: PlaceGeofence.Area?, fix: PlaceGeofence.Fix?,
    ): Long? {
        if (selectedId == null || observedAtMs < changedAtMs) return null
        if (!automatic) return selectedId
        if (area == null || area.placeId != selectedId || fix == null ||
            abs(fix.atMs - observedAtMs) > 20_000 || fix.accuracyM == null ||
            !fix.accuracyM.isFinite() || fix.accuracyM !in 0f..PlaceGeofence.MAX_ACCURACY_M
        ) return null
        return PlaceGeofence.match(listOf(area), fix, selectedId)
    }
}
