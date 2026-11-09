package app.sotreus.edge.link

import app.sotreus.edge.protocol.BleLinkResult
import app.sotreus.edge.protocol.EdgeAccess
import app.sotreus.edge.protocol.EdgeCommands
import app.sotreus.edge.protocol.EdgeConfig
import app.sotreus.edge.protocol.EdgeEvent
import app.sotreus.edge.protocol.EdgeInfo
import app.sotreus.edge.protocol.EdgeMode
import app.sotreus.edge.protocol.EdgeProtocol
import app.sotreus.edge.protocol.EdgeStatus
import app.sotreus.edge.protocol.LivePage
import app.sotreus.edge.protocol.LiveRow
import app.sotreus.edge.protocol.SightingKind
import app.sotreus.edge.protocol.obj
import kotlinx.serialization.json.JsonObject

// Typed calls over any [EdgeLink]. A refused command throws [EdgeRequestException] with the
// Edge's own error text.

private suspend fun EdgeLink.ok(build: (Long) -> String): EdgeEvent.Response {
    val r = request(build = build)
    if (!r.ok) throw EdgeRequestException(r.error ?: "refused")
    return r
}

suspend fun EdgeLink.info(): EdgeInfo =
    ok { EdgeCommands.info(it) }.body.let { it.obj("info") ?: it }.let { EdgeInfo.parse(it) } ?: throw EdgeRequestException("info without device_id")

suspend fun EdgeLink.status(): EdgeStatus = EdgeStatus.parse(ok { EdgeCommands.status(it) }.body.let { it.obj("status") ?: it })

suspend fun EdgeLink.config(): EdgeConfig = EdgeConfig.parse(ok { EdgeCommands.getConfig(it) }.body.let { it.obj("config") ?: it })

suspend fun EdgeLink.setConfig(partial: JsonObject): EdgeConfig =
    EdgeConfig.parse(ok { EdgeCommands.setConfig(it, partial) }.body.let { it.obj("config") ?: it })

/** Mode change: the Edge restarts into [mode] about 2.5 s later. */
suspend fun EdgeLink.setMode(mode: EdgeMode) {
    ok { EdgeCommands.setMode(it, mode) }
}

suspend fun EdgeLink.setTime(unixMs: Long, tzOffsetMin: Int) {
    ok { EdgeCommands.setTime(it, unixMs, tzOffsetMin) }
}

/** Every open sighting, paging through `live` (at most [maxPages] pages). */
suspend fun EdgeLink.liveAll(maxPages: Int = 5): Pair<List<LiveRow>, Long?> {
    val rows = ArrayList<LiveRow>()
    var offset = 0
    var uptime: Long? = null
    repeat(maxPages) {
        val page = LivePage.parse(ok { EdgeCommands.live(it, offset, EdgeProtocol.LIVE_PAGE) }.body)
        rows += page.rows
        uptime = page.uptimeMs ?: uptime
        offset = page.nextOffset ?: return rows to uptime
    }
    return rows to uptime
}

suspend fun EdgeLink.watch(kind: SightingKind, address: String, seconds: Int = EdgeProtocol.WATCH_SECONDS) {
    ok { EdgeCommands.watch(it, kind, address, seconds) }
}

suspend fun EdgeLink.unwatch() {
    ok { EdgeCommands.unwatch(it) }
}

/** PIN and access point details. Shown only on request; the PIN is stored Keystore-wrapped. */
/** Full-size Bluetooth packets for this phone: stores [fullSize], or only reads when it is null. */
suspend fun EdgeLink.bleLink(fullSize: Boolean?): BleLinkResult =
    BleLinkResult.parse(ok { EdgeCommands.bleLink(it, fullSize) }.body) ?: throw EdgeRequestException("ble_link without full_size")

suspend fun EdgeLink.access(): EdgeAccess = EdgeAccess.parse(ok { EdgeCommands.access(it) }.body) ?: throw EdgeRequestException("access without pin")
