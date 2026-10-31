package app.sotreus.context.environment

import android.content.Context
import app.sotreus.core.model.AirReading
import app.sotreus.core.model.Hazard
import app.sotreus.core.model.Quake
import app.sotreus.core.model.WeatherAlert
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class Cached<T>(val value: T, val fetchedAtMs: Long)

/**
 * Downloads environment feeds (architecture handoff §14.5) and caches the raw responses on the
 * phone, so the last copy shows offline with its age. USGS and GDACS requests are the same for
 * everyone and carry no location. The air-quality request carries one [AreaQuery] cell centre.
 */
@Singleton
class EnvironmentSources @Inject constructor(@ApplicationContext private val context: Context) {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val dir get() = File(context.filesDir, "context/environment").apply { mkdirs() }
    private val lastFailure = ConcurrentHashMap<String, Long>()
    private val lastCode = ConcurrentHashMap<String, Int>()

    private fun file(name: String) = File(dir, name)

    suspend fun cachedQuakes(): Cached<List<Quake>>? = read(QUAKES) { UsgsFeed.parse(it) }

    /** Merges the Orange/Red and Green lists; an event in both keeps its higher level. */
    suspend fun cachedHazards(): Cached<List<Hazard>>? {
        val major = read(HAZARDS_MAJOR) { GdacsFeed.parse(it) }
        val minor = read(HAZARDS_MINOR) { GdacsFeed.parse(it) }
        if (major == null && minor == null) return null
        val all = (major?.value.orEmpty() + minor?.value.orEmpty()).groupBy { it.id }.map { (_, same) -> same.maxBy { it.level } }
        return Cached(all, listOfNotNull(major?.fetchedAtMs, minor?.fetchedAtMs).min())
    }

    /** The cached reading, only when it was fetched for [query]. */
    suspend fun cachedAir(query: AreaQuery): Cached<AirReading>? = readKeyed(AIR, query.key) { OpenMeteoAir.parse(it) }

    suspend fun cachedNws(area: String, nowMs: Long): Cached<List<WeatherAlert>>? = readKeyed(NWS, area) { NwsAlerts.parse(it, nowMs) }

    suspend fun cachedMeteoAlarm(country: String, nowMs: Long): Cached<List<WeatherAlert>>? =
        readKeyed(METEOALARM, country) { MeteoAlarmFeed.parse(it, nowMs) }

    suspend fun cachedOpenAqStations(box: AreaBox): Cached<List<OpenAq.Station>>? = readKeyed(OPENAQ_STATIONS, box.key) { OpenAq.parseLocations(it) }

    suspend fun cachedOpenAqLatest(locationId: Long): Cached<List<Triple<Long, Double, Long>>>? =
        readKeyed(OPENAQ_LATEST, locationId.toString()) { OpenAq.parseLatest(it) }

    /** True when OpenAQ refused the last request because of the key. */
    fun openAqKeyRejected(): Boolean = lastCode[OPENAQ_STATIONS] in AUTH_CODES || lastCode[OPENAQ_LATEST] in AUTH_CODES

    /** Each refresh returns false when the download failed; a fresh enough cache counts as success. */
    suspend fun refreshQuakes(maxAgeMs: Long): Boolean = refresh(QUAKES, maxAgeMs, USGS_URL)

    suspend fun refreshHazards(maxAgeMs: Long): Boolean =
        refresh(HAZARDS_MAJOR, maxAgeMs, GDACS_MAJOR_URL) and refresh(HAZARDS_MINOR, maxAgeMs, GDACS_MINOR_URL)

    suspend fun refreshAir(query: AreaQuery, maxAgeMs: Long): Boolean =
        refreshKeyed(AIR, query.key, maxAgeMs, "$AIR_URL?latitude=${query.latText}&longitude=${query.lonText}&current=${OpenMeteoAir.FIELDS}&timeformat=unixtime")

    /** Sends only the area code (a state or territory). */
    suspend fun refreshNws(area: String, maxAgeMs: Long): Boolean =
        refreshKeyed(NWS, area, maxAgeMs, "$NWS_URL/$area", mapOf("Accept" to "application/geo+json"))

    /** Sends only the country feed name. */
    suspend fun refreshMeteoAlarm(country: String, maxAgeMs: Long): Boolean =
        refreshKeyed(METEOALARM, country, maxAgeMs, "$METEOALARM_URL$country", mapOf("Accept" to "*/*")) // The feed answers 406 to an Atom or JSON Accept header.

