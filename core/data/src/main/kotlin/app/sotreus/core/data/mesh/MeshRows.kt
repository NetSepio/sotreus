package app.sotreus.core.data.mesh

import app.sotreus.core.database.dao.MeshDao
import app.sotreus.core.database.entity.MeshMessageEntity
import app.sotreus.core.database.entity.MeshNodeEntity
import app.sotreus.core.database.entity.MeshWaypointEntity
import app.sotreus.edge.protocol.EdgeRecordHeader
import app.sotreus.edge.protocol.MeshTextRecord
import app.sotreus.edge.protocol.mesh.MeshDelivery
import app.sotreus.edge.protocol.mesh.MeshMessage
import app.sotreus.edge.protocol.mesh.MeshNode
import app.sotreus.edge.protocol.mesh.MeshNodeId
import app.sotreus.edge.protocol.mesh.MeshWaypoint

/**
 * Turning what the Edge reports into rows (HANDOFF_V3_MESH.md §3.3). One message is one row,
 * whichever way it arrived: a type 4 record (the reliable path), a `mesh_rx` event (the fast one)
 * or the `mesh_messages` list after a reconnect.
 */
object MeshRows {
    /** `ch:<index>` for a channel message, `dm:<other node>` for a direct one. */
    fun conversation(from: Long, to: Long, channel: Int, outgoing: Boolean): String =
        if (to == MeshNodeId.BROADCAST) "ch:$channel" else "dm:${if (outgoing) to else from}"

    fun channelOf(conversation: String): Int? = conversation.removePrefix("ch:").takeIf { conversation.startsWith("ch:") }?.toIntOrNull()
    fun peerOf(conversation: String): Long? = conversation.removePrefix("dm:").takeIf { conversation.startsWith("dm:") }?.toLongOrNull()

    /** [wallFirstMs] is the record's time on the Edge's clock when known (valid or anchored). */
    fun fromRecord(deviceId: String, h: EdgeRecordHeader, body: MeshTextRecord, wallFirstMs: Long?, nowMs: Long): MeshMessageEntity =
        MeshMessageEntity(
            deviceId = deviceId,
            fromNode = body.from,
            packetId = body.packetId,
            toNode = body.to,
            channel = body.channelIndex,
            conversation = conversation(body.from, body.to, body.channelIndex, body.sent),
            text = body.text,
            outgoing = body.sent,
            atMs = wallFirstMs ?: nowMs,
            timeFromEdge = wallFirstMs != null,
            rssi = body.rssi.takeIf { !body.sent },
            snr = body.snr.takeIf { !body.sent },
            hops = (body.hopStart - body.hopLimit).takeIf { !body.sent && body.hopStart > 0 && it >= 0 },
            delivery = if (body.sent) MeshDelivery.SENT.wire else null,
            seq = h.seq,
            read = body.sent,
        )

    fun fromMessage(deviceId: String, m: MeshMessage, nowMs: Long): MeshMessageEntity {
        val edgeTime = m.unixS?.let { it * 1000 }
        return MeshMessageEntity(
            deviceId = deviceId,
            fromNode = m.from,
            packetId = m.packetId,
            toNode = m.to,
            channel = m.channel,
            conversation = conversation(m.from, m.to, m.channel, m.outgoing),
            text = m.text,
            outgoing = m.outgoing,
            atMs = edgeTime ?: (nowMs - (m.agoS ?: 0) * 1000),
            timeFromEdge = edgeTime != null,
            rssi = m.rssi,
            snr = m.snr?.toFloat(),
            replyId = m.replyId,
            reaction = m.reaction,
            pki = m.pki,
            delivery = m.delivery?.wire,
            read = m.outgoing,
        )
    }

