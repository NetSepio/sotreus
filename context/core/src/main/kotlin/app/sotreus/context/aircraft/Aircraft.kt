package app.sotreus.context.aircraft

import app.sotreus.context.GeoArea

/** One aircraft as reported by a provider. Provenance NETWORK; positions are reports, not sensing. */
data class AircraftReport(
    val icao24: String,
    val callsign: String?,
    val originCountry: String?,
    /** Wall-clock ms of the last position report and the last message of any kind. */
    val positionAtMs: Long?,
    val lastContactMs: Long,
    val lat: Double?,
    val lon: Double?,
    val altitudeM: Double?,
    val onGround: Boolean,
    val speedMps: Double?,
    val courseDeg: Double?,
    val verticalRateMps: Double?,
    val squawk: String?,
)

/** Architecture handoff §14.1. Implementations receive only an area. */
interface AircraftProvider {
    val name: String
    suspend fun aircraftIn(area: GeoArea): AircraftResult
}

sealed interface AircraftResult {
    data class Ok(val reports: List<AircraftReport>, val providerTimeMs: Long) : AircraftResult
    data object RateLimited : AircraftResult
    data class Failed(val message: String) : AircraftResult
}