    /** Sends the user's key and the bounds of one grid cell. */
    suspend fun refreshOpenAqStations(box: AreaBox, apiKey: String, maxAgeMs: Long): Boolean =
        refreshKeyed(OPENAQ_STATIONS, box.key, maxAgeMs, "$OPENAQ_URL/locations?bbox=${box.key}&limit=100", mapOf("X-API-Key" to apiKey))

    /** Sends the user's key and a station id already listed for the cell. */
    suspend fun refreshOpenAqLatest(locationId: Long, apiKey: String, maxAgeMs: Long): Boolean =
        refreshKeyed(OPENAQ_LATEST, locationId.toString(), maxAgeMs, "$OPENAQ_URL/locations/$locationId/latest", mapOf("X-API-Key" to apiKey))

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        lastFailure.clear()
        lastCode.clear()
    }

    /** A cached response, only when it was fetched for [key] (a cell, region or station). */
    private suspend fun <T> readKeyed(name: String, key: String, parse: (String) -> T?): Cached<T>? = withContext(Dispatchers.IO) {
        if (runCatching { file("$name.key").readText() }.getOrNull() != key) null else read(name, parse)
    }

    private suspend fun refreshKeyed(name: String, key: String, maxAgeMs: Long, url: String, headers: Map<String, String> = emptyMap()): Boolean =
        withContext(Dispatchers.IO) {
            val keyFile = file("$name.key")
            if (runCatching { keyFile.readText() }.getOrNull() != key) {
                file(name).delete()
                lastFailure.remove(name)
            }
            refresh(name, maxAgeMs, url, headers).also { ok -> if (ok) keyFile.writeText(key) }
        }

    private suspend fun <T> read(name: String, parse: (String) -> T?): Cached<T>? = withContext(Dispatchers.IO) {
        val f = file(name)
        if (!f.isFile) return@withContext null
        runCatching { parse(f.readText()) }.getOrNull()?.let { Cached(it, f.lastModified()) }
    }

    private suspend fun refresh(name: String, maxAgeMs: Long, url: String, headers: Map<String, String> = emptyMap()): Boolean = withContext(Dispatchers.IO) {
        val f = file(name)
        val now = System.currentTimeMillis()
        if (f.isFile && now - f.lastModified() < maxAgeMs) return@withContext true
        // After a failure, wait before asking again rather than retrying on every tick.
        lastFailure[name]?.let { if (now - it < RETRY_AFTER_FAILURE_MS) return@withContext false }
        val ok = runCatching {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json")
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(request).execute().use { r ->
                lastCode[name] = r.code
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val source = r.body.source()
                if (source.request(MAX_BYTES + 1)) throw IOException("Response over $MAX_BYTES bytes")
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(source.buffer.readUtf8())
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }.isSuccess
        if (ok) lastFailure.remove(name) else lastFailure[name] = now
        ok
    }

    private val AreaQuery.key get() = "$latText,$lonText"

    companion object {
        const val USGS_URL = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_week.geojson"
        private const val GDACS_URL = "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH"
        // Orange and Red for every type; Green only where USGS does not already cover it and
        // where it is not dominated by small wildfires.
        const val GDACS_MAJOR_URL = "$GDACS_URL?eventlist=EQ;TC;FL;VO;WF&alertlevel=orange;red"
        const val GDACS_MINOR_URL = "$GDACS_URL?eventlist=TC;FL;VO&alertlevel=green"
        const val AIR_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"
        const val NWS_URL = "https://api.weather.gov/alerts/active/area"
        const val METEOALARM_URL = "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-"
        const val OPENAQ_URL = "https://api.openaq.org/v3"
        private val AUTH_CODES = setOf(401, 403)
        private const val NWS = "nws.json"
        private const val METEOALARM = "meteoalarm.xml"
        private const val OPENAQ_STATIONS = "openaq-locations.json"
        private const val OPENAQ_LATEST = "openaq-latest.json"
        private const val USER_AGENT = "Sotreus-Android (+https://sotreus.com)"
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val RETRY_AFTER_FAILURE_MS = 2 * 60_000L
        private const val QUAKES = "usgs.json"
        private const val HAZARDS_MAJOR = "gdacs-major.json"
        private const val HAZARDS_MINOR = "gdacs-minor.json"
        private const val AIR = "air.json"
    }
}
