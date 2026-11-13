package app.sotreus.core.data.mesh

import android.util.Log
import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.data.edge.EdgeHub
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.database.dao.EdgeDao
import app.sotreus.core.database.dao.MeshDao
import app.sotreus.core.database.entity.MeshConversationEntity
import app.sotreus.edge.link.EdgeLink
import app.sotreus.edge.link.EdgeRequestException
import app.sotreus.edge.link.config
import app.sotreus.edge.link.meshCanned
import app.sotreus.edge.link.meshChannelUrl
import app.sotreus.edge.link.meshMessages
import app.sotreus.edge.link.meshNewIdentity
import app.sotreus.edge.link.meshNode
import app.sotreus.edge.link.meshNodes
import app.sotreus.edge.link.meshRegions
import app.sotreus.edge.link.meshRemoveNode
import app.sotreus.edge.link.meshRequestPosition
import app.sotreus.edge.link.meshSend
import app.sotreus.edge.link.meshSetPosition
import app.sotreus.edge.link.meshStatus
import app.sotreus.edge.link.meshStoreForward
import app.sotreus.edge.link.meshTak
import app.sotreus.edge.link.meshTakChat
import app.sotreus.edge.link.meshTakPosition
import app.sotreus.edge.link.meshTraceroute
import app.sotreus.edge.link.meshWaypoint
import app.sotreus.edge.link.meshWaypoints
import app.sotreus.edge.link.setConfig
import app.sotreus.edge.protocol.EdgeEvent
import app.sotreus.edge.protocol.EdgeMode
import app.sotreus.edge.protocol.mesh.MeshConfig
import app.sotreus.edge.protocol.mesh.MeshConfigPatch
import app.sotreus.edge.protocol.mesh.MeshGeo
import app.sotreus.edge.protocol.mesh.MeshPositionMode
import app.sotreus.edge.protocol.mesh.MeshRegions
import app.sotreus.edge.protocol.mesh.MeshStatus
import app.sotreus.edge.protocol.mesh.MeshTakUser
import app.sotreus.edge.protocol.mesh.MeshTraceroute
import app.sotreus.edge.protocol.obj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** What the mesh looks like right now. Messages and nodes are read from the database instead. */
data class MeshLive(
    val deviceId: String? = null,
    /** The Edge's last known mode is Mesh: Mesh takes Now's tab (HANDOFF_V3_MESH.md §2.1). */
    val meshMode: Boolean = false,
    val connected: Boolean = false,
    /** This Edge's own node number. */
    val selfNum: Long? = null,
    val status: MeshStatus? = null,
    val statusAtMs: Long? = null,
    val config: MeshConfig? = null,
    val regions: MeshRegions? = null,
    val refreshing: Boolean = false,
    /** The newest traceroute result per destination, and destinations still waiting for one. */
    val routes: Map<Long, MeshTraceroute> = emptyMap(),
    val routeAtMs: Map<Long, Long> = emptyMap(),
    val routing: Set<Long> = emptySet(),
    val tak: List<MeshTakUser> = emptyList(),
    /** The owner's answer to the position disclosure (M8); null until answered. */
    val positionChoice: MeshPositionMode? = null,
    /** This phone's location is being handed to the Edge right now. */
    val sharing: Boolean = false,
    val lastSharedMs: Long? = null,
    /** The region Sotreus set from this phone's mobile network country, while it stays set (shown once). */
    val regionFromNetwork: String? = null,
    val error: String? = null,
) {
    /** Sending needs the link and a running radio (region set). */
    val canSend: Boolean get() = connected && meshMode && status?.running != false && config?.regionSet != false
}

/**
 * Mesh mode over the Edge link (HANDOFF_V3_MESH.md, docs/plans/mesh-mode.md §3). Runs beside
 * [EdgeHub]: on each connect in Mesh mode it reads status, config, nodes and the last messages;
 * between connects it applies `mesh_*` events. Messages also arrive as type 4 records through the
 * sync and hand-off, which [MeshRows] merges with what events brought, one row per message.
 *
 * This phone's location goes to the mesh only in Mesh mode, while connected, in `client` position
 * mode, after the owner's answer to the disclosure (§3.1).
 */
