package app.sotreus.context.environment

import app.sotreus.context.ContextLocation
import app.sotreus.context.ContextRepository
import app.sotreus.context.GeoPoint
import app.sotreus.core.data.security.SecretBox
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.model.AirQualityMode
import app.sotreus.core.model.AirReading
import app.sotreus.core.model.AlertGroup
import app.sotreus.core.model.Hazard
import app.sotreus.core.model.Nearby
import app.sotreus.core.model.Quake
import app.sotreus.core.model.StationReading
import app.sotreus.core.model.WeatherAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One environment source. [Ready.refreshFailed] means the last download failed and [Ready.value] is older. */
sealed interface FeedState<out T> {
    data object Off : FeedState<Nothing>
    data object NoLocation : FeedState<Nothing>
    data object Loading : FeedState<Nothing>
    data object Unavailable : FeedState<Nothing>
    /** The source needs the user's own API key and none is saved. */
    data object NeedsKey : FeedState<Nothing>
    /** The source refused the saved key. */
    data object KeyRejected : FeedState<Nothing>
    data class Ready<T>(val value: T, val fetchedAtMs: Long, val refreshFailed: Boolean) : FeedState<T>
}

data class EnvironmentState(
    val location: ContextLocation? = null,
    /** False until the first attempt to resolve a location has finished. */
    val located: Boolean = false,
    val air: FeedState<AirReading> = FeedState.Off,
    val airMode: AirQualityMode = AirQualityMode.OFF,
    val quakes: FeedState<List<Nearby<Quake>>> = FeedState.Off,
    val quakeRadiusKm: Int = 300,
    val hazards: FeedState<List<Nearby<Hazard>>> = FeedState.Off,
    /** The phone's region from the bundled boundaries ("US-CA", "DE"), or null outside them. Never sent. */
    val regionCode: String? = null,
    /** Town, state or province, and country, looked up on the phone. Never sent. */
    val area: AreaName? = null,
    /** Official warnings are switched on; [warningRegion] is where this phone is, looked up on the phone. */
    val warningsOn: Boolean = false,
    val warningRegion: WarningRegion? = null,
    val nws: FeedState<List<WeatherAlert>> = FeedState.Off,
    val meteoAlarm: FeedState<List<AlertGroup>> = FeedState.Off,
    /** Ready with null: no station in the cell reported recently. */
    val openAq: FeedState<StationReading?> = FeedState.Off,
) {
    val anyOn: Boolean get() = warningsOn || listOf(air, quakes, hazards, openAq).any { it != FeedState.Off }
}

