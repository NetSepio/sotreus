package app.sotreus.core.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.sotreus.core.crypto.Sha256
import app.sotreus.core.crypto.toHex
import app.sotreus.core.database.dao.EntityDao
import app.sotreus.core.database.dao.ObservationDao
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.EntityEntity
import app.sotreus.core.database.entity.ObservationEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Privacy-aware local export (architecture handoff §25). The default is privacy-reduced:
 * coarse coordinates, hashed identifiers, no notes, 15-minute time buckets, no friend presence.
 * Raw export is an explicit choice made on the export sheet, with a warning.
 */
data class ExportOptions(
    val coordinates: Coordinates = Coordinates.COARSE,
    val hashIdentifiers: Boolean = true,
    val includeNotes: Boolean = false,
    val timeBucketMinutes: Int = 15,
) {
    enum class Coordinates { EXACT, COARSE, NONE }

    val isRaw: Boolean get() = coordinates == Coordinates.EXACT || !hashIdentifiers || includeNotes || timeBucketMinutes == 0

    companion object {
        val PrivacyReduced = ExportOptions()
    }
}

@Singleton
class ExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val entities: EntityDao,
    private val observations: ObservationDao,
    private val sessions: SessionDao,
    private val places: PlaceDao,
) {
    suspend fun exportEntity(entityId: String, options: ExportOptions): Uri? {
        val entity = entities.get(entityId) ?: return null
        val obs = observations.allForEntity(entityId)
        return write("entity", document(options, listOf(entity), obs, sessionName = null))
    }

    suspend fun exportSession(sessionId: Long, options: ExportOptions): Uri? {
        val session = sessions.get(sessionId) ?: return null
        val obs = observations.forSession(sessionId)
        val ents = obs.map { it.entityId }.distinct().chunked(500).flatMap { entities.getAll(it) }
        return write("session", document(options, ents, obs, session.name))
    }

    private suspend fun document(options: ExportOptions, ents: List<EntityEntity>, obs: List<ObservationEntity>, sessionName: String?): String {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val placeNames = places.observeAllOnce()
        fun id(raw: String) = if (options.hashIdentifiers) Sha256.hmac(salt, raw.toByteArray()).copyOf(8).toHex() else raw
        fun time(ms: Long) = if (options.timeBucketMinutes > 0) ms - ms % (options.timeBucketMinutes * 60_000L) else ms
        fun JsonObjectBuilder.coords(lat: Double?, lon: Double?) {
            if (lat == null || lon == null) return
            when (options.coordinates) {
                ExportOptions.Coordinates.EXACT -> { put("lat", lat); put("lon", lon) }
                ExportOptions.Coordinates.COARSE -> { put("lat", (lat * 100).roundToLong() / 100.0); put("lon", (lon * 100).roundToLong() / 100.0) }
                ExportOptions.Coordinates.NONE -> Unit
            }
        }
        return buildJsonObject {
            put("format", "sotreus-export")
            put("version", 1)
            put("privacyReduced", !options.isRaw)
            put("identifiers", if (options.hashIdentifiers) "hashed-per-export" else "raw")
            put("coordinates", options.coordinates.name.lowercase())
            put("timeBucketMinutes", options.timeBucketMinutes)
            put("note", "Observations, not identities. Places are where this phone was at the time of observation.")
            sessionName?.let { put("session", it) }
            put(
                "entities",
                JsonArray(
                    ents.map { e ->
                        buildJsonObject {
                            put("id", id(e.id))
                            put("radio", e.radio.name)
                            put("family", e.family?.name)
                            put("label", e.userState.name)
                            put("firstSeen", time(e.firstSeenMs))
                            put("lastSeen", time(e.lastSeenMs))
                            if (!options.hashIdentifiers) {
                                put("address", e.address)
                                put("advertisedName", e.advertisedName)
                            }
                            if (options.includeNotes) put("note", e.note)
                        }
                    },
                ),
            )
            put(
                "observations",
                JsonArray(
                    obs.map { o ->
                        buildJsonObject {
                            put("entity", id(o.entityId))
                            put("at", time(o.observedAtMs))
                            put("rssi", o.rssi)
                            put("provenance", o.provenance.name)
                            o.placeId?.let { put("place", placeNames[it] ?: "") }
                            coords(o.lat, o.lon)
                        }
                    },
                ),
            )
        }.toString()
    }

    private suspend fun PlaceDao.observeAllOnce(): Map<Long, String> =
        observeAll().first().associate { it.id to it.name }

    private fun write(kind: String, json: String): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "sotreus-$kind-${System.currentTimeMillis()}.json")
        file.writeText(json)
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }

    /** Writes arbitrary text (e.g. a single-record proof) for sharing. */
    fun writeText(name: String, text: String): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(text)
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }
}
