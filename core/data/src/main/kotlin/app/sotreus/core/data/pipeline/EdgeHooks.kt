package app.sotreus.core.data.pipeline

import app.sotreus.core.model.LocationSource
import app.sotreus.core.model.WifiRole
import app.sotreus.intelligence.fieldwatch.Observation

/**
 * A stored location for an Edge sighting: the Edge's location at the time of observation, never
 * the radio's (HANDOFF_V2_EDGE.md §4.1). [endLat]/[endLon] are set for a stretch of route.
 */
data class TaggedLocation(
    val atMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val source: LocationSource,
    val altitudeM: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
)

/** Supplies the Edge's location for live samples while the Edge is the sensor. */
fun interface EdgeLiveLocator {
    fun locate(atMs: Long): TaggedLocation?
}

/** One synced Edge window that this phone did not receive live. */
data class PastSighting(
    val key: String,
    val observation: Observation,
    val firstMs: Long,
    val lastMs: Long,
    val rssiMax: Int,
    val location: TaggedLocation?,
    val wifiRole: WifiRole?,
    /** A stationary Edge linked to a Place feeds that Place. */
    val placeId: Long?,
)
