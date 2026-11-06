package app.sotreus.core.data.edge

import android.content.Intent
import android.content.IntentSender
import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.EdgeDao
import app.sotreus.core.database.entity.EdgeBootAnchorEntity
import app.sotreus.core.database.entity.EdgeDeviceEntity
import app.sotreus.core.model.EdgeDeployment
import app.sotreus.core.model.SensingSource
import app.sotreus.edge.android.EdgePairing
import app.sotreus.edge.link.EdgeLink
import app.sotreus.edge.link.EdgeLinkState
import app.sotreus.edge.link.EdgeRequestException
import app.sotreus.edge.link.NdjsonEdgeLink
import app.sotreus.edge.link.access
import app.sotreus.edge.link.config
import app.sotreus.edge.link.info
import app.sotreus.edge.link.setConfig
import app.sotreus.edge.link.setMode
import app.sotreus.edge.link.setTime
import app.sotreus.edge.link.status
import app.sotreus.edge.location.GeoFix
import app.sotreus.edge.location.PlacementAverager
import app.sotreus.edge.protocol.DeploymentMode
import app.sotreus.edge.protocol.EdgeInfo
import app.sotreus.edge.protocol.EdgeJson
import app.sotreus.edge.protocol.EdgeMode
import app.sotreus.edge.protocol.EdgePlacement
import app.sotreus.edge.protocol.EdgeTime
import app.sotreus.edge.protocol.SyncPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** What the Edge screens need: pairing, deployment and position, settings on the Edge, forget. */
@Singleton
class EdgeRepository @Inject constructor(
    private val dao: EdgeDao,
    private val hub: EdgeHub,
    private val pairing: EdgePairing,
    private val settings: SettingsRepository,
    private val tagger: EdgeLocationTagger,
    private val location: PhoneLocation,
    private val secrets: EdgeSecrets,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val devices: Flow<List<EdgeDeviceEntity>> = dao.observeDevices()
    val live: StateFlow<EdgeLive> = hub.state
    val lastFix: StateFlow<GeoFix?> = tagger.lastFix

    private val _positionSaved = kotlinx.coroutines.flow.MutableStateFlow<EdgePlacement?>(null)

    /** The placement the Edge reported keeping after the last save in this run of the app. */
    val positionSaved: StateFlow<EdgePlacement?> = _positionSaved

    fun clearPositionSaved() { _positionSaved.value = null }

    fun untimedCount(deviceId: String): Flow<Int> = dao.observeUntimedCount(deviceId)
    fun sightingCount(deviceId: String): Flow<Int> = dao.observeSightingCount(deviceId)

    /** Pairing step 1: Android's picker, filtered to devices advertising the Sotreus service. */
    fun associate(activity: android.app.Activity, onPicker: (IntentSender) -> Unit, onError: (String) -> Unit) = pairing.associate(activity, onPicker, onError)

    sealed interface PairResult {
        /** Bonded and read. [waiting] records were stored on the Edge before pairing. */
        data class Paired(val info: EdgeInfo, val oldest: Long, val head: Long, val waiting: Long, val firstMs: Long?, val lastMs: Long?) : PairResult
        data object Cancelled : PairResult
        data class Failed(val reason: String) : PairResult
    }

    @Volatile private var pending: EdgeLink? = null


    /**
     * Pairing step 2, with the picker's result: bond (Android asks for the code on the Edge's
     * display), connect, read `info`, set the clock and store the device. The link stays open for
     * [finishSetup].
     */
    val useSystemPicker: Boolean get() = pairing.useSystemPicker

    /** Android 8–11: the app's own short scan for Edges (see [EdgePairing.useSystemPicker]). */
    fun scanForEdges(): Flow<EdgePairing.FoundEdge> = pairing.scan()

    suspend fun pair(result: Intent?): PairResult {
        val picked = pairing.deviceFrom(result) ?: return PairResult.Failed("No Edge was picked")
        return pairDevice(picked)
    }

    /** Bond with [picked] (Android asks for the code on the Edge's display), connect, read and store it. */
    suspend fun pairDevice(picked: android.bluetooth.BluetoothDevice): PairResult {
        hub.stop()
        // Connect first, then bond on that LE link (BleGattTransport); the owner types the code meanwhile.
        val transport = pairing.open(picked, scope, autoConnect = false)
        val link = NdjsonEdgeLink(transport, scope)
        val end = withTimeoutOrNull(PAIR_TIMEOUT_MS) {
            transport.state.first { it is EdgeLinkState.Connected || it is EdgeLinkState.Failed }
        }
        if (end !is EdgeLinkState.Connected) {
            link.close()
            return PairResult.Failed((end as? EdgeLinkState.Failed)?.reason ?: "Could not connect to the Edge. Keep it close and try again.")
        }
        val bonded = picked
        return runCatching {
            val info = link.info()
            if (!info.supported) throw EdgeRequestException("This Edge speaks protocol ${info.protocol}. Update the app or the Edge.")
            val sent = System.currentTimeMillis()
            val st = link.status()
            val received = System.currentTimeMillis()
            link.setTime(System.currentTimeMillis(), tzOffsetMinutes())
            val cfg = runCatching { link.config() }.getOrNull()
            val head = st.log?.head ?: 0
            val oldest = st.log?.oldest ?: 0
            val existing = dao.device(info.deviceId)
            val address = pairing.bondedAddressFor(bonded, info.suffix)
            // Pairing again (the phone lost its bond) keeps the owner's settings and the sync cursor.
            val base = existing?.copy(
                name = info.name, suffix = info.suffix, hardware = info.hardware, firmware = info.firmware, protocol = info.protocol,
                capsJson = capsJson(info), probeSalt = info.probeSalt, btAddress = address, pairedAtMs = System.currentTimeMillis(),
            ) ?: EdgeDeviceEntity(
                deviceId = info.deviceId, name = info.name, suffix = info.suffix, hardware = info.hardware, firmware = info.firmware,
                protocol = info.protocol, capsJson = capsJson(info), probeSalt = info.probeSalt, btAddress = address,
                pairedAtMs = System.currentTimeMillis(), syncCursor = SyncPlanner.startFromNow(head),
            )
            dao.upsertDevice(cfg?.placement?.let { base.withPlacement(it) } ?: base)
            dao.upsertAnchor(EdgeBootAnchorEntity(info.deviceId, EdgeTime.bootKey(info.boot), EdgeTime.anchor(st.uptimeMs, sent + (received - sent) / 2)))
            pairing.observePresence(pairing.bondedAddressFor(bonded, info.suffix))
            pending = link
            // Records actually stored: seqs have gaps (hand-off, deleted after sync), so not head - oldest + 1.
            val waiting = if (existing != null || head == 0L) 0 else st.log?.count ?: 0
            PairResult.Paired(info, oldest, head, waiting, null, null)
        }.getOrElse {
            link.close()
            PairResult.Failed(it.message ?: "Pairing failed")
        }
    }

    /** Pairing step 3: deployment, tagging and the pre-pairing records question. Makes the Edge the sensor. */
    suspend fun finishSetup(deviceId: String, deployment: EdgeDeployment, phoneTagging: Boolean, importOlder: Boolean, oldest: Long) {
        val d = dao.device(deviceId) ?: return
        val link = pending
        pending = null
        val mode = if (deployment == EdgeDeployment.STATIONARY) DeploymentMode.STATIONARY else DeploymentMode.TRAVEL
        runCatching { link?.setConfig(placementJson(EdgePlacement(mode))) }
        dao.upsertDevice(
            d.copy(
                deployment = deployment, phoneTagging = phoneTagging,
                syncCursor = if (importOlder) SyncPlanner.importAll(oldest) else d.syncCursor,
            ),
        )
        runCatching { link?.close() }
        settings.setSensingSource(SensingSource.EDGE)
        hub.stop()
        hub.start()
    }

    /** Cancels a pairing left at step 3. */
    suspend fun abandonSetup() {
        runCatching { pending?.close() }
        pending = null
    }

    suspend fun setDeployment(deployment: EdgeDeployment) = onEdge { l, d ->
        val mode = if (deployment == EdgeDeployment.STATIONARY) DeploymentMode.STATIONARY else DeploymentMode.TRAVEL
        l.setConfig(placementJson(EdgePlacement(mode)))
        save(d.copy(deployment = deployment))
    }

    /** A stationary Edge's position, from averaged phone fixes or a map pick. Resets the moved check. */
    /**
     * Stores a stationary position on the Edge, then reads `config.placement` back: the result is
     * what the Edge actually keeps, and that is what the device row mirrors.
     */
    suspend fun setPosition(lat: Double, lon: Double, accuracyM: Float, source: String): Result<EdgePlacement> = onEdge { l, d ->
        val now = System.currentTimeMillis()
        val p = EdgePlacement(DeploymentMode.STATIONARY, lat, lon, accuracyM, now, source)
        l.setConfig(placementJson(p))
        val kept = l.config().placement?.takeIf { it.positioned && it.mode == DeploymentMode.STATIONARY }
            ?: throw EdgeRequestException("The Edge did not keep the position. Try again.")
        save(d.withPlacement(kept).copy(wifiBaseline = null, baselineFromMs = null, placementHeldSinceMs = null))
        _positionSaved.value = kept
        kept
    }

    /** Whether "Set from this phone" can run: permission, then location services. */
    fun phoneLocationAccess(): PhoneLocationAccess = when {
        !location.hasPermission() -> PhoneLocationAccess.NO_PERMISSION
        !location.isEnabled() -> PhoneLocationAccess.OFF
        else -> PhoneLocationAccess.OK
    }

    /** Moved check answer "still here": the held sightings get the fixed position after all. */
    suspend fun confirmStillHere(deviceId: String) {
        val d = dao.device(deviceId) ?: return
        val held = d.placementHeldSinceMs ?: return
        val lat = d.placeLat ?: return
        val lon = d.placeLon ?: return
        val acc = d.placeAccuracyM ?: return
        dao.applyFixedPosition(deviceId, held, lat, lon, acc.toDouble())
        save(d.copy(placementHeldSinceMs = null))
    }

    suspend fun setPhoneTagging(on: Boolean) {
        val d = dao.current() ?: return
        save(d.copy(phoneTagging = on))
    }

    suspend fun setLinkedPlace(placeId: Long?) {
        val d = dao.current() ?: return
        save(d.copy(linkedPlaceId = placeId))
    }

    /** Mode switch; the Edge restarts into it about 2.5 s later. */
    suspend fun setMode(mode: EdgeMode) = onEdge { l, _ -> l.setMode(mode) }

    suspend fun setAwareness(partial: Map<String, Any>) = onEdge { l, _ ->
        l.setConfig(JsonObject(mapOf("awareness" to JsonObject(partial.mapValues { (_, v) -> if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString().toIntOrNull()) }))))
    }

    fun syncNow() = hub.requestSync()

    suspend fun reopenPairing() = onEdge { l, _ -> l.request { app.sotreus.edge.protocol.EdgeCommands.pairingOpen(it) } }

    suspend fun restartEdge() = onEdge { l, _ -> l.request { app.sotreus.edge.protocol.EdgeCommands.reboot(it) } }

    /** Moved check answer "it moved": the position stays held until a new one is set. */
    suspend fun markMoved() {
        val d = dao.current() ?: return
        if (d.placementHeldSinceMs == null) save(d.copy(placementHeldSinceMs = System.currentTimeMillis()))
    }

    /**
     * The data link (HANDOFF_V2_EDGE.md §3.2). Wi-Fi choices fetch the PIN over the Bluetooth bond
     * now, so later Wi-Fi syncs need no typing; it is stored Keystore-wrapped.
     */
    suspend fun setDataLink(dataLink: app.sotreus.core.model.EdgeDataLink) = onEdge { l, d ->
        if (dataLink != app.sotreus.core.model.EdgeDataLink.BLUETOOTH) secrets.savePin(d.deviceId, l.access().pin)
        save(d.copy(dataLink = dataLink))
    }

    /** The owner's explicit switch back to this phone's radios (never automatic). */
    suspend fun usePhoneRadios() {
        settings.setSensingSource(SensingSource.PHONE)
    }

    suspend fun useEdge() {
        if (dao.current() != null) settings.setSensingSource(SensingSource.EDGE)
    }

    /** Forgets the Edge on this phone. Sightings already stored stay. */
    suspend fun forget(deviceId: String) {
        val d = dao.device(deviceId) ?: return
        hub.stop()
        pairing.forget(d.btAddress)
        dao.deleteDevice(deviceId)
        secrets.forget(deviceId)
        settings.setSensingSource(SensingSource.PHONE)
    }

    /** "Use this phone's location" for a stationary Edge: up to 30 s of fixes, averaged. */
    /** Averages this phone's fixes for up to 30 s; estimates within [acceptM] are accepted. */
    fun averagePosition(acceptM: Float): Flow<PlacementAverager.Estimate> = flow {
        val averager = PlacementAverager(tagger.limits.copy(stationaryAcceptM = acceptM).sane())
        withTimeoutOrNull(tagger.limits.stationaryAverageMs) {
            location.updates(fast = true).collect { fix ->
                averager.add(fix.toGeo())
                averager.estimate()?.let { emit(it) }
            }
        }
    }

    private suspend fun save(d: EdgeDeviceEntity) {
        dao.upsertDevice(d)
        tagger.bind(d, live.value.info?.caps ?: app.sotreus.edge.protocol.EdgeCapabilities())
    }

    private suspend fun <T> onEdge(block: suspend (EdgeLink, EdgeDeviceEntity) -> T): Result<T> {
        val d = dao.current() ?: return Result.failure(EdgeRequestException("No Edge is paired"))
        return runCatching { hub.withLink { block(it, d) } ?: throw EdgeRequestException("Connect to your Edge to change this") }
    }

    private fun placementJson(p: EdgePlacement): JsonObject {
        // Mode only keeps any stored position; a position is sent whole.
        val body = if (p.positioned) p.toJson() else JsonObject(mapOf("mode" to JsonPrimitive(p.mode.wire)))
        return JsonObject(mapOf("placement" to body))
    }

    private fun capsJson(info: EdgeInfo): String =
        EdgeJson.encodeToString(JsonObject.serializer(), JsonObject(info.caps.raw.mapValues { JsonPrimitive(it.value) }))

    private fun tzOffsetMinutes(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    private companion object {
        /** Connecting plus the owner typing the code. */
        const val PAIR_TIMEOUT_MS = 150_000L
    }
}

enum class PhoneLocationAccess { OK, NO_PERMISSION, OFF }
