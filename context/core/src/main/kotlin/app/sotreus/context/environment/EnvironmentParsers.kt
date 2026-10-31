package app.sotreus.context.environment

import app.sotreus.context.Geo
import app.sotreus.context.GeoPoint
import app.sotreus.core.model.AirQualityMode
import app.sotreus.core.model.AirReading
import app.sotreus.core.model.Hazard
import app.sotreus.core.model.HazardLevel
import app.sotreus.core.model.HazardType
import app.sotreus.core.model.Nearby
import app.sotreus.core.model.Quake
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.floor

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.ifBlank { null }
private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
private fun JsonObject.lng(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonElement.point(): Pair<Double, Double>? {
    val c = (this as? JsonObject)?.obj("geometry")?.get("coordinates") as? JsonArray ?: return null
    val lon = (c.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
    val lat = (c.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
    return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
}

/** USGS earthquake summary GeoJSON (https://earthquake.usgs.gov/earthquakes/feed/v1.0/geojson.php). */
object UsgsFeed {
    fun parse(body: String): List<Quake> {
        val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray.orEmpty()
        return features.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val p = o.obj("properties") ?: return@mapNotNull null
            if (p.str("type")?.lowercase(Locale.US).let { it != null && it != "earthquake" }) return@mapNotNull null
            val (lat, lon) = f.point() ?: return@mapNotNull null
            val depth = ((o.obj("geometry")?.get("coordinates") as? JsonArray)?.getOrNull(2) as? JsonPrimitive)?.doubleOrNull
            Quake(
                id = o.str("id") ?: return@mapNotNull null,
                magnitude = p.dbl("mag") ?: return@mapNotNull null,
                place = p.str("place"),
                atMs = p.lng("time") ?: return@mapNotNull null,
                updatedMs = p.lng("updated") ?: 0L,
                lat = lat,
                lon = lon,
                depthKm = depth,
                url = p.str("url"),
            )
        }
    }
}

/** GDACS event list GeoJSON (https://www.gdacs.org/gdacsapi/). Keeps current events only. */
object GdacsFeed {
    fun parse(body: String): List<Hazard> {
        val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray.orEmpty()
        return features.mapNotNull { f ->
            val p = (f as? JsonObject)?.obj("properties") ?: return@mapNotNull null
            if (p.str("iscurrent")?.lowercase(Locale.US) != "true") return@mapNotNull null
            val type = HazardType.of(p.str("eventtype")) ?: return@mapNotNull null
            val level = when (p.str("alertlevel")?.lowercase(Locale.US)) {
                "green" -> HazardLevel.GREEN
                "orange" -> HazardLevel.ORANGE
                "red" -> HazardLevel.RED
                else -> return@mapNotNull null
            }
            val (lat, lon) = f.point() ?: return@mapNotNull null
            val from = utc(p.str("fromdate")) ?: return@mapNotNull null
            Hazard(
                id = "${type.code}-${p.str("eventid") ?: return@mapNotNull null}",
                type = type,
                level = level,
                name = p.str("name") ?: p.str("description") ?: type.code,
                severity = p.obj("severitydata")?.str("severitytext"),
                fromMs = from,
                toMs = utc(p.str("todate")) ?: from,
                lat = lat,
                lon = lon,
                url = p.obj("url")?.str("report"),
            )
        }.distinctBy { it.id }
    }

    private fun utc(s: String?): Long? = s?.let { runCatching { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
}

/** Open-Meteo air-quality `current` block (https://open-meteo.com/en/docs/air-quality-api). */
object OpenMeteoAir {
    const val FIELDS = "european_aqi,us_aqi,pm2_5,pm10,ozone,nitrogen_dioxide"

    fun parse(body: String): AirReading? {
        val c = json.parseToJsonElement(body).jsonObject.obj("current") ?: return null
        val time = c.lng("time") ?: return null
        return AirReading(
            europeanAqi = c.dbl("european_aqi")?.toInt(),
            usAqi = c.dbl("us_aqi")?.toInt(),
            pm25 = c.dbl("pm2_5"),
            pm10 = c.dbl("pm10"),
            ozone = c.dbl("ozone"),
            no2 = c.dbl("nitrogen_dioxide"),
            modelTimeMs = time * 1000,
        ).takeIf { it.europeanAqi != null || it.usAqi != null || it.pm25 != null }
    }
}

/** The only location an air-quality request carries: a grid-cell centre. */
data class AreaQuery(val lat: Double, val lon: Double) {
    /** Two decimals: enough for a 0.05° cell centre, nothing finer. */
    val latText: String get() = String.format(Locale.US, "%.2f", lat)
    val lonText: String get() = String.format(Locale.US, "%.2f", lon)
}

/** The bounds of one grid cell, as OpenAQ's `bbox` (min lon, min lat, max lon, max lat). */
data class AreaBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    val key: String get() = String.format(Locale.US, "%.2f,%.2f,%.2f,%.2f", west, south, east, north)
}

object EnvironmentGeo {
    /** The 0.5° cell containing [p]. OpenAQ never gets anything finer. */
    fun coarseBox(p: GeoPoint): AreaBox {
        val c = round(p, AirQualityMode.COARSE)!!
        return AreaBox(c.lon - 0.25, c.lat - 0.25, c.lon + 0.25, c.lat + 0.25)
    }

    /** Coarse: 0.5° cells (about 55 km). Fine: 0.1° cells (about 11 km). Null when off. */
    fun cellSizeDeg(mode: AirQualityMode): Double? = when (mode) {
        AirQualityMode.OFF -> null
        AirQualityMode.COARSE -> 0.5
        AirQualityMode.FINE -> 0.1
    }

    /** The centre of the [mode] grid cell containing [p]. Many nearby phones send the same point. */
    fun round(p: GeoPoint, mode: AirQualityMode): AreaQuery? {
        val size = cellSizeDeg(mode) ?: return null
        fun centre(v: Double, min: Double, max: Double): Double {
            val cell = floor(v / size + 1e-9) * size + size / 2
            return Math.round(cell.coerceIn(min, max) * 100) / 100.0
        }
        return AreaQuery(centre(p.lat, -89.95, 89.95), centre(p.lon, -179.95, 179.95))
    }

    /**
     * How far GDACS events of each type are listed from their centre point. GDACS gives one point per
     * event, so these stay close to the area an event of that type usually affects; a cyclone's centre
     * 800 km away is not about this place.
     */
    fun hazardRadiusKm(type: HazardType): Double = when (type) {
        HazardType.EARTHQUAKE -> 300.0
        HazardType.TROPICAL_CYCLONE -> 400.0
        HazardType.FLOOD -> 200.0
        HazardType.VOLCANO -> 100.0
        HazardType.WILDFIRE -> 100.0
    }

    fun nearbyQuakes(all: List<Quake>, here: GeoPoint, radiusKm: Double): List<Nearby<Quake>> =
        all.map { Nearby(it, Geo.distanceKm(here, GeoPoint(it.lat, it.lon))) }
            .filter { it.distanceKm <= radiusKm }
            .sortedByDescending { it.item.atMs }

    fun nearbyHazards(all: List<Hazard>, here: GeoPoint): List<Nearby<Hazard>> =
        all.map { Nearby(it, Geo.distanceKm(here, GeoPoint(it.lat, it.lon))) }
            .filter { it.distanceKm <= hazardRadiusKm(it.item.type) }
            .sortedWith(compareByDescending<Nearby<Hazard>> { it.item.level }.thenBy { it.distanceKm })
}
