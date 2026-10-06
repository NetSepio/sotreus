package app.sotreus.context.satellite

import android.content.Context
import app.sotreus.core.model.SatelliteGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A satellite's orbital elements (three-line element set) from CelesTrak. */
data class OrbitalElements(val name: String, val line1: String, val line2: String, val group: SatelliteGroup) {
    val noradId: Int get() = line1.substring(2, 7).trim().toInt()

    /** Revolutions per day (line 2, columns 53–63). */
    val meanMotion: Double get() = line2.substring(52, 63).trim().toDoubleOrNull() ?: 0.0

    /** About one revolution per day: hangs over one longitude, so it never "passes". */
    val geosynchronous: Boolean get() = meanMotion in 0.9..1.1

    /** Element-set epoch as UTC epoch ms, from line 1 columns 19–32 (YYDDD.DDDDDDDD). */
    val epochMs: Long
        get() {
            val yy = line1.substring(18, 20).trim().toInt()
            val day = line1.substring(20, 32).trim().toDouble()
            val year = if (yy < 57) 2000 + yy else 1900 + yy
            val jan1 = java.time.LocalDate.of(year, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            return jan1 + ((day - 1.0) * 86_400_000L).toLong()
        }
}

data class CatalogState(val elements: List<OrbitalElements>, val fetchedAtMs: Long?)

/**
 * Downloads CelesTrak GP data per group (https://celestrak.org/NORAD/elements/) and caches it on
 * the phone. The request names a group only; it carries no location. Cached data is used offline.
 */
@Singleton
class CelesTrakCatalog @Inject constructor(@ApplicationContext private val context: Context) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val dir get() = File(context.filesDir, "context/celestrak").apply { mkdirs() }

    private fun file(group: SatelliteGroup) = File(dir, "${group.celestrakGroup}.tle")

    /** Cached elements for [groups]; empty when never downloaded. */
    suspend fun cached(groups: Set<SatelliteGroup>): CatalogState = withContext(Dispatchers.IO) {
        val files = groups.map { it to file(it) }.filter { it.second.isFile }
        CatalogState(
            elements = files.flatMap { (g, f) -> parse(f.readText(), g) },
            fetchedAtMs = files.minOfOrNull { it.second.lastModified() },
        )
    }

    /** Refreshes groups older than [maxAgeMs]. Returns false if any download failed. */
    suspend fun refresh(groups: Set<SatelliteGroup>, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        var ok = true
        for (g in groups) {
            val f = file(g)
            if (f.isFile && now - f.lastModified() < maxAgeMs) continue
            val text = runCatching {
                http.newCall(Request.Builder().url("https://celestrak.org/NORAD/elements/gp.php?GROUP=${g.celestrakGroup}&FORMAT=tle").build())
                    .execute().use { r -> if (r.isSuccessful) r.body.string() else null }
            }.getOrNull()
            if (text != null && parse(text, g).isNotEmpty()) f.writeText(text) else ok = false
        }
        ok
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        fun parse(text: String, group: SatelliteGroup): List<OrbitalElements> {
            val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val out = ArrayList<OrbitalElements>()
            var i = 0
            while (i + 2 <= lines.lastIndex) {
                val name = lines[i]
                val l1 = lines[i + 1]
                val l2 = lines[i + 2]
                if (l1.startsWith("1 ") && l2.startsWith("2 ") && l1.length >= 69 && l2.length >= 69) {
                    out += OrbitalElements(name.trim(), l1, l2, group)
                    i += 3
                } else {
                    i += 1
                }
            }
            return out
        }
    }
}
