package app.sotreus.core.data.field

import android.content.Context
import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.model.MapBackground
import app.sotreus.core.model.MapStyles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/** How far a downloaded street area reaches from where the phone was. */
enum class OfflineAreaSize(val km: Int) { SMALL(10), MEDIUM(25), LARGE(50) }

/** A street area kept on this phone. The centre is rounded to [OfflineMaps.ROUND_DEG] before anything is requested. */
@Serializable
data class OfflineArea(val lat: Double, val lon: Double, val km: Int, val savedAtMs: Long, val bytes: Long = 0)

enum class OfflineMapFailure { NO_FIX, REACH, SERVER, LARGE, OTHER }

data class OfflineMapState(
    val area: OfflineArea? = null,
    /** Download progress while one runs. */
    val percent: Int? = null,
    val failure: OfflineMapFailure? = null,
)

/**
 * One downloaded street area for path and signal maps. Downloading is the only time it uses the network: the map
 * server sees this phone's IP address and the rounded area, once. A map set to [MapBackground.OFFLINE] then reads only
 * from this phone. A new download replaces the old area after it finishes; a failed or cancelled one leaves the old
 * area as it was.
 */
@Singleton
class OfflineMapRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val location: PhoneLocation,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(OfflineMapState())
    val state = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var running: OfflineRegion? = null
    private var loaded = false

    /** What a map should draw: [MapBackground.OFFLINE] falls back to the plain grid until an area is saved. */
    fun effective(choice: MapBackground): MapBackground =
        if (choice == MapBackground.OFFLINE && _state.value.area == null) MapBackground.NONE else choice

    /** Reads the saved area once; cheap after that. */
    fun refresh() = onMain {
        if (loaded) return@onMain
        loaded = true
        manager().listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val saved = offlineRegions.orEmpty().mapNotNull { r -> area(r)?.let { r to it } }.maxByOrNull { it.second.savedAtMs }
                saved?.first?.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        val bytes = status?.completedResourceSize ?: 0
                        if (status?.isComplete == true) _state.update { it.copy(area = saved.second.copy(bytes = bytes)) }
                        else saved.first.delete(quietDelete) // Left over from a download that never finished.
                    }
                    override fun onError(error: String?) = Unit
                })
            }
            override fun onError(error: String) = Unit
        })
    }

    /** Downloads streets for [size] around this phone's current fix. Needs a fresh fix; never uses an older one. */
    fun download(size: OfflineAreaSize) = onMain {
        if (running != null) return@onMain
        val fix = location.fresh(System.currentTimeMillis())
        if (fix == null) { _state.update { it.copy(failure = OfflineMapFailure.NO_FIX) }; return@onMain }
        val area = OfflineArea(OfflineMaps.round(fix.lat), OfflineMaps.round(fix.lon), size.km, System.currentTimeMillis())
        _state.update { it.copy(percent = 0, failure = null) }
        // A map left in offline mode switches the shared connection flag off; a download needs it back on.
        MapLibre.setConnected(null)
        val definition = OfflineTilePyramidRegionDefinition(
            MapStyles.STREETS, OfflineMaps.bounds(area), 0.0, OfflineMaps.MAX_ZOOM.toDouble(), context.resources.displayMetrics.density,
        )
        manager().setOfflineMapboxTileCountLimit(OfflineMaps.TILE_LIMIT)
        manager().createOfflineRegion(definition, json.encodeToString(OfflineArea.serializer(), area).toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                running = offlineRegion
                offlineRegion.setObserver(Observer(offlineRegion, area))
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) = fail(null, OfflineMapFailure.OTHER)
        })
    }

    fun cancel() = onMain { running?.let { fail(it, null) } }

    /** Removes the saved area. Maps set to Downloaded area show the plain grid until another is saved. */
    fun delete() = onMain {
        manager().listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions.orEmpty().filter { it != running }.forEach { it.delete(quietDelete) }
                _state.update { it.copy(area = null) }
            }
            override fun onError(error: String) = Unit
        })
    }

    private inner class Observer(private val region: OfflineRegion, private val area: OfflineArea) : OfflineRegion.OfflineRegionObserver {
        override fun onStatusChanged(status: OfflineRegionStatus) {
            if (running != region) return
            if (status.isComplete) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                running = null
                val done = area.copy(bytes = status.completedResourceSize)
                manager().listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        offlineRegions.orEmpty().filter { it.id != region.id }.forEach { it.delete(quietDelete) }
                    }
                    override fun onError(error: String) = Unit
                })
                _state.update { OfflineMapState(area = done) }
            } else if (status.requiredResourceCount > 0) {
                val pct = (100 * status.completedResourceCount / status.requiredResourceCount).toInt().coerceIn(0, 99)
                _state.update { it.copy(percent = pct) }
            }
        }

        override fun onError(error: OfflineRegionError) {
            // A missing tile is normal over open water; the download carries on.
            if (error.reason == OfflineRegionError.REASON_NOT_FOUND) return
            fail(region, if (error.reason == OfflineRegionError.REASON_CONNECTION) OfflineMapFailure.REACH else if (error.reason == OfflineRegionError.REASON_SERVER) OfflineMapFailure.SERVER else OfflineMapFailure.OTHER)
        }

        override fun mapboxTileCountLimitExceeded(limit: Long) = fail(region, OfflineMapFailure.LARGE)
    }

    private fun fail(region: OfflineRegion?, why: OfflineMapFailure?) {
        region?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        region?.delete(quietDelete)
        if (region == null || running == region) running = null
        _state.update { it.copy(percent = null, failure = why) }
    }

    private fun area(region: OfflineRegion): OfflineArea? = runCatching { json.decodeFromString(OfflineArea.serializer(), String(region.metadata)) }.getOrNull()

    private fun manager(): OfflineManager { MapLibre.getInstance(context); return OfflineManager.getInstance(context) }

    private fun onMain(block: () -> Unit) { scope.launch(Dispatchers.Main) { block() } }

    private val quietDelete = object : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = Unit
        override fun onError(error: String) = Unit
    }
}

