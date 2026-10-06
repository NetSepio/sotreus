package app.sotreus.context.aircraft

import app.sotreus.context.GeoArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenSky Network state vectors (https://openskynetwork.github.io/opensky-api/rest.html), anonymous
 * access: no key, ~10 s resolution, a daily credit budget. Only the bounding box is sent.
 */
@Singleton
class OpenSkyProvider @Inject constructor() : AircraftProvider {
    override val name = "OpenSky Network"
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    override suspend fun aircraftIn(area: GeoArea): AircraftResult = withContext(Dispatchers.IO) {
        val url = String.format(
            Locale.US, "https://opensky-network.org/api/states/all?lamin=%.4f&lomin=%.4f&lamax=%.4f&lomax=%.4f",
            area.latMin, area.lonMin, area.latMax, area.lonMax,
        )
        runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                when {
                    r.code == 429 -> AircraftResult.RateLimited
                    !r.isSuccessful -> AircraftResult.Failed("HTTP ${r.code}")
                    else -> parse(r.body.string())
                }
            }
        }.getOrElse { AircraftResult.Failed(it.message ?: it.javaClass.simpleName) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses the `/states/all` response (positional arrays; see the OpenSky REST docs). */
        fun parse(body: String): AircraftResult.Ok {
            val root = json.parseToJsonElement(body).jsonObject
            val time = root["time"]?.jsonPrimitive?.longOrNull ?: 0L
            val states = (root["states"] as? JsonArray).orEmpty()
            val reports = states.mapNotNull { el ->
                val s = el.jsonArray
                fun str(i: Int) = (s.getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.ifBlank { null }
                fun dbl(i: Int) = (s.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
                fun lng(i: Int) = (s.getOrNull(i) as? JsonPrimitive)?.longOrNull
                val icao = str(0) ?: return@mapNotNull null
                AircraftReport(
                    icao24 = icao.lowercase(Locale.US),
                    callsign = str(1),
                    originCountry = str(2),
                    positionAtMs = lng(3)?.times(1000),
                    lastContactMs = (lng(4) ?: time) * 1000,
                    lon = dbl(5),
                    lat = dbl(6),
                    altitudeM = dbl(13) ?: dbl(7),
                    onGround = (s.getOrNull(8) as? JsonPrimitive)?.booleanOrNull == true,
                    speedMps = dbl(9),
                    courseDeg = dbl(10),
                    verticalRateMps = dbl(11),
                    squawk = str(14),
                )
            }
            return AircraftResult.Ok(reports, time * 1000)
        }
    }
}
