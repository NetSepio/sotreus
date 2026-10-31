package app.sotreus.core.data.field

import android.content.Context
import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.intelligence.fieldwatch.CameraIndex
import app.sotreus.intelligence.fieldwatch.FlockApproach
import app.sotreus.intelligence.fieldwatch.FlockCamera
import app.sotreus.intelligence.fieldwatch.FlockStore
import app.sotreus.intelligence.fieldwatch.GpsSample
import app.sotreus.intelligence.fieldwatch.PathCameras
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * The two known-camera lists from Fieldwatch (OpenStreetMap data, ODbL 1.0). Each is downloaded
 * whole, on request: the request names the list only and carries nothing about this phone. Neither
 * list ships inside the app.
 */
enum class CameraList(val url: String, val fileName: String, val approxBytes: Long) {
    /** United States license plate readers, from the FlockHopper / DeFlock export. */
    PLATES("https://data.dontgetflocked.com/cameras.geojson.gz", "plates.bin", 37_748_018L),
    /** Speed, red-light, gunshot and other cameras, published by Fieldwatch. */
    OTHERS("https://raw.githubusercontent.com/OffGridPete/Fieldwatch/main/dist/fieldwatch-sensors.geojson.gz", "sensors.bin", 616_965L),
}

data class CameraListState(
    val count: Int? = null,
    val savedAtMs: Long? = null,
    /** 0–100 while downloading; 100 while the phone reads the file. */
    val percent: Int? = null,
    val failure: CameraListFailure? = null,
)

enum class CameraListFailure { REACH, TRUST, SERVER, SPACE, LARGE, READ, SAVE, OTHER }

@Singleton
class KnownCamerasRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val dir get() = File(context.filesDir, "cameras").apply { mkdirs() }
    private val _state = MutableStateFlow(CameraList.entries.associateWith { CameraListState() })
    val state: StateFlow<Map<CameraList, CameraListState>> = _state.asStateFlow()
    private val jobs = HashMap<CameraList, Job>()
    private val loading = Mutex()
    private val indexes = HashMap<CameraList, CameraIndex>()

    init {
        scope.launch(Dispatchers.IO) { CameraList.entries.forEach { refreshHeader(it) } }
    }

    private fun file(list: CameraList) = File(dir, list.fileName)

    private fun refreshHeader(list: CameraList) {
        val header = FlockStore.peek(file(list))
        _state.update { it + (list to it.getValue(list).copy(count = header?.count, savedAtMs = header?.savedAt)) }
    }

    /** Downloads [list] in the background. A failed or short download keeps the saved copy. */
    fun download(list: CameraList) {
        if (jobs[list]?.isActive == true) return
        jobs[list] = scope.launch(Dispatchers.IO) {
            set(list) { it.copy(percent = 0, failure = null) }
            val failure = fetch(list)
            if (failure == null) {
                loading.withLock { indexes.remove(list) }
                refreshHeader(list)
            }
            set(list) { it.copy(percent = null, failure = failure) }
        }
    }

    fun cancel(list: CameraList) {
        jobs[list]?.cancel()
        set(list) { it.copy(percent = null) }
    }

    suspend fun delete(list: CameraList) = withContext(Dispatchers.IO) {
        jobs[list]?.cancel()
        file(list).delete()
        loading.withLock { indexes.remove(list) }
        _state.update { it + (list to CameraListState()) }
    }

    /** The saved cameras of the chosen lists, loaded once and kept in memory. */
    suspend fun index(plates: Boolean, others: Boolean): List<CameraIndex> = withContext(Dispatchers.IO) {
        listOfNotNull(CameraList.PLATES.takeIf { plates }, CameraList.OTHERS.takeIf { others }).mapNotNull { list ->
            loading.withLock {
                indexes[list] ?: FlockStore.read(file(list))?.let { CameraIndex(it.cameras) }?.also { indexes[list] = it }
            }
        }
    }

    /** Cameras inside the box from the chosen lists, at most [cap]. */
    suspend fun inBox(plates: Boolean, others: Boolean, south: Double, west: Double, north: Double, east: Double, cap: Int): List<FlockCamera> =
        index(plates, others).flatMap { it.inBox(south, west, north, east, cap) }.take(cap)

    /** Known cameras along [path] within [FlockApproach.PATH_M]. */
    suspend fun tally(plates: Boolean, others: Boolean, path: List<GpsSample>): PathCameras.Tally {
        val indexes = index(plates, others)
        return withContext(Dispatchers.Default) {
            PathCameras.tally(path) { lat, lon, r -> indexes.flatMap { it.near(lat, lon, r) } }
        }
    }

    private fun set(list: CameraList, change: (CameraListState) -> CameraListState) =
        _state.update { it + (list to change(it.getValue(list))) }

    private fun fetch(list: CameraList): CameraListFailure? {
        val dest = file(list)
        val tmp = File(dir, "${list.fileName}.download")
        tmp.delete()
        val savedEtag = FlockStore.peek(dest)?.etag?.ifEmpty { null }
        return try {
            val request = Request.Builder().url(list.url)
                .header("User-Agent", "Sotreus-Android (+https://sotreus.com)")
                .header("Accept", "application/geo+json, application/json, application/gzip")
                .apply { if (savedEtag != null) header("If-None-Match", savedEtag) }
                .build()
            http.newCall(request).execute().use { r ->
                if (r.code == 304) return null
                if (!r.isSuccessful) return CameraListFailure.SERVER
                val length = r.body.contentLength()
                if (length > MAX_BYTES) return CameraListFailure.LARGE
                val total = if (length > 0) length else list.approxBytes
                val room = dir.usableSpace
                if (room in 1 until total * 3 + SPACE_MARGIN) return CameraListFailure.SPACE
                var read = 0L
                r.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            read += n
                            if (read > MAX_BYTES) return CameraListFailure.LARGE
                            out.write(buf, 0, n)
                            set(list) { it.copy(percent = ((read * 100) / total).toInt().coerceIn(0, 99)) }
                        }
                    }
                }
                set(list) { it.copy(percent = 100) }
                val cameras = try {
                    tmp.inputStream().use { FlockStore.parse(it) }
                } catch (_: Exception) {
                    return CameraListFailure.READ
                }
                if (cameras.size < FlockApproach.MIN_COUNT) return CameraListFailure.READ
                if (!FlockStore.install(dest, cameras, r.header("ETag").orEmpty(), System.currentTimeMillis())) return CameraListFailure.SAVE
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (e) {
                is SSLException -> CameraListFailure.TRUST
                is UnknownHostException, is SocketTimeoutException, is SocketException -> CameraListFailure.REACH
                is IOException -> CameraListFailure.REACH
                else -> CameraListFailure.OTHER
            }
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val MAX_BYTES = 80L * 1024 * 1024
        const val SPACE_MARGIN = 8L * 1024 * 1024
    }
}