/**
 * Air quality, earthquakes, disasters, official warnings and station readings near the context
 * location (architecture handoff §14.5). Downloads happen only while [environment] is collected,
 * which the Context tab does while visible. Each source runs on its own and shows as soon as it
 * arrives; a cached copy shows first while a stale one refreshes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class EnvironmentRepository @Inject constructor(
    private val areas: AreaNameRepository,
    private val settings: SettingsRepository,
    private val context: ContextRepository,
    private val sources: EnvironmentSources,
    private val secrets: SecretBox,
) {
    private data class Config(
        val quakes: Boolean,
        val radiusKm: Int,
        val hazards: Boolean,
        val air: AirQualityMode,
        val warnings: Boolean,
        val openAq: Boolean,
        val openAqKey: String?,
    ) {
        val anyOn get() = quakes || hazards || air != AirQualityMode.OFF || warnings || openAq
        override fun toString() = "Config(…)" // keeps the API key out of any log line
    }

    /** Wraps the location so "not tried yet" (null) differs from "tried, none found" (Located(null)). */
    private data class Located(val location: ContextLocation?)


    fun environment(): Flow<EnvironmentState> = combine(
        settings.settings,
        settings.openAqKeyWrapped.map { w -> w?.let { runCatching { String(secrets.unwrap(it), Charsets.UTF_8) }.getOrNull() } },
    ) { s, key ->
        val on = s.contextEnabled
        Config(
            on && s.earthquakesEnabled, s.earthquakeRadiusKm, on && s.hazardsEnabled, if (on) s.airQualityMode else AirQualityMode.OFF,
            on && s.officialWarnings, on && s.openAqEnabled, key?.trim()?.ifBlank { null },
        )
    }
        .distinctUntilChanged()
        .flatMapLatest { run(it) }

    private fun run(c: Config): Flow<EnvironmentState> = channelFlow {
        val state = MutableStateFlow(
            EnvironmentState(
                airMode = c.air, quakeRadiusKm = c.radiusKm, warningsOn = c.warnings,
                quakes = loadingIf(c.quakes), hazards = loadingIf(c.hazards), air = loadingIf(c.air != AirQualityMode.OFF),
                openAq = loadingIf(c.openAq),
            ),
        )
        launch { state.collect { send(it) } }
        if (!c.anyOn) return@channelFlow

        val located = MutableStateFlow<Located?>(null)
        launch {
            // Start from a place, session or recent last-known fix at once; a fresh fix replaces it.
            context.quickLocation()?.let { q ->
                located.value = Located(q)
                state.update { it.copy(location = q, located = true, regionCode = regionOf(q), area = areaOf(q)) }
            }
            while (true) {
                val l = context.resolveLocation() ?: located.value?.location
                located.value = Located(l)
                state.update { it.copy(location = l, located = true, regionCode = regionOf(l), area = areaOf(l)) }
                delay(if (l == null) NO_LOCATION_RETRY_MS else LOCATION_MS)
            }
        }

        /** Runs one source: again whenever the location changes, and every [TICK_MS] in between. */
        fun <T> source(on: Boolean, set: (EnvironmentState, FeedState<T>) -> EnvironmentState, fetch: suspend (GeoPoint, suspend (FeedState<T>) -> Unit) -> Unit) {
            if (!on) return
            launch {
                located.filterNotNull().map { it.location?.point }.distinctUntilChanged().collectLatest { here ->
                    if (here == null) {
                        state.update { set(it, FeedState.NoLocation) }
                        return@collectLatest
                    }
                    while (true) {
                        fetch(here) { s -> state.update { set(it, s) } }
                        delay(TICK_MS)
                    }
                }
            }
        }

        source(c.quakes, { s, f -> s.copy(quakes = f) }) { here, put ->
            cachedThenFresh(put, { sources.cachedQuakes() }, { sources.refreshQuakes(QUAKES_MAX_AGE_MS) }) { EnvironmentGeo.nearbyQuakes(it, here, c.radiusKm.toDouble()) }
        }
        source(c.hazards, { s, f -> s.copy(hazards = f) }) { here, put ->
            cachedThenFresh(put, { sources.cachedHazards() }, { sources.refreshHazards(HAZARDS_MAX_AGE_MS) }) { EnvironmentGeo.nearbyHazards(it, here) }
        }
        source(c.air != AirQualityMode.OFF, { s, f -> s.copy(air = f) }) { here, put ->
            val query = EnvironmentGeo.round(here, c.air)!!
            cachedThenFresh(put, { sources.cachedAir(query) }, { sources.refreshAir(query, AIR_MAX_AGE_MS) }) { it }
        }
        source(c.openAq, { s, f -> s.copy(openAq = f) }) { here, put -> put(openAq(c.openAqKey, here, System.currentTimeMillis())) }
        if (c.warnings) {
            // The region is looked up on the phone; only its state or country code is ever sent.
            source<Unit>(true, { s, _ -> s }) { here, _ ->
                val region = areas.warningRegionAt(here.lat, here.lon)
                state.update {
                    it.copy(
                        warningRegion = region,
                        nws = if (region is WarningRegion.Nws) (it.nws.takeIf { s -> s is FeedState.Ready } ?: FeedState.Loading) else FeedState.Off,
                        meteoAlarm = if (region is WarningRegion.MeteoAlarm) (it.meteoAlarm.takeIf { s -> s is FeedState.Ready } ?: FeedState.Loading) else FeedState.Off,
                    )
                }
                when (region) {
                    is WarningRegion.Nws -> cachedThenFresh(
                        { f -> state.update { it.copy(nws = f) } },
                        { sources.cachedNws(region.area, System.currentTimeMillis()) },
                        { sources.refreshNws(region.area, NWS_MAX_AGE_MS) },
                    ) { it }
                    is WarningRegion.MeteoAlarm -> cachedThenFresh(
                        { f -> state.update { it.copy(meteoAlarm = f) } },
                        { sources.cachedMeteoAlarm(region.country, System.currentTimeMillis()) },
                        { sources.refreshMeteoAlarm(region.country, METEOALARM_MAX_AGE_MS) },
                    ) { MeteoAlarmFeed.group(it) }
                    null -> Unit
                }
            }
        }
    }

    /**
     * Shows a cached copy at once, then refreshes it if it is stale. A refresh that fails keeps the
     * cached copy and marks it, so the screen never goes from data back to nothing.
     */
    private suspend fun <T, R> cachedThenFresh(
        put: suspend (FeedState<R>) -> Unit,
        cached: suspend () -> Cached<T>?,
        refresh: suspend () -> Boolean,
        shape: (T) -> R,
    ) {
        cached()?.let { put(FeedState.Ready(shape(it.value), it.fetchedAtMs, refreshFailed = false)) }
        val ok = refresh()
        put(cached()?.let { FeedState.Ready(shape(it.value), it.fetchedAtMs, !ok) } ?: FeedState.Unavailable)
    }

    /** Stations in the 0.5° cell, then the latest values of the nearest one that reported recently. */
    private suspend fun openAq(key: String?, here: GeoPoint, now: Long): FeedState<StationReading?> {
        if (key == null) return FeedState.NeedsKey
        val box = EnvironmentGeo.coarseBox(here)
        val stationsOk = sources.refreshOpenAqStations(box, key, OPENAQ_MAX_AGE_MS)
        if (sources.openAqKeyRejected()) return FeedState.KeyRejected
        val stations = sources.cachedOpenAqStations(box) ?: return FeedState.Unavailable
        val (station, km) = OpenAq.nearest(stations.value, here, now, STATION_SILENT_MS)
            ?: return FeedState.Ready(null, stations.fetchedAtMs, !stationsOk)
        val latestOk = sources.refreshOpenAqLatest(station.id, key, OPENAQ_MAX_AGE_MS)
        if (sources.openAqKeyRejected()) return FeedState.KeyRejected
        val latest = sources.cachedOpenAqLatest(station.id)
        val reading = StationReading(station.id, station.name, station.provider, km, latest?.value?.let { OpenAq.values(station, it) }.orEmpty(), station.attribution)
        return FeedState.Ready(reading, latest?.fetchedAtMs ?: stations.fetchedAtMs, !stationsOk || !latestOk)
    }

    private suspend fun regionOf(l: ContextLocation?): String? = l?.let { areas.regionCodeAt(it.point.lat, it.point.lon) }

    private suspend fun areaOf(l: ContextLocation?): AreaName? = l?.let { areas.areaAt(it.point.lat, it.point.lon) }

    private fun loadingIf(on: Boolean): FeedState<Nothing> = if (on) FeedState.Loading else FeedState.Off

    companion object {
        const val QUAKES_MAX_AGE_MS = 10 * 60_000L
        const val HAZARDS_MAX_AGE_MS = 30 * 60_000L
        const val AIR_MAX_AGE_MS = 60 * 60_000L
        /** Older than this, the air card says the reading is stale. */
        const val AIR_STALE_MS = 3 * 3_600_000L
        /** Feeds are stale after three missed refreshes. */
        const val QUAKES_STALE_MS = 3 * QUAKES_MAX_AGE_MS
        const val HAZARDS_STALE_MS = 3 * HAZARDS_MAX_AGE_MS
        const val NWS_MAX_AGE_MS = 10 * 60_000L
        const val METEOALARM_MAX_AGE_MS = 15 * 60_000L
        const val OPENAQ_MAX_AGE_MS = 60 * 60_000L
        const val WARNINGS_STALE_MS = 3 * METEOALARM_MAX_AGE_MS
        const val OPENAQ_STALE_MS = 3 * OPENAQ_MAX_AGE_MS
        /** A station that has not reported for a day is skipped for a nearer one that has. */
        private const val STATION_SILENT_MS = 24 * 3_600_000L
        private const val TICK_MS = 60_000L
        private const val LOCATION_MS = 60_000L
        private const val NO_LOCATION_RETRY_MS = 15_000L
    }
}