    /**
     * Stores messages: new ones are inserted; for one already stored, a record fills its seq, hops
     * and the Edge's time, and a sent message's delivery only moves forward. Returns the rows that
     * were new.
     */
    suspend fun store(dao: MeshDao, rows: List<MeshMessageEntity>): List<MeshMessageEntity> {
        if (rows.isEmpty()) return emptyList()
        val ids = dao.insertMessages(rows)
        val fresh = ArrayList<MeshMessageEntity>()
        rows.forEachIndexed { i, r ->
            if (ids.getOrNull(i) != -1L) { fresh += r; return@forEachIndexed }
            val cur = dao.message(r.deviceId, r.fromNode, r.packetId) ?: return@forEachIndexed
            if (r.seq != null || r.hops != null || (r.timeFromEdge && !cur.timeFromEdge)) {
                dao.fillMessage(r.deviceId, r.fromNode, r.packetId, r.seq, r.hops, r.atMs, r.timeFromEdge)
            }
            val next = MeshDelivery.of(r.delivery)
            if (r.outgoing && next != null) advanceDelivery(dao, r.deviceId, r.packetId, cur.delivery, next, null)
        }
        return fresh
    }

    suspend fun advanceDelivery(dao: MeshDao, deviceId: String, packetId: Long, current: String?, next: MeshDelivery, reason: Int?) {
        val now = MeshDelivery.of(current)
        val after = now?.advance(next) ?: next
        if (after != now) dao.setDelivery(deviceId, packetId, after.wire, reason.takeIf { after == MeshDelivery.FAILED })
    }

    /** The node as the Edge reports it, keeping what this phone knew when the report leaves a field out. */
    fun node(deviceId: String, n: MeshNode, prev: MeshNodeEntity?, nowMs: Long): MeshNodeEntity {
        val heardMs = n.lastHeardUnixS?.let { it * 1000 } ?: n.lastHeardAgoS?.let { nowMs - it * 1000 }
        val p = n.position
        val d = n.device
        val e = n.environment
        val telemetry = d != null || e != null
        return MeshNodeEntity(
            deviceId = deviceId,
            num = n.num,
            longName = n.longName ?: prev?.longName,
            shortName = n.shortName ?: prev?.shortName,
            hwModel = n.hwModel ?: prev?.hwModel,
            role = n.role ?: prev?.role,
            hasKey = n.hasPublicKey || prev?.hasKey == true,
            favorite = n.favorite,
            ignored = n.ignored,
            lastHeardMs = listOfNotNull(heardMs, prev?.lastHeardMs).maxOrNull(),
            rssi = n.rssi ?: prev?.rssi,
            snr = n.snr?.toFloat() ?: prev?.snr,
            hopsAway = n.hopsAway ?: prev?.hopsAway,
            battery = d?.batteryLevel ?: prev?.battery,
            voltage = d?.voltage?.toFloat() ?: prev?.voltage,
            channelUtil = d?.channelUtil?.toFloat() ?: prev?.channelUtil,
            airUtil = d?.airUtilTx?.toFloat() ?: prev?.airUtil,
            uptimeS = d?.uptimeS ?: prev?.uptimeS,
            temperatureC = e?.temperatureC?.toFloat() ?: prev?.temperatureC,
            humidityPct = e?.humidityPct?.toFloat() ?: prev?.humidityPct,
            pressureHpa = e?.pressureHpa?.toFloat() ?: prev?.pressureHpa,
            telemetryMs = if (telemetry) heardMs ?: nowMs else prev?.telemetryMs,
            lat = p?.lat ?: prev?.lat,
            lon = p?.lon ?: prev?.lon,
            altM = p?.altM ?: prev?.altM,
            precisionBits = if (p != null) p.precisionBits else prev?.precisionBits,
            positionMs = p?.let { it.reportedUnixS?.let { s -> s * 1000 } ?: heardMs } ?: prev?.positionMs,
            neighbors = if (n.neighbors.isNotEmpty()) n.neighbors.joinToString(",") { "${it.num}:${it.snr ?: ""}" } else prev?.neighbors,
        )
    }

    fun waypoint(deviceId: String, w: MeshWaypoint, nowMs: Long) = MeshWaypointEntity(
        deviceId = deviceId, id = w.id, fromNode = w.from, name = w.name.ifBlank { "Waypoint" }, description = w.description,
        lat = w.lat, lon = w.lon, expireMs = w.expireUnixS?.let { it * 1000 }, icon = w.icon, atMs = w.unixS?.let { it * 1000 } ?: nowMs,
    )

    fun neighbors(s: String?): List<Pair<Long, Float?>> = s.orEmpty().split(',').mapNotNull { part ->
        val num = part.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
        num to part.substringAfter(':', "").toFloatOrNull()
    }
}
