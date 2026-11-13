package app.sotreus.core.data.edge

import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.pipeline.PastSighting
import app.sotreus.core.database.DatabaseTransactions
import app.sotreus.core.database.dao.EdgeDao
import app.sotreus.core.database.entity.EdgeDeviceEntity
import app.sotreus.core.database.entity.EdgeSightingEntity
import app.sotreus.core.database.entity.EdgeUntimedRecordEntity
import app.sotreus.core.model.EdgeDeployment
import app.sotreus.core.model.LocationSource
import app.sotreus.core.model.WifiRole
import app.sotreus.core.model.WindowLocationKind
import app.sotreus.edge.link.EdgeLink
import app.sotreus.edge.link.EdgeSyncLoop
import app.sotreus.edge.location.MovedChecks
import app.sotreus.edge.location.SightingLocation
import app.sotreus.edge.protocol.EdgePositionRecord
import app.sotreus.edge.protocol.EdgeRecordCodec
import app.sotreus.edge.protocol.EdgeRestartLoss
import app.sotreus.edge.protocol.EdgeTime
import app.sotreus.edge.protocol.Hex
import app.sotreus.edge.protocol.RestartLossPlanner
import app.sotreus.edge.protocol.SightingBody
import app.sotreus.edge.protocol.SyncPlanner
import app.sotreus.edge.protocol.WifiProbeSighting
import app.sotreus.edge.protocol.WifiApSighting
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resumable, idempotent sync of an Edge's record log (HANDOFF_V2_EDGE.md §6.2). The cursor moves
 * only after a page is stored; `(device_id, seq)` is unique, so a repeated page changes nothing.
 * Windows this phone already received live become `edge_sightings` rows only; the rest also
 * become entities, encounters and observations through [ObservationPipeline.recordPast].
 */
