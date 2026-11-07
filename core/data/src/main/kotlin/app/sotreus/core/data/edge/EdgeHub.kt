package app.sotreus.core.data.edge

import android.util.Log
import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.database.dao.EdgeDao
import app.sotreus.core.database.entity.EdgeBootAnchorEntity
import app.sotreus.core.database.entity.EdgeDeviceEntity
import app.sotreus.core.model.EdgeDeployment
import app.sotreus.edge.android.EdgePairing
import app.sotreus.edge.android.EdgePresence
import app.sotreus.edge.link.EdgeHandOff
import app.sotreus.edge.link.EdgeLink
import app.sotreus.edge.link.LinkKind
import app.sotreus.edge.link.EdgeLinkState
import app.sotreus.edge.link.NdjsonEdgeLink
import app.sotreus.edge.link.config
import app.sotreus.edge.link.info
import app.sotreus.edge.link.liveAll
import app.sotreus.edge.link.setTime
import app.sotreus.edge.link.setConfig
import app.sotreus.edge.link.status
import app.sotreus.edge.link.unwatch
import app.sotreus.edge.link.access
import app.sotreus.edge.link.watch
import app.sotreus.edge.location.EdgeLocationSource
import app.sotreus.edge.protocol.DeploymentMode
import app.sotreus.edge.protocol.EdgeCapabilities
import app.sotreus.edge.protocol.EdgeConfig
import app.sotreus.edge.protocol.EdgeEvent
import app.sotreus.edge.protocol.EdgeInfo
import app.sotreus.edge.protocol.EdgeJson
import app.sotreus.edge.protocol.EdgeMode
import app.sotreus.edge.protocol.EdgeRecordCodec
import app.sotreus.edge.protocol.EdgeStatus
import app.sotreus.edge.protocol.EdgeTime
import app.sotreus.edge.protocol.SightingBody
import app.sotreus.edge.protocol.SightingKind
import app.sotreus.intelligence.fieldwatch.Observation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** What the app knows about the paired Edge right now. Times are phone wall-clock ms; 0 = never. */
data class EdgeLive(
    val device: EdgeDeviceEntity? = null,
    val link: EdgeLinkState = EdgeLinkState.Idle,
    val info: EdgeInfo? = null,
    val status: EdgeStatus? = null,
    val statusAtMs: Long = 0,
    val config: EdgeConfig? = null,
    /** Last successful `live` poll (only while a live screen is open). */
    val lastLiveMs: Long = 0,
    /** Last evidence the Edge is listening: a poll, a `seen` or `rssi` event, or a handed-off record. */
    val lastHeardMs: Long = 0,
    val openSightings: Int = 0,
    val syncing: Boolean = false,
    val syncCursor: Long = 0,
    val syncHead: Long = 0,
    val lastError: String? = null,
    val clockOffsetMs: Long? = null,
    val linkRssi: Int? = null,
    val together: Boolean = false,
    val locationSource: EdgeLocationSource = EdgeLocationSource.NONE,
    /** The Edge hands new records straight to this phone (`stream_start`) instead of storing them. */
    val handOff: Boolean = false,
) {
    val connected: Boolean get() = link is EdgeLinkState.Connected

    /** This phone lost the bond (Android deleted it): only pairing again brings the Edge back. */
    val pairingLost: Boolean get() = (link as? EdgeLinkState.Failed)?.pairingLost == true
    val listening: Boolean get() = connected && status?.mode != EdgeMode.MESH
}

/**
 * Runs the link to the paired Edge while the Edge is the sensor (HANDOFF_V2_EDGE.md §3.3): on each
 * connect it checks the device ID, anchors the boot, sets the clock, reads the placement and
 * syncs; while connected it polls `live` every 4 s, reads the link signal every 5 s for
 * "together", and runs this phone's location updates only while they tag Edge sightings.
 * Publishes live sightings as Fieldwatch [Observation]s, like the phone's [app.sotreus.sensing.RadioHub].
 */
