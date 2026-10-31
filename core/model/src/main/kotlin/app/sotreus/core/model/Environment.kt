package app.sotreus.core.model

/** An earthquake from the USGS summary feed. Provenance NETWORK. */
data class Quake(
    val id: String,
    val magnitude: Double,
    /** USGS wording, e.g. "52 km SSE of Town, Region". */
    val place: String?,
    val atMs: Long,
    val updatedMs: Long,
    val lat: Double,
    val lon: Double,
    val depthKm: Double?,
    val url: String?,
)

/** GDACS event type codes. Droughts are left out: their centre point says little about one place. */
enum class HazardType(val code: String) {
    EARTHQUAKE("EQ"), TROPICAL_CYCLONE("TC"), FLOOD("FL"), VOLCANO("VO"), WILDFIRE("WF");

    companion object { fun of(code: String?): HazardType? = entries.firstOrNull { it.code == code } }
}

/** GDACS's own alert level. Shown as GDACS's wording, never turned into advice. */
enum class HazardLevel { GREEN, ORANGE, RED }

/** A current GDACS event. One centre point; [lat]/[lon] is not the edge of the event. Provenance NETWORK. */
data class Hazard(
    val id: String,
    val type: HazardType,
    val level: HazardLevel,
    val name: String,
    /** GDACS severity text, e.g. "Magnitude 4.9M, Depth:10km". */
    val severity: String?,
    val fromMs: Long,
    val toMs: Long,
    val lat: Double,
    val lon: Double,
    val url: String?,
)

/** CAMS model values via Open-Meteo for one rounded point. A model estimate: provenance PREDICTED. */
data class AirReading(
    val europeanAqi: Int?,
    val usAqi: Int?,
    val pm25: Double?,
    val pm10: Double?,
    val ozone: Double?,
    val no2: Double?,
    /** Model time the values are for. */
    val modelTimeMs: Long,
)

/** A feed item with its distance from the context location. */
data class Nearby<T>(val item: T, val distanceKm: Double)

/** Official warning services Sotreus can show copies of. */
enum class AlertSource { NWS, METEOALARM }

/**
 * One official warning, copied from its issuer's feed. [level] is MeteoAlarm's colour (Yellow,
 * Orange, Red); [severity] is the CAP severity word (Minor … Extreme). Neither is turned into advice.
 */
data class WeatherAlert(
    val id: String,
    val source: AlertSource,
    val event: String,
    val level: String?,
    val severity: String?,
    val area: String,
    val onsetMs: Long?,
    val untilMs: Long?,
    val sender: String?,
    val url: String?,
)

/** Warnings with the same event, level and time window, listed once with every area they cover. */
data class AlertGroup(
    val event: String,
    val level: String?,
    val severity: String?,
    val areas: List<String>,
    val onsetMs: Long?,
    val untilMs: Long?,
    val url: String?,
)

/** Latest values from the nearest OpenAQ monitoring station. Measured by that station: provenance NETWORK. */
data class StationReading(
    val locationId: Long,
    val name: String,
    val provider: String?,
    val distanceKm: Double,
    val values: List<StationValue>,
    /** Data licence attribution required by the station's owner, when OpenAQ lists one. */
    val attribution: String?,
)

data class StationValue(val parameter: String, val units: String, val value: Double, val atMs: Long)