/** Pure geometry for offline areas, kept apart so it can be tested without a map. */
object OfflineMaps {
    /** About 5.5 km north–south; the smallest area still covers the phone after rounding. */
    const val ROUND_DEG = 0.05
    /** Street vector tiles stop at zoom 14; closer views draw from those. */
    const val MAX_ZOOM = 14
    const val TILE_LIMIT = 12_000L
    private const val KM_PER_DEG = 111.32

    fun round(deg: Double): Double = (deg / ROUND_DEG).roundToInt() * ROUND_DEG

    fun bounds(area: OfflineArea): LatLngBounds {
        val (s, w, n, e) = box(area.lat, area.lon, area.km)
        return LatLngBounds.from(n, e, s, w)
    }

    /** South, west, north, east of a square [km] from the centre in each direction. */
    fun box(lat: Double, lon: Double, km: Int): List<Double> {
        val dLat = km / KM_PER_DEG
        val dLon = km / (KM_PER_DEG * cos(lat * PI / 180).coerceAtLeast(0.05))
        return listOf((lat - dLat).coerceAtLeast(-85.0), (lon - dLon).coerceAtLeast(-180.0), (lat + dLat).coerceAtMost(85.0), (lon + dLon).coerceAtMost(180.0))
    }

    /** Web-mercator tiles from zoom 0 to [MAX_ZOOM] covering the area; shown before a download so its size is known. */
    fun tiles(lat: Double, lon: Double, km: Int): Long {
        val (s, w, n, e) = box(lat, lon, km)
        var total = 0L
        for (z in 0..MAX_ZOOM) {
            val x0 = tileX(w, z); val x1 = tileX(e, z)
            val y0 = tileY(n, z); val y1 = tileY(s, z)
            total += (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
        }
        return total
    }

    private fun tileX(lon: Double, z: Int): Int = floor((lon + 180) / 360 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    private fun tileY(lat: Double, z: Int): Int {
        val r = lat * PI / 180
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }
}