@Singleton
class EdgeSync @Inject constructor(
    private val dao: EdgeDao,
    private val pipeline: ObservationPipeline,
    private val tagger: EdgeLocationTagger,
    private val transactions: DatabaseTransactions,
    private val mesh: app.sotreus.core.database.dao.MeshDao,
) {
    data class Outcome(
        val stored: Int,
        val untimed: Int,
        val corrupt: Int,
        val lost: Long,
        val upToDate: Boolean,
        val deletedThrough: Long? = null,
        /** The cursor after the run: every record below it is in the database or was unreadable on the Edge. */
        val cursor: Long = 0,
    )

    private data class Stored(val sightings: Int, val untimed: Int, val committed: Set<Long>)

    /** Corrupt records seen per (device, seq): one seen twice is unreadable on the Edge and may be passed. */
    private val corruptSeen = HashMap<Pair<String, Long>, Int>()

    suspend fun run(
        link: EdgeLink,
        deviceId: String,
        oldest: Long,
        head: Long,
        ringFull: Boolean = true,
        now: () -> Long = System::currentTimeMillis,
        progress: (cursor: Long, head: Long) -> Unit = { _, _ -> },
    ): Outcome {
        val start = dao.device(deviceId) ?: return Outcome(0, 0, 0, 0, true)
        val plan = SyncPlanner.plan(start.syncCursor, oldest, head, ringFull)
        if (plan.upToDate) return Outcome(0, 0, 0, 0, true, cursor = start.syncCursor)
        if (plan.lost > 0) dao.advanceCursor(deviceId, plan.from, now(), plan.lost)
        val anchors = dao.anchors(deviceId).associate { it.boot to it.unixAtBootMs }
        var stored = 0
        var untimed = 0
        // Fetch, commit, move the cursor and confirm (delete after sync) only what is in the database.
        val result = EdgeSyncLoop.run(
            link = link,
            from = plan.from,
            store = { page ->
                val device = dao.device(deviceId) ?: throw IllegalStateException("The Edge was forgotten during a sync")
                val s = store(device, page, anchors, now())
                stored += s.sightings
                untimed += s.untimed
                s.committed
            },
            saveCursor = { c ->
                dao.raiseCursor(deviceId, c, now())
                progress(c, head)
            },
            skipCorrupt = { seq -> (corruptSeen.merge(deviceId to seq, 1, Int::plus) ?: 0) >= 2 },
        )
        dao.device(deviceId)?.let { checkMoved(it, now()) }
        return Outcome(stored, untimed, result.corrupt, plan.lost, false, result.deletedThrough, result.cursor)
    }

    data class BootNote(val lost: Long, val notKnown: Long)

    /**
     * A handshake on [boot] (protocol "Records lost at a restart"): adds the records the Edge's
     * restarts since this phone's last handshake lost and this phone does not hold, and the
     * restarts whose loss is not known. One transaction with the boot it moves to, so each restart
     * is judged once.
     */
    suspend fun noteBoot(deviceId: String, boot: Long, report: EdgeRestartLoss?): BootNote? = transactions.run {
        val d = dao.device(deviceId) ?: return@run null
        val plan = RestartLossPlanner.plan(d.lastBoot, boot, report)
        val lost = plan.ranges.sumOf { r -> (r.last - r.first + 1) - dao.heldSeqs(deviceId, r.first, r.last) }
        dao.noteBoot(deviceId, boot, lost, plan.notKnown)
        BootNote(lost, plan.notKnown)
    }

    /**
     * Records handed off live (`stream_start`): the same store path as a sync, de-duplicated by
     * `(device_id, seq)`. Returns the seqs now in the database, which alone may be acknowledged.
     */
    suspend fun storeLive(deviceId: String, records: List<EdgeRecordCodec.Result>, now: Long = System.currentTimeMillis()): Set<Long> {
        val device = dao.device(deviceId) ?: return emptySet()
        val anchors = dao.anchors(deviceId).associate { it.boot to it.unixAtBootMs }
        return store(device, records, anchors, now).committed
    }

    /**
     * Stores records. Every readable record lands in one table before it counts as committed:
     * timed sightings in `edge_sightings`, untimed ones in `edge_untimed_records`, mesh text in
     * `mesh_messages`, the rest raw in `edge_kept_records`. Re-stored records are ignored (`(device_id, seq)` is unique).
     *
     * One transaction for the page, History rows included: a write that fails leaves nothing
     * behind, so a sighting row that already exists on a retry really is fully stored and its
     * History rows are not written twice. The pipeline's lock is taken first (live sightings
     * wait), then the transaction, so the two never wait on each other.
     */
    private suspend fun store(device: EdgeDeviceEntity, page: List<EdgeRecordCodec.Result>, anchors: Map<Int, Long>, now: Long): Stored =
        pipeline.withPastLock { held -> transactions.run { storeInTransaction(device, page, anchors, now, held) } }

    private suspend fun storeInTransaction(
        device: EdgeDeviceEntity,
        page: List<EdgeRecordCodec.Result>,
        anchors: Map<Int, Long>,
        now: Long,
        held: ObservationPipeline.PastLock,
    ): Stored {
        val rows = ArrayList<EdgeSightingEntity>()
        val past = ArrayList<PastSighting?>()
        val untimed = ArrayList<EdgeUntimedRecordEntity>()
        val kept = ArrayList<app.sotreus.core.database.entity.EdgeKeptRecordEntity>()
        val meshRows = ArrayList<app.sotreus.core.database.entity.MeshMessageEntity>()
        val readable = ArrayList<Long>()
        for (r in page) {
            val ok = r as? EdgeRecordCodec.Result.Ok ?: continue
            val rec = ok.record
            val h = rec.header
            readable += h.seq
            val wall = EdgeTime.wallWindow(h, anchors)
            fun keep() {
                kept += app.sotreus.core.database.entity.EdgeKeptRecordEntity(
                    device.deviceId, h.seq, h.type, h.boot, h.flags, h.firstMs, h.lastMs, Hex.encode(ok.raw), now,
                )
            }
            when (val body = rec.body) {
                is SightingBody -> {
                    if (wall == null) {
                        untimed += EdgeUntimedRecordEntity(device.deviceId, h.seq, h.boot, h.type, h.firstMs, h.lastMs, Hex.encode(ok.raw), now)
                        continue
                    }
                    val key = EdgeObservations.key(body)
                    val loc = tagger.window(device, wall.first, wall.last)
                    val tagged = loc.toTagged(wall.last, LocationSource.PHONE_WITH_EDGE)
                    val covered = dao.overlappingEncounters(key, wall.first, wall.last) > 0
                    rows += sightingRow(device.deviceId, h.seq, key, h.type, wall, body, h.continues, covered, loc)
                    past += if (covered) null else PastSighting(
                        key = key,
                        observation = EdgeObservations.of(body, wall.last, body.rssiMax),
                        firstMs = wall.first,
                        lastMs = wall.last,
                        rssiMax = body.rssiMax,
                        location = tagged,
                        wifiRole = when (body) { is WifiProbeSighting -> WifiRole.CLIENT; is WifiApSighting -> WifiRole.AP; else -> null },
                        placeId = device.linkedPlaceId.takeIf { tagged?.source == LocationSource.EDGE_FIXED },
                    )
                }
                is EdgePositionRecord -> {
                    if (wall != null) tagger.onEdgePosition(body, wall.first)
                    keep()
                }
                // Mesh text goes to the mesh tables (HANDOFF_V3_MESH.md §3.3), one row per message.
                is app.sotreus.edge.protocol.MeshTextRecord -> meshRows += app.sotreus.core.data.mesh.MeshRows.fromRecord(device.deviceId, h, body, wall?.first, now)
                // Edge events and unknown types: kept raw.
                else -> keep()
            }
        }
        // Only rows that were not already stored feed History, so a repeated page adds nothing.
        val inserted = if (rows.isEmpty()) emptyList() else dao.insertSightings(rows)
        val fresh = rows.indices.mapNotNull { i -> past[i]?.takeIf { inserted.getOrNull(i) != -1L } }
        pipeline.recordPast(fresh, now, held)
        if (untimed.isNotEmpty()) dao.insertUntimed(untimed)
        if (kept.isNotEmpty()) dao.insertKept(kept)
        app.sotreus.core.data.mesh.MeshRows.store(mesh, meshRows)
        // Reached only when every write above succeeded; the transaction commits them together.
        return Stored(inserted.count { it != -1L }, untimed.size, readable.toSet())
    }

    private fun sightingRow(
        deviceId: String, seq: Long, key: String, type: Int, wall: LongRange, body: SightingBody,
        continues: Boolean, covered: Boolean, loc: SightingLocation,
    ): EdgeSightingEntity {
        val frames = when (body) {
            is app.sotreus.edge.protocol.BleSighting -> body.reports
            is WifiApSighting -> body.frames
            is WifiProbeSighting -> body.frames
        }
        val base = EdgeSightingEntity(
            deviceId = deviceId, seq = seq, entityId = key, type = type, firstMs = wall.first, lastMs = wall.last,
            rssiLast = body.rssiLast, rssiMax = body.rssiMax, rssiMin = body.rssiMin, frames = frames,
            continues = continues, coveredLive = covered, locKind = WindowLocationKind.NOT_KNOWN, coverage = loc.coverage,
        )
        val src = loc.source.toModel()
        return when (loc) {
            is SightingLocation.Point -> base.copy(locKind = WindowLocationKind.POINT, locSource = src, lat = loc.lat, lon = loc.lon, radiusM = loc.radiusM, altitudeM = loc.altitudeM, verticalAccuracyM = loc.verticalAccuracyM)
            is SightingLocation.AlongRoute -> base.copy(locKind = WindowLocationKind.ALONG_ROUTE, locSource = src, lat = loc.startLat, lon = loc.startLon, endLat = loc.endLat, endLon = loc.endLon, pathM = loc.pathM, radiusM = loc.accuracyM)
            is SightingLocation.Partial -> base.copy(locKind = WindowLocationKind.PARTIAL, locSource = src, lat = loc.lat, lon = loc.lon, radiusM = loc.radiusM)
            is SightingLocation.NotKnown -> base
        }
    }

    /**
     * Moved check for a stationary Edge (HANDOFF_V2_EDGE.md §4.3): builds the Wi-Fi baseline 30
     * minutes after placement, then compares the last hour. Under 30 % overlap holds the fixed
     * position from an hour ago until the owner answers.
     */
    suspend fun checkMoved(device: EdgeDeviceEntity, now: Long) {
        val from = device.placeSetMs
        if (device.deployment != EdgeDeployment.STATIONARY || from == null || device.placementHeldSinceMs != null) return
        val baseline = device.wifiBaseline?.split(',')?.filter { it.isNotBlank() }?.toSet()
        if (baseline == null) {
            if (now - from < BASELINE_MS) return
            val heard = dao.apSightings(device.deviceId, from, from + BASELINE_MS)
                .groupBy { it.entityId.removePrefix("wifi:") }.mapValues { (_, v) -> v.maxOf { it.rssiMax } }
            if (heard.isEmpty()) return
            dao.upsertDevice(device.copy(wifiBaseline = MovedChecks.baseline(heard).joinToString(","), baselineFromMs = from))
            return
        }
        val recent = dao.apSightings(device.deviceId, now - HOUR_MS, now).map { it.entityId.removePrefix("wifi:") }.toSet()
        if (recent.isEmpty()) return
        if (MovedChecks.neighbourhoodChanged(baseline, recent, tagger.limits)) {
            dao.upsertDevice(device.copy(placementHeldSinceMs = now - HOUR_MS))
        }
    }

    private companion object {
        const val BASELINE_MS = 30 * 60_000L
        const val HOUR_MS = 60 * 60_000L
    }
}