@Singleton
class MeshHub @Inject constructor(
    private val edge: EdgeHub,
    private val dao: MeshDao,
    private val edgeDao: EdgeDao,
    private val location: PhoneLocation,
    private val notifier: MeshNotifier,
    private val country: PhoneCountry,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(MeshLive())
    val state: StateFlow<MeshLive> = _state.asStateFlow()

    /** The conversation on screen: no notification for it. */
    @Volatile var openConversation: String? = null

    private val refreshLock = Mutex()
    private var started = false
    private var shareJob: Job? = null

    @Synchronized fun start() {
        if (started) return
        started = true
        scope.launch { runCatching { backfillKept() }.onFailure { Log.w(TAG, "mesh backfill", it) } }
        scope.launch { edge.meshEvents.collect { runCatching { onEvent(it) }.onFailure { e -> Log.w(TAG, "mesh event", e) } } }
        scope.launch {
            edge.state.map { s ->
                // Connected counts once the handshake has read `info`: a refresh before it can meet a link still settling.
                Triple(s.device?.deviceId, s.connected && s.info != null, (s.status?.mode?.takeIf { it != EdgeMode.UNKNOWN }?.wire ?: s.device?.lastMode) == EdgeMode.MESH.wire)
            }.distinctUntilChanged().collectLatest { (id, connected, mesh) ->
                val choice = id?.let { edgeDao.device(it)?.meshPositionChoice }?.let(MeshPositionMode::of)
                _state.update {
                    if (it.deviceId != id) MeshLive(deviceId = id, meshMode = mesh, connected = connected, positionChoice = choice)
                    else it.copy(meshMode = mesh, connected = connected, positionChoice = choice, sharing = it.sharing && connected && mesh)
                }
                // A mode change restarts the Edge about 2.5 s after it reports the new mode: wait, so a
                // refresh does not meet the restart. collectLatest drops the wait if the link goes.
                if (id != null && connected && mesh) {
                    kotlinx.coroutines.delay(REFRESH_SETTLE_MS)
                    refresh()
                }
            }
        }
        scope.launch {
            combine(_state.map { it.connected && it.meshMode }.distinctUntilChanged(), _state.map { it.config?.position?.mode }.distinctUntilChanged(),
                _state.map { it.positionChoice }.distinctUntilChanged()) { on, mode, choice -> on && mode == MeshPositionMode.CLIENT && choice == MeshPositionMode.CLIENT }
                .distinctUntilChanged()
                .collectLatest { share -> if (share) sharePosition() else _state.update { it.copy(sharing = false) } }
        }
    }

    /**
     * Mesh text that an earlier version kept raw (`edge_kept_records`, type 4) moves to the mesh
     * tables once. The rows keep their seq, so held-record counts for restarts stay the same.
     */
    private suspend fun backfillKept() {
        while (true) {
            val kept = edgeDao.keptMeshText()
            var moved = 0
            if (kept.isEmpty()) return
            kept.groupBy { it.deviceId }.forEach { (deviceId, rows) ->
                val messages = rows.mapNotNull { row ->
                    val ok = app.sotreus.edge.protocol.Hex.decode(row.recordHex)?.let { app.sotreus.edge.protocol.EdgeRecordCodec.decode(it) }
                        as? app.sotreus.edge.protocol.EdgeRecordCodec.Result.Ok ?: return@mapNotNull null
                    val body = ok.record.body as? app.sotreus.edge.protocol.MeshTextRecord ?: return@mapNotNull null
                    val h = ok.record.header
                    MeshRows.fromRecord(deviceId, h, body, h.firstMs.takeIf { h.timeValid }, row.receivedAtMs).copy(read = true)
                }
                MeshRows.store(dao, messages)
                // Only what moved leaves the raw table; a record that cannot be read stays kept.
                edgeDao.deleteKept(deviceId, messages.mapNotNull { it.seq })
                moved += messages.size
            }
            if (moved == 0) return
        }
    }

    // ---- Refresh -------------------------------------------------------------------------------

    /** Reads everything the screens show. Safe to call again (Nodes pull-to-refresh, reconnects). */
    suspend fun refresh(nodes: Boolean = true) = refreshLock.withLock {
        val id = _state.value.deviceId ?: return@withLock
        _state.update { it.copy(refreshing = true) }
        try {
            edge.withLink { l ->
                val now = System.currentTimeMillis()
                val cfg = MeshConfig.parse(l.config().raw.obj("mesh"))
                val st = l.meshStatus()
                _state.update { it.copy(config = cfg, status = st, statusAtMs = now, selfNum = st.nodeNum ?: it.selfNum, error = null) }
                if (_state.value.regions == null) runCatching { l.meshRegions() }.onSuccess { r -> _state.update { it.copy(regions = r) } }
                if (cfg != null && !cfg.regionSet) fillRegion(l)
                if (nodes) storeNodes(l, id)
                MeshRows.store(dao, l.meshMessages().map { MeshRows.fromMessage(id, it, now) })
                runCatching { l.meshWaypoints() }.onSuccess { list -> dao.upsertWaypoints(list.map { MeshRows.waypoint(id, it, now) }) }
                runCatching { l.meshTak() }.onSuccess { t -> _state.update { it.copy(tak = t) } }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "mesh refresh failed", e)
            _state.update { it.copy(error = e.message) }
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    /**
     * An unset region keeps the radio silent. Fill it from this phone's mobile network country when
     * that names exactly one region this firmware knows; the owner changes it in Mesh settings.
     */
    private suspend fun fillRegion(l: EdgeLink) {
        val guess = app.sotreus.edge.protocol.mesh.MeshRegionGuess.forCountry(country.iso()) ?: return
        val known = _state.value.regions?.regions ?: return
        if (guess !in known) return
        val cfg = MeshConfig.parse(l.setConfig(MeshConfigPatch.fields("region" to guess)).raw.obj("mesh"))
        Log.i(TAG, "region set to $guess from the mobile network country")
        val st = runCatching { l.meshStatus() }.getOrNull()
        _state.update { it.copy(config = cfg ?: it.config, status = st ?: it.status, regionFromNetwork = guess) }
    }

    private suspend fun storeNodes(l: EdgeLink, deviceId: String) {
        val now = System.currentTimeMillis()
        val list = l.meshNodes(paged = edge.state.value.info?.caps?.meshNodesPaging == true)
        if (list.isEmpty()) return
        val prev = dao.nodes(deviceId, list.map { it.num }).associateBy { it.num }
        dao.upsertNodes(list.map { MeshRows.node(deviceId, it, prev[it.num], now) })
    }

    // ---- Events --------------------------------------------------------------------------------

    private suspend fun onEvent(ev: EdgeEvent) {
        val id = _state.value.deviceId ?: return
        val now = System.currentTimeMillis()
        when (ev) {
            is EdgeEvent.MeshRx -> {
                val fresh = MeshRows.store(dao, listOf(MeshRows.fromMessage(id, ev.message, now)))
                fresh.forEach { m -> if (m.conversation != openConversation && dao.muted(id, m.conversation) != true && !m.reaction) notifier.message(m, nodeName(id, m.fromNode), channelName(m.conversation)) }
                if (ev.message.conversation() == openConversation) dao.markRead(id, ev.message.conversation())
            }
            is EdgeEvent.MeshTx -> MeshRows.advanceDelivery(dao, id, ev.packetId, deliveryOf(id, ev.packetId), ev.delivery, ev.reason)
            is EdgeEvent.MeshNodeHeard -> dao.upsertNodes(listOf(MeshRows.node(id, ev.node, dao.node(id, ev.node.num), now)))
            is EdgeEvent.MeshRoute -> _state.update {
                it.copy(routes = it.routes + (ev.trace.to to ev.trace), routeAtMs = it.routeAtMs + (ev.trace.to to now), routing = it.routing - ev.trace.to)
            }
            is EdgeEvent.MeshWaypointHeard -> edge.withLink { l -> l.meshWaypoints() }?.let { list -> dao.upsertWaypoints(list.map { MeshRows.waypoint(id, it, now) }) }
            is EdgeEvent.MeshTakHeard -> edge.withLink { l -> l.meshTak() }?.let { t -> _state.update { it.copy(tak = t) } }
            else -> Unit
        }
    }

    private fun app.sotreus.edge.protocol.mesh.MeshMessage.conversation() = MeshRows.conversation(from, to, channel, outgoing)

    private suspend fun deliveryOf(deviceId: String, packetId: Long): String? =
        _state.value.selfNum?.let { dao.delivery(deviceId, it, packetId) }

    private suspend fun nodeName(deviceId: String, num: Long): String? = dao.node(deviceId, num)?.let { it.shortName ?: it.longName }

    private fun channelName(conversation: String): String? {
        val ch = MeshRows.channelOf(conversation) ?: return null
        val cfg = _state.value.config
        return cfg?.channels?.getOrNull(ch)?.displayName(ch, cfg.preset) ?: "Channel $ch"
    }

    // ---- Actions (screens) ---------------------------------------------------------------------

    /** `caps.mesh_target_fields`: `node` / `waypoint_id` instead of the clashing `id` (protocol §2). */
    private fun targetFields(): Boolean = edge.state.value.info?.caps?.meshTargetFields == true

    /** Runs [block] on the link, or fails with the reason the UI shows. */
    private suspend fun <T> onLink(block: suspend (EdgeLink, String) -> T): Result<T> {
        val id = _state.value.deviceId ?: return Result.failure(EdgeRequestException("No Edge is paired"))
        return runCatching { edge.withLink { block(it, id) } ?: throw EdgeRequestException("Connect to your Edge to send") }
    }

    /** Sends a message to [conversation]. Stored at once as Sent; `mesh_tx` events move it on. */
    suspend fun send(conversation: String, text: String, replyId: Long? = null, reaction: Boolean = false): Result<Long> = onLink { l, id ->
        val self = _state.value.selfNum ?: l.meshStatus().nodeNum ?: throw EdgeRequestException("Your Edge has no node number yet")
        val channel = MeshRows.channelOf(conversation)
        val peer = MeshRows.peerOf(conversation)
        val to = peer ?: app.sotreus.edge.protocol.mesh.MeshNodeId.BROADCAST
        val packet = l.meshSend(text, to, channel ?: 0, wantAck = true, replyId = replyId, reaction = reaction)
        val now = System.currentTimeMillis()
        val pki = peer != null && dao.node(id, peer)?.hasKey == true
        MeshRows.store(dao, listOf(app.sotreus.core.database.entity.MeshMessageEntity(
            deviceId = id, fromNode = self, packetId = packet, toNode = to, channel = channel ?: 0, conversation = conversation, text = text,
            outgoing = true, atMs = now, timeFromEdge = false, replyId = replyId, reaction = reaction, pki = pki,
            delivery = app.sotreus.edge.protocol.mesh.MeshDelivery.QUEUED.wire, read = true,
        )))
        packet
    }

    suspend fun sendQuick(conversation: String, index: Int, text: String): Result<Long> =
        if (MeshRows.channelOf(conversation) == 0) onLink { l, _ -> l.meshCanned(index) }.onSuccess { refreshMessagesSoon() }
        else send(conversation, text)

    private fun refreshMessagesSoon() {
        scope.launch {
            val id = _state.value.deviceId ?: return@launch
            edge.withLink { l -> MeshRows.store(dao, l.meshMessages().map { MeshRows.fromMessage(id, it, System.currentTimeMillis()) }) }
        }
    }

    fun dismissRegionNote() = _state.update { it.copy(regionFromNetwork = null) }

    suspend fun markRead(conversation: String) {
        val id = _state.value.deviceId ?: return
        dao.markRead(id, conversation)
    }

    suspend fun setMuted(conversation: String, muted: Boolean) {
        val id = _state.value.deviceId ?: return
        dao.upsertConversation(MeshConversationEntity(id, conversation, muted))
    }

    suspend fun traceroute(num: Long): Result<Long> = onLink { l, _ ->
        _state.update { it.copy(routing = it.routing + num) }
        runCatching { l.meshTraceroute(num) }.onFailure { _state.update { s -> s.copy(routing = s.routing - num) } }.getOrThrow()
    }

    suspend fun requestPosition(num: Long): Result<Unit> = onLink { l, _ -> l.meshRequestPosition(num) }

    suspend fun setFavorite(num: Long, favorite: Boolean): Result<Unit> = onLink { l, id ->
        l.meshNode(num, favorite = favorite, targetFields = targetFields())
        dao.setFavorite(id, num, favorite)
    }

    suspend fun setIgnored(num: Long, ignored: Boolean): Result<Unit> = onLink { l, id ->
        l.meshNode(num, ignored = ignored, targetFields = targetFields())
        dao.setIgnored(id, num, ignored)
    }

    /**
     * Removes a node from the Edge's table (`caps.mesh_node_remove`) and from this phone's list. Its
     * messages stay. No event follows, so the table is read again; the node returns if heard again.
     */
    suspend fun removeNode(num: Long): Result<Unit> = onLink { l, id ->
        l.meshRemoveNode(num, targetFields())
        dao.deleteNode(id, num)
        runCatching { storeNodes(l, id) }
        Unit
    }

    /** Asks a store & forward router heard on the mesh for missed channel messages. */
    suspend fun missedMessages(router: Long?): Result<Unit> = onLink { l, _ -> l.meshStoreForward(router) }

    suspend fun addWaypoint(lat: Double, lon: Double, name: String, description: String?, expireMs: Long?): Result<Long> = onLink { l, id ->
        val wid = l.meshWaypoint(lat, lon, name, description, expireMs?.let { it / 1000 }, targetFields = targetFields())
        val now = System.currentTimeMillis()
        dao.upsertWaypoints(listOf(app.sotreus.core.database.entity.MeshWaypointEntity(id, wid, _state.value.selfNum, name, description, lat, lon, expireMs, null, now)))
        wid
    }

    /** Writes part of `config.mesh` and reads the result back. */
    suspend fun setConfig(patch: kotlinx.serialization.json.JsonObject): Result<MeshConfig?> = onLink { l, _ ->
        val cfg = MeshConfig.parse(l.setConfig(patch).raw.obj("mesh"))
        _state.update { it.copy(config = cfg ?: it.config) }
        runCatching { l.meshStatus() }.onSuccess { st -> _state.update { it.copy(status = st, statusAtMs = System.currentTimeMillis()) } }
        cfg
    }

    suspend fun channelLink(): Result<String> = onLink { l, _ -> l.meshChannelUrl() }

    /** TAK GeoChat to all chat rooms on the primary channel. */
    suspend fun takChat(text: String): Result<Unit> = onLink { l, _ -> l.meshTakChat(text) }

    /** This Edge's mesh position as a TAK position report (needs a position and a channel that allows one). */
    suspend fun takPosition(): Result<Unit> = onLink { l, _ -> l.meshTakPosition() }

    suspend fun refreshTak(): Result<Unit> = onLink { l, _ -> l.meshTak().let { t -> _state.update { it.copy(tak = t) } } }

    /** Applies a Meshtastic channel link: [replace] swaps all channels (and preset, region, hop limit). */
    suspend fun importChannels(url: String, replace: Boolean): Result<String> = onLink { l, _ ->
        val out = l.meshChannelUrl(url, replace)
        val cfg = MeshConfig.parse(l.config().raw.obj("mesh"))
        _state.update { it.copy(config = cfg ?: it.config) }
        out
    }

    suspend fun newIdentity(): Result<String> = onLink { l, _ ->
        val out = l.meshNewIdentity()
        val st = l.meshStatus()
        _state.update { it.copy(status = st, selfNum = st.nodeNum) }
        out
    }

    /**
     * The owner's position choice (M8 and Mesh settings, HANDOFF_V3_MESH.md §3.1), stored on this
     * phone and written to the Edge. [fixed] is the map point for [MeshPositionMode.FIXED].
     */
    suspend fun choosePosition(mode: MeshPositionMode, fixed: Pair<Double, Double>? = null): Result<Unit> {
        val id = _state.value.deviceId ?: return Result.failure(EdgeRequestException("No Edge is paired"))
        edgeDao.setMeshPositionChoice(id, mode.wire)
        _state.update { it.copy(positionChoice = mode) }
        val patch = when (mode) {
            MeshPositionMode.CLIENT -> MeshConfigPatch.positionClient()
            MeshPositionMode.OFF -> MeshConfigPatch.positionOff()
            MeshPositionMode.FIXED -> fixed?.let { MeshConfigPatch.positionFixed(it.first, it.second) } ?: return Result.failure(EdgeRequestException("Pick a point first"))
        }
        return setConfig(patch).map { }
    }

    // ---- This phone's position on the mesh (§3.1) ---------------------------------------------

    private suspend fun sharePosition() {
        if (!location.hasPermission()) {
            _state.update { it.copy(sharing = false) }
            return
        }
        _state.update { it.copy(sharing = true) }
        app.sotreus.core.data.service.EdgeLinkService.meshSharing.value = true
        try { shareUpdates() } finally { app.sotreus.core.data.service.EdgeLinkService.meshSharing.value = false }
    }

    private suspend fun shareUpdates() {
        var last: app.sotreus.sensing.LocationSource.Fix? = null
        var lastSentMs = 0L
        location.updates(fast = false).collect { fix ->
            if (fix.mock) return@collect
            val now = System.currentTimeMillis()
            val interval = (_state.value.config?.position?.intervalS ?: DEFAULT_INTERVAL_S) * 1000
            val moved = last?.let { MeshGeo.distanceM(it.lat, it.lon, fix.lat, fix.lon) } ?: Double.MAX_VALUE
            val due = now - lastSentMs >= interval || (moved >= MOVED_M && now - lastSentMs >= MIN_GAP_MS)
            if (!due) return@collect
            val ok = edge.withLink { l -> l.meshSetPosition(fix.lat, fix.lon, (fix.mslAltitudeM ?: fix.altitudeM)?.toInt()) } != null
            if (ok) {
                last = fix
                lastSentMs = now
                _state.update { it.copy(lastSharedMs = now) }
            }
        }
    }

    companion object {
        private const val TAG = "SotreusMesh"
        /** Update the Edge when this phone has moved this far… */
        const val MOVED_M = 25.0
        /** …but not more often than this. The Edge broadcasts on its own interval. */
        const val MIN_GAP_MS = 30_000L
        const val DEFAULT_INTERVAL_S = 900L
        /** Longer than the Edge's 2.5 s between reporting a new mode and restarting into it. */
        const val REFRESH_SETTLE_MS = 3_500L
    }
}