@Singleton
class EdgeHub @Inject constructor(
    private val pairing: EdgePairing,
    private val dao: EdgeDao,
    private val sync: EdgeSync,
    private val tagger: EdgeLocationTagger,
    private val location: PhoneLocation,
    private val wifi: app.sotreus.edge.android.EdgeWifi,
    private val secrets: EdgeSecrets,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _observations = MutableSharedFlow<Observation>(extraBufferCapacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val observations: SharedFlow<Observation> = _observations.asSharedFlow()

    private val _rssi = MutableSharedFlow<EdgeEvent.Rssi>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** `watch` readings for the proximity check. */
    val rssi: SharedFlow<EdgeEvent.Rssi> = _rssi.asSharedFlow()

    private val _state = MutableStateFlow(EdgeLive())
    val state: StateFlow<EdgeLive> = _state.asStateFlow()

    private val lock = Mutex()
    private var session: Job? = null
    @Volatile private var link: EdgeLink? = null
    private val syncRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val bodies = object : LinkedHashMap<String, SightingBody>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SightingBody>?) = size > MAX_BODIES
    }

    val running: Boolean get() = session?.isActive == true

    private val _placementConfirmed = MutableStateFlow<app.sotreus.edge.protocol.EdgePlacement?>(null)

    /** The position the Edge reported keeping after the app last sent one, in this run of the app. */
    val placementConfirmed: StateFlow<app.sotreus.edge.protocol.EdgePlacement?> = _placementConfirmed.asStateFlow()

    fun clearPlacementConfirmed() { _placementConfirmed.value = null }

    /**
     * Sends the placement this phone holds when it is marked pending, reads `config.placement` back,
     * and clears the mark only if the Edge kept it. A newer change made during the round trip stays
     * pending for the next connect. Returns what the Edge kept, or null.
     */
    suspend fun pushPlacement(l: EdgeLink, deviceId: String): app.sotreus.edge.protocol.EdgePlacement? {
        val d = dao.device(deviceId)?.takeIf { it.placementPending } ?: return null
        val sent = d.placement()
        l.setConfig(placementJson(sent))
        val kept = l.config().placement?.takeIf { it.keeps(sent) } ?: return null
        val now = dao.device(deviceId) ?: return null
        if (now.placement() != sent) return null
        val mirrored = now.withPlacement(kept).copy(placementPending = false)
        dao.upsertDevice(mirrored)
        tagger.bind(mirrored, _state.value.info?.caps ?: EdgeCapabilities())
        _state.update { it.copy(device = mirrored, config = it.config?.copy(placement = kept)) }
        if (kept.positioned) _placementConfirmed.value = kept
        return kept
    }

    /** Starts the link to the newest paired Edge. Safe to call repeatedly. */
    fun start() {
        if (running) return
        session = scope.launch { runSession() }
    }

    fun stop() {
        val s = session
        session = null
        s?.cancel()
        val l = link
        link = null
        tagger.linkDown(System.currentTimeMillis())
        scope.launch(NonCancellable) { runCatching { l?.close() } }
        _state.update { it.copy(link = EdgeLinkState.Idle, together = false, linkRssi = null) }
    }

    /** Runs [block] on the current link, if connected. For screens (settings, sync now, watch). */
    suspend fun <T> withLink(block: suspend (EdgeLink) -> T): T? {
        val l = link ?: return null
        if (l.state.value !is EdgeLinkState.Connected) return null
        return block(l)
    }

    fun requestSync() {
        syncRequests.tryEmit(Unit)
    }

    @Volatile private var watchJob: Job? = null

    /**
     * Proximity check: watch one radio on the Edge (rssi events at up to 4 Hz), renewed before the
     * firmware's 600 s limit; null stops it. Readings feed the same pipeline trail as live samples.
     */
    fun setWatch(entityId: String?) {
        watchJob?.cancel()
        watchJob = null
        if (!running) return
        if (entityId == null) {
            scope.launch { runCatching { withLink { it.unwatch() } } }
            return
        }
        val kind = if (entityId.startsWith("ble:")) SightingKind.BLE else SightingKind.WIFI_AP
        val address = entityId.substringAfter(':')
        watchJob = scope.launch {
            while (isActive) {
                runCatching { withLink { it.watch(kind, address) } }
                delay(WATCH_RENEW_MS)
            }
        }
    }

    private suspend fun runSession() = coroutineScope {
        val dev = dao.current() ?: run { _state.value = EdgeLive(lastError = "No Edge is paired"); return@coroutineScope }
        _state.value = EdgeLive(device = dev)
        tagger.bind(dev, EdgeCapabilities.parse(runCatching { EdgeJson.parseToJsonElement(dev.capsJson) as JsonObject }.getOrNull()))
        val device = pairing.device(dev.btAddress) ?: run { _state.update { it.copy(lastError = "Bluetooth is not available") }; return@coroutineScope }
        pairing.observePresence(dev.btAddress)
        val transport = withContext(kotlinx.coroutines.Dispatchers.Main) { pairing.open(device, this@coroutineScope) }
        val l = NdjsonEdgeLink(transport, this)
        link = l
        launch { l.events.collect { onEvent(it) } }
        launch { sharedNetworkLoop(dev.deviceId) }
        launch {
            EdgePresence.events.collect { e ->
                if (e.address.equals(dev.btAddress, ignoreCase = true) && e.present && l.state.value !is EdgeLinkState.Connected) {
                    _state.update { it.copy(lastError = null) }
                }
            }
        }
        var connection: Job? = null
        transport.state.collect { s ->
            _state.update { it.copy(link = s) }
            when (s) {
                is EdgeLinkState.Connected -> {
                    connection?.cancel()
                    connection = launch { runConnected(l, dev.deviceId) }
                }
                is EdgeLinkState.Lost, is EdgeLinkState.Failed, EdgeLinkState.Idle -> {
                    connection?.cancel()
                    connection = null
                    tagger.linkDown(System.currentTimeMillis())
                    _state.update { it.copy(together = false, linkRssi = null, lastError = (s as? EdgeLinkState.Failed)?.reason ?: it.lastError) }
                }
                else -> Unit
            }
        }
    }

    private suspend fun runConnected(l: EdgeLink, deviceId: String) = coroutineScope {
        val ok = runCatching { handshake(l, deviceId) }.onFailure {
            Log.w(TAG, "handshake failed", it)
            _state.update { s -> s.copy(lastError = it.message) }
        }.getOrNull() ?: return@coroutineScope
        val caps = ok.caps
        launch { syncLoop(l, deviceId) } // syncs at once, then hands off
        if (caps.liveSightings) launch { liveLoop(l) }
        launch { linkLoop(l) }
        launch { clockLoop(l) }
        launch {
            tagger.wantsPhoneLocation.collectLatest { wanted ->
                if (wanted) location.updates(fast = true).collect { fix ->
                    location.offer(fix)
                    tagger.onPhoneFix(fix)
                }
            }
        }
    }

    /** device ID check, boot anchor, clock, placement. */
    private suspend fun handshake(l: EdgeLink, deviceId: String): EdgeInfo {
        val info = l.info()
        if (info.deviceId != deviceId) {
            l.close()
            throw IllegalStateException("This is a different Edge. Pair it again from Settings.")
        }
        if (!info.supported) throw IllegalStateException("This Edge speaks protocol ${info.protocol}; update the app or the Edge.")
        val now = System.currentTimeMillis()
        dao.connected(deviceId, now, info.firmware, EdgeJson.encodeToString(JsonObject.serializer(), JsonObject(info.caps.raw.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })), info.probeSalt)
        val sent = System.currentTimeMillis()
        val st = l.status()
        val received = System.currentTimeMillis()
        val mid = sent + (received - sent) / 2
        dao.upsertAnchor(EdgeBootAnchorEntity(deviceId, EdgeTime.bootKey(info.boot), EdgeTime.anchor(st.uptimeMs, mid)))
        val offset = st.unixMs?.let { EdgeTime.clockOffset(it, sent, received) }
        l.setTime(System.currentTimeMillis(), tzOffsetMinutes())
        // A placement set on this phone while the Edge was away goes first.
        runCatching { pushPlacement(l, deviceId) }.onFailure { Log.w(TAG, "placement not sent; it stays pending", it) }
        val cfg = runCatching { l.config() }.getOrNull()
        val stored = dao.device(deviceId)
        // The Edge's placement is the source of truth, except while this phone still has one to send.
        val updated = stored?.let { d -> if (d.placementPending) d else cfg?.placement?.let { p -> d.withPlacement(p) } ?: d }
        if (updated != null && updated != stored) dao.upsertDevice(updated)
        tagger.bind(updated ?: stored, info.caps)
        _state.update {
            it.copy(device = updated ?: stored, info = info, status = st, statusAtMs = received, config = cfg, clockOffsetMs = offset, lastError = null, locationSource = tagger.source())
        }
        return info
    }

    /**
     * Sync, confirm (delete after sync), then hand off (protocol §4): on every connect, and again
     * whenever the hand-off ends. While the hand-off runs, new records come straight to this phone,
     * so sync requests wait for it to end. A request during a sync runs one more sync after it.
     */
    private suspend fun syncLoop(l: EdgeLink, deviceId: String): Unit = coroutineScope {
        val handOff = EdgeHandOff(l, this)
        var handOffSupported = true
        var shortHandOffs = 0
        try {
            syncRequests.onSubscription { emit(Unit) }.collect {
                if (handOff.active) return@collect
                delay(SYNC_DEBOUNCE_MS)
                lock.withLock {
                    try {
                        val log = l.status().also { st -> _state.update { it.copy(status = st, statusAtMs = System.currentTimeMillis()) } }.log ?: return@withLock
                        _state.update { it.copy(syncing = true) }
                        val dev = dao.device(deviceId)
                        if (dev != null && dev.dataLink == app.sotreus.core.model.EdgeDataLink.BLUETOOTH_WIFI_BURST && minOf(log.count, log.head - dev.syncCursor + 1) > BURST_MIN_RECORDS) {
                            runCatching { burstSync(l, dev, log.oldest, log.head, log.ringFull) }.onFailure { Log.w(TAG, "Wi-Fi burst failed; continuing over Bluetooth", it) }
                        }
                        sync.run(l, deviceId, log.oldest, log.head, log.ringFull, progress = ::syncProgress)
                        if (handOffSupported && l.kind == LinkKind.BLUETOOTH && shortHandOffs < MAX_SHORT_HAND_OFFS) {
                            handOffSupported = startHandOff(l, deviceId, handOff) { lastedMs ->
                                shortHandOffs = if (lastedMs < SHORT_HAND_OFF_MS) shortHandOffs + 1 else 0
                            }
                        }
                        dao.device(deviceId)?.let { d -> tagger.bind(d, _state.value.info?.caps ?: EdgeCapabilities()); _state.update { it.copy(device = d) } }
                        // After sync_confirm the stored count drops; show the Edge's own number.
                        runCatching { l.status() }.onSuccess { st -> _state.update { it.copy(status = st, statusAtMs = System.currentTimeMillis()) } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.update { it.copy(lastError = e.message) }
                    } finally {
                        _state.update { it.copy(syncing = false) }
                    }
                }
            }
        } finally {
            // Link gone or session over: the Edge stores what was not acknowledged by itself.
            handOff.linkDown()
            _state.update { it.copy(handOff = false) }
        }
    }

    private fun syncProgress(cursor: Long, head: Long) = _state.update { it.copy(syncCursor = cursor, syncHead = head) }

    /**
     * `stream_start`, then a catch-up sync of what the Edge stored before it (up to `stored_head`).
     * Handed-off records go through the same store path as a sync. The cursor moves past them only
     * once the catch-up is complete, so it never skips a stored record. Returns false when the Edge
     * has no hand-off (older firmware).
     */
    private suspend fun startHandOff(l: EdgeLink, deviceId: String, handOff: EdgeHandOff, ended: (lastedMs: Long) -> Unit): Boolean {
        val caughtUp = java.util.concurrent.atomic.AtomicBoolean(false)
        val startedAt = System.currentTimeMillis()
        var lastMovedCheck = startedAt
        val cursor = dao.device(deviceId)?.syncCursor ?: 1
        val started = handOff.start(
            from = cursor,
            store = { batch -> sync.storeLive(deviceId, batch) },
            onCommitted = { through ->
                val now = System.currentTimeMillis()
                heard()
                if (caughtUp.get()) {
                    dao.raiseCursor(deviceId, through + 1, now)
                    _state.update { it.copy(syncCursor = maxOf(it.syncCursor, through + 1), syncHead = maxOf(it.syncHead, through)) }
                }
                if (now - lastMovedCheck > MOVED_CHECK_MS) {
                    lastMovedCheck = now
                    dao.device(deviceId)?.let { sync.checkMoved(it, now) }
                }
            },
            onStopped = { reason ->
                Log.i(TAG, "hand-off ended: $reason")
                _state.update { it.copy(handOff = false) }
                ended(System.currentTimeMillis() - startedAt)
                requestSync()
            },
        )
        return when (started) {
            EdgeHandOff.Start.Unsupported -> false
            is EdgeHandOff.Start.Refused -> {
                Log.w(TAG, "hand-off refused: ${started.reason}")
                true
            }
            is EdgeHandOff.Start.Started -> {
                _state.update { it.copy(handOff = true) }
                val out = sync.run(l, deviceId, oldest = 0, head = started.storedHead, ringFull = false, progress = ::syncProgress)
                if (out.cursor > started.storedHead) {
                    caughtUp.set(true)
                    handOff.committedThrough?.let { dao.raiseCursor(deviceId, it + 1, System.currentTimeMillis()) }
                }
                true
            }
        }
    }

    /** A big backlog over the Edge's own network: join, pull binary records, leave (HANDOFF_V2_EDGE.md §3.2). */
    private suspend fun burstSync(l: EdgeLink, dev: EdgeDeviceEntity, oldest: Long, head: Long, ringFull: Boolean) {
        val access = l.access()
        secrets.savePin(dev.deviceId, access.pin)
        val joined = wifi.joinEdgeNetwork(access.apSsid, access.apPassword) ?: return
        try {
            val http = app.sotreus.edge.android.HttpEdgeLink(app.sotreus.edge.android.EdgeWifi.EDGE_AP_URL, wifi.clientFor(joined.network), access.pin, app.sotreus.edge.link.LinkKind.EDGE_WIFI)
            if (!http.connect(tzOffsetMinutes())) return
            try {
                sync.run(http, dev.deviceId, oldest, head, ringFull, progress = ::syncProgress)
            } finally {
                http.close()
            }
        } finally {
            joined.release()
        }
    }

    /** A stationary Edge on a shared network: sync over the LAN while Bluetooth is out of range. */
    private suspend fun sharedNetworkLoop(deviceId: String) = coroutineScope {
        while (isActive) {
            val dev = dao.device(deviceId)
            if (dev != null && dev.dataLink == app.sotreus.core.model.EdgeDataLink.SHARED_NETWORK && !_state.value.connected) {
                runCatching {
                    val pin = secrets.pin(deviceId) ?: return@runCatching
                    val found = wifi.find(dev.suffix) ?: return@runCatching
                    val host = app.sotreus.edge.android.EdgeWifi.lanHost(dev.suffix)
                    val http = app.sotreus.edge.android.HttpEdgeLink(app.sotreus.edge.android.EdgeWifi.lanUrl(dev.suffix), wifi.clientFor(host, found.address), pin, app.sotreus.edge.link.LinkKind.SHARED_NETWORK)
                    if (!http.connect(tzOffsetMinutes())) return@runCatching
                    try {
                        runCatching { pushPlacement(http, deviceId) }.onFailure { Log.w(TAG, "placement not sent over the network", it) }
                        lock.withLock {
                            val log = http.status().log ?: return@withLock
                            _state.update { it.copy(syncing = true) }
                            sync.run(http, deviceId, log.oldest, log.head, log.ringFull, progress = ::syncProgress)
                        }
                    } finally {
                        _state.update { it.copy(syncing = false) }
                        http.close()
                    }
                }.onFailure { Log.w(TAG, "shared-network sync failed", it) }
            }
            delay(SHARED_NETWORK_SYNC_MS)
        }
    }

    /**
     * The `live` table (about 100 rows of JSON) is the heaviest traffic on the link, and with 27-byte
     * link packets it crowds out the hand-off. So it is polled only while a live screen is open,
     * every [LIVE_MS]; otherwise `seen` events and handed-off records carry the sightings.
     */
    private suspend fun liveLoop(l: EdgeLink) {
        liveViewers.map { it > 0 }.distinctUntilChanged().collectLatest { visible ->
            while (visible) {
                if (_state.value.status?.mode != EdgeMode.MESH) {
                    runCatching { l.liveAll() }.onSuccess { (rows, _) ->
                        val now = System.currentTimeMillis()
                        rows.forEach { row ->
                            val key = EdgeObservations.key(row.kind, row.address)
                            val body = synchronized(bodies) { bodies[key] } ?: EdgeObservations.bare(row.kind, row.address, row.rssiLast, row.rssiMax)
                            _observations.tryEmit(EdgeObservations.of(body, now - row.agoMs, row.rssiLast))
                        }
                        _state.update { it.copy(lastLiveMs = now, lastHeardMs = now, openSightings = rows.size) }
                    }
                }
                delay(LIVE_MS)
            }
        }
    }

    private val liveViewers = MutableStateFlow(0)

    /** A live screen (Now, with the Edge as the sensor) became visible or hidden. */
    fun liveVisible(visible: Boolean) = liveViewers.update { (it + if (visible) 1 else -1).coerceAtLeast(0) }

    private fun heard() = _state.update { it.copy(lastHeardMs = System.currentTimeMillis()) }

    private suspend fun linkLoop(l: EdgeLink) = coroutineScope {
        while (isActive) {
            val now = System.currentTimeMillis()
            l.linkRssi()?.let { dbm ->
                tagger.linkRssi(now, dbm)
                _state.update { it.copy(linkRssi = dbm, together = tagger.isTogether(now), locationSource = tagger.source(now)) }
            }
            delay(LINK_RSSI_MS)
        }
    }

    private suspend fun clockLoop(l: EdgeLink) = coroutineScope {
        while (isActive) {
            delay(CLOCK_CHECK_MS)
            runCatching {
                val sent = System.currentTimeMillis()
                val st = l.status()
                val received = System.currentTimeMillis()
                val offset = st.unixMs?.let { EdgeTime.clockOffset(it, sent, received) }
                if (EdgeTime.needsSetTime(offset, tagger.limits.maxClockOffsetMs)) l.setTime(System.currentTimeMillis(), tzOffsetMinutes())
                _state.update { it.copy(status = st, statusAtMs = received, clockOffsetMs = offset) }
            }
        }
    }

    private fun onEvent(ev: EdgeEvent) {
        when (ev) {
            is EdgeEvent.Seen -> {
                val body = ev.body?.let { EdgeRecordCodec.decodeBody(ev.kind.recordType, it) as? SightingBody }
                    ?: EdgeObservations.bare(ev.kind, ev.address, ev.rssi, ev.rssi)
                synchronized(bodies) { bodies[EdgeObservations.key(ev.kind, ev.address)] = body }
                _observations.tryEmit(EdgeObservations.of(body, System.currentTimeMillis(), ev.rssi))
                heard()
            }
            is EdgeEvent.Obs -> requestSync()
            is EdgeEvent.Rssi -> {
                _rssi.tryEmit(ev)
                val kind = if (ev.kind == "ble") SightingKind.BLE else SightingKind.WIFI_AP
                val key = EdgeObservations.key(kind, ev.address)
                val body = synchronized(bodies) { bodies[key] } ?: EdgeObservations.bare(kind, ev.address, ev.rssi, ev.rssi)
                _observations.tryEmit(EdgeObservations.of(body, System.currentTimeMillis(), ev.rssi))
                heard()
            }
            is EdgeEvent.Status -> _state.update { it.copy(status = ev.status, statusAtMs = System.currentTimeMillis()) }
            is EdgeEvent.Mode -> _state.update { s -> s.copy(status = s.status?.copy(mode = ev.mode)) }
            else -> Unit
        }
    }

    private fun tzOffsetMinutes(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    companion object {
        private const val TAG = "SotreusEdge"
        const val LIVE_MS = 20_000L
        private const val LINK_RSSI_MS = 5_000L
        private const val CLOCK_CHECK_MS = 10 * 60_000L
        private const val SYNC_DEBOUNCE_MS = 2_000L
        private const val MAX_BODIES = 2_000
        private const val WATCH_RENEW_MS = 9 * 60_000L
        private const val BURST_MIN_RECORDS = 5_000L
        private const val MAX_SHORT_HAND_OFFS = 3
        private const val SHORT_HAND_OFF_MS = 60_000L
        private const val MOVED_CHECK_MS = 10 * 60_000L
        private const val SHARED_NETWORK_SYNC_MS = 10 * 60_000L

        /** Whether a live sighting key belongs to a Wi-Fi client asking for networks. */
        fun isProbe(observation: Observation): Boolean = observation.extras == EdgeObservations.PROBE_EXTRAS

        fun kindOf(observation: Observation): SightingKind = when {
            observation.kind == app.sotreus.intelligence.fieldwatch.RadioKind.BLE -> SightingKind.BLE
            isProbe(observation) -> SightingKind.WIFI_PROBE
            else -> SightingKind.WIFI_AP
        }
    }
}

/** The Edge's stored placement is the source of truth; mirror it on the device row. */
internal fun EdgeDeviceEntity.withPlacement(p: app.sotreus.edge.protocol.EdgePlacement): EdgeDeviceEntity = copy(
    deployment = if (p.mode == DeploymentMode.STATIONARY) EdgeDeployment.STATIONARY else EdgeDeployment.TRAVEL,
    placeLat = p.lat, placeLon = p.lon, placeAccuracyM = p.accuracyM, placeSetMs = p.setUnixMs, placeSource = p.source,
)
