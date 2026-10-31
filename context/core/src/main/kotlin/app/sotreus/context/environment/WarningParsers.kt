package app.sotreus.context.environment

import app.sotreus.context.Geo
import app.sotreus.context.GeoPoint
import app.sotreus.core.model.AlertGroup
import app.sotreus.core.model.AlertSource
import app.sotreus.core.model.StationValue
import app.sotreus.core.model.WeatherAlert
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.ifBlank { null }
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun iso(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** CAP severity order, most severe first. Unknown words sort last. */
private val SEVERITY = listOf("extreme", "severe", "moderate", "minor")
internal fun severityRank(s: String?): Int = SEVERITY.indexOf(s?.lowercase(Locale.US)).let { if (it < 0) SEVERITY.size else it }

/** MeteoAlarm awareness colours, most severe first. */
private val LEVELS = listOf("red", "orange", "yellow", "green")
internal fun levelRank(l: String?): Int = LEVELS.indexOf(l?.lowercase(Locale.US)).let { if (it < 0) LEVELS.size else it }

/** NWS active alerts for one area code (https://www.weather.gov/documentation/services-web-api). */
object NwsAlerts {
    fun parse(body: String, nowMs: Long): List<WeatherAlert> {
        val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray.orEmpty()
        return features.mapNotNull { f ->
            val p = (f as? JsonObject)?.obj("properties") ?: return@mapNotNull null
            if (p.str("status")?.lowercase(Locale.US) != "actual") return@mapNotNull null
            if (p.str("messageType")?.lowercase(Locale.US) == "cancel") return@mapNotNull null
            val until = iso(p.str("ends")) ?: iso(p.str("expires"))
            if (until != null && until < nowMs) return@mapNotNull null
            WeatherAlert(
                id = p.str("id") ?: return@mapNotNull null,
                source = AlertSource.NWS,
                event = p.str("event") ?: return@mapNotNull null,
                level = null,
                severity = p.str("severity"),
                area = p.str("areaDesc").orEmpty(),
                onsetMs = iso(p.str("onset")) ?: iso(p.str("effective")),
                untilMs = until,
                sender = p.str("senderName"),
                url = null,
            )
        }.distinctBy { it.id }.sortedWith(compareBy<WeatherAlert> { severityRank(it.severity) }.thenBy { it.onsetMs ?: Long.MAX_VALUE })
    }
}

/** MeteoAlarm legacy Atom feed for one country (https://feeds.meteoalarm.org). */
object MeteoAlarmFeed {
    fun parse(xml: String, nowMs: Long): List<WeatherAlert> {
        val factory = DocumentBuilderFactory.newInstance()
        // No DTDs or external entities from a network document.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        factory.isExpandEntityReferences = false
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val entries = doc.getElementsByTagName("entry")
        return (0 until entries.length).mapNotNull { i ->
            val e = entries.item(i) as Element
            fun text(tag: String) = e.getElementsByTagName(tag).item(0)?.textContent?.trim()?.ifBlank { null }
            if (text("cap:status")?.lowercase(Locale.US) != "actual") return@mapNotNull null
            if (text("cap:message_type")?.lowercase(Locale.US) == "cancel") return@mapNotNull null
            val until = iso(text("cap:expires"))
            if (until != null && until < nowMs) return@mapNotNull null
            val title = text("title")
            val level = title?.substringBefore(' ')?.takeIf { levelRank(it) < LEVELS.size }
            val links = e.getElementsByTagName("link")
            val areaLink = (0 until links.length).map { links.item(it) as Element }
                .firstOrNull { it.getAttribute("type").isEmpty() && it.getAttribute("rel").isEmpty() }?.getAttribute("href")
            WeatherAlert(
                id = text("cap:identifier") ?: text("id") ?: return@mapNotNull null,
                source = AlertSource.METEOALARM,
                // "Yellow Snow-ice Warning issued for Germany - Kreis Cham" → "Snow-ice Warning".
                event = title?.substringBefore(" issued for ")?.let { t -> if (level != null) t.removePrefix(level).trim() else t } ?: text("cap:event") ?: return@mapNotNull null,
                level = level?.replaceFirstChar { it.uppercase() },
                severity = text("cap:severity"),
                area = text("cap:areaDesc").orEmpty(),
                onsetMs = iso(text("cap:onset")) ?: iso(text("cap:effective")),
                untilMs = until,
                sender = null,
                url = areaLink?.ifBlank { null },
            )
        }.distinctBy { "${it.id}|${it.area}" }
    }

    /** One row per event, level and time window, naming every area it covers. Most severe first. */
    fun group(alerts: List<WeatherAlert>): List<AlertGroup> =
        alerts.groupBy { listOf(it.event, it.level, it.onsetMs, it.untilMs) }
            .map { (_, same) ->
                val first = same.first()
                AlertGroup(first.event, first.level, first.severity, same.map { it.area }.filter { it.isNotBlank() }.distinct().sorted(), first.onsetMs, first.untilMs, first.url)
            }
            .sortedWith(compareBy<AlertGroup> { levelRank(it.level) }.thenBy { severityRank(it.severity) }.thenBy { it.onsetMs ?: Long.MAX_VALUE })
}

/** OpenAQ v3 (https://docs.openaq.org). Every request needs the user's own API key. */
object OpenAq {
    data class Sensor(val parameter: String, val units: String)
    data class Station(val id: Long, val name: String, val provider: String?, val lat: Double, val lon: Double, val lastMs: Long?, val sensors: Map<Long, Sensor>, val attribution: String?)

    fun parseLocations(body: String): List<Station> =
        json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val c = o.obj("coordinates") ?: return@mapNotNull null
            val lat = (c["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lon = (c["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if ((o["isMobile"] as? JsonPrimitive)?.contentOrNull == "true") return@mapNotNull null
            val sensors = (o["sensors"] as? JsonArray).orEmpty().mapNotNull { s ->
                val so = s as? JsonObject ?: return@mapNotNull null
                val p = so.obj("parameter") ?: return@mapNotNull null
                val id = (so["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                id to Sensor(p.str("displayName") ?: p.str("name") ?: return@mapNotNull null, p.str("units").orEmpty())
            }.toMap()
            Station(
                id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
                name = o.str("name") ?: o.str("locality") ?: "OpenAQ ${o["id"]}",
                provider = o.obj("provider")?.str("name"),
                lat = lat,
                lon = lon,
                lastMs = iso(o.obj("datetimeLast")?.str("utc")),
                sensors = sensors,
                attribution = (o["licenses"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.obj("attribution")?.str("name") },
            )
        }

    /** Latest value per sensor: (sensorId, value, time). */
    fun parseLatest(body: String): List<Triple<Long, Double, Long>> =
        json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            Triple(
                (o["sensorsId"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
                (o["value"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                iso(o.obj("datetime")?.str("utc")) ?: return@mapNotNull null,
            )
        }

    /** The nearest station that reported within [maxAgeMs], and its distance from [here]. */
    fun nearest(stations: List<Station>, here: GeoPoint, nowMs: Long, maxAgeMs: Long): Pair<Station, Double>? =
        stations.filter { s -> s.sensors.isNotEmpty() && (s.lastMs ?: 0L) >= nowMs - maxAgeMs }
            .map { it to Geo.distanceKm(here, GeoPoint(it.lat, it.lon)) }
            .minByOrNull { it.second }

    fun values(station: Station, latest: List<Triple<Long, Double, Long>>): List<StationValue> =
        latest.mapNotNull { (sensor, value, at) -> station.sensors[sensor]?.let { StationValue(it.parameter, it.units, value, at) } }
            .groupBy { it.parameter }.map { (_, v) -> v.maxBy { it.atMs } }
            .sortedBy { PARAMETER_ORDER.indexOf(it.parameter.lowercase(Locale.US)).let { i -> if (i < 0) PARAMETER_ORDER.size else i } }

    private val PARAMETER_ORDER = listOf("pm2.5", "pm10", "o₃ mass", "o3", "no₂ mass", "no2", "so₂ mass", "so2", "co mass", "co")
}

enum class AqiScale { EUROPEAN, US }

/** Region codes the warning services accept. Names are the services' own English names. */
object WarningRegions {
    /** NWS area codes: states, DC and territories. */
    val NWS: List<Pair<String, String>> = listOf(
        "AL" to "Alabama", "AK" to "Alaska", "AS" to "American Samoa", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California",
        "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware", "DC" to "District of Columbia", "FL" to "Florida", "GA" to "Georgia",
        "GU" to "Guam", "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
        "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts", "MI" to "Michigan",
        "MN" to "Minnesota", "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
        "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York", "NC" to "North Carolina",
        "ND" to "North Dakota", "MP" to "Northern Mariana Islands", "OH" to "Ohio", "OK" to "Oklahoma", "OR" to "Oregon",
        "PA" to "Pennsylvania", "PR" to "Puerto Rico", "RI" to "Rhode Island", "SC" to "South Carolina", "SD" to "South Dakota",
        "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont", "VI" to "U.S. Virgin Islands", "VA" to "Virginia",
        "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
    )

    /** MeteoAlarm legacy Atom feed names (https://feeds.meteoalarm.org). */
    val METEOALARM: List<Pair<String, String>> = listOf(
        "andorra" to "Andorra", "austria" to "Austria", "belgium" to "Belgium", "bosnia-herzegovina" to "Bosnia and Herzegovina",
        "bulgaria" to "Bulgaria", "croatia" to "Croatia", "cyprus" to "Cyprus", "czechia" to "Czechia", "denmark" to "Denmark",
        "estonia" to "Estonia", "finland" to "Finland", "france" to "France", "germany" to "Germany", "greece" to "Greece",
        "hungary" to "Hungary", "iceland" to "Iceland", "ireland" to "Ireland", "israel" to "Israel", "italy" to "Italy",
        "latvia" to "Latvia", "lithuania" to "Lithuania", "luxembourg" to "Luxembourg", "malta" to "Malta", "moldova" to "Moldova",
        "montenegro" to "Montenegro", "netherlands" to "Netherlands", "republic-of-north-macedonia" to "North Macedonia",
        "norway" to "Norway", "poland" to "Poland", "portugal" to "Portugal", "romania" to "Romania", "serbia" to "Serbia",
        "slovakia" to "Slovakia", "slovenia" to "Slovenia", "spain" to "Spain", "sweden" to "Sweden", "switzerland" to "Switzerland",
        "ukraine" to "Ukraine", "united-kingdom" to "United Kingdom",
    )

    /** ISO 3166-1 alpha-2 → MeteoAlarm feed name. */
    private val METEOALARM_ISO = mapOf(
        "AD" to "andorra", "AT" to "austria", "BE" to "belgium", "BA" to "bosnia-herzegovina", "BG" to "bulgaria",
        "HR" to "croatia", "CY" to "cyprus", "CZ" to "czechia", "DK" to "denmark", "EE" to "estonia", "FI" to "finland",
        "FR" to "france", "DE" to "germany", "GR" to "greece", "HU" to "hungary", "IS" to "iceland", "IE" to "ireland",
        "IL" to "israel", "IT" to "italy", "LV" to "latvia", "LT" to "lithuania", "LU" to "luxembourg", "MT" to "malta",
        "MD" to "moldova", "ME" to "montenegro", "NL" to "netherlands", "MK" to "republic-of-north-macedonia",
        "NO" to "norway", "PL" to "poland", "PT" to "portugal", "RO" to "romania", "RS" to "serbia", "SK" to "slovakia",
        "SI" to "slovenia", "ES" to "spain", "SE" to "sweden", "CH" to "switzerland", "UA" to "ukraine", "GB" to "united-kingdom",
    )

    fun meteoAlarmFor(iso2: String): String? = METEOALARM_ISO[iso2]

    /**
     * The air-quality scale people in a region use: the European AQI in European countries (the
     * MeteoAlarm set without Israel), the US EPA AQI everywhere else. The feed has no Asian national
     * index (India NAQI, China AQI), so the widely used US scale stands in and the screen says so.
     */
    fun aqiScale(regionCode: String?): AqiScale =
        if (regionCode != null && regionCode != "IL" && regionCode in METEOALARM_ISO) AqiScale.EUROPEAN else AqiScale.US

    fun nwsName(code: String): String? = NWS.firstOrNull { it.first == code }?.second
    fun meteoAlarmName(slug: String): String? = METEOALARM.firstOrNull { it.first == slug }?.second
}
