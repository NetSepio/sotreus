package app.sotreus.feature.edge

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.edge.EdgeLive
import app.sotreus.core.data.edge.EdgeRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.entity.EdgeDeviceEntity
import app.sotreus.core.model.EdgeDataLink
import app.sotreus.core.model.EdgeDeployment
import app.sotreus.core.model.SensingSource
import app.sotreus.core.model.SotreusDefaults
import app.sotreus.edge.location.GeoFix
import app.sotreus.edge.location.ManualPosition
import app.sotreus.edge.location.PlacementAverager
import app.sotreus.edge.location.PlacementSources
import app.sotreus.edge.protocol.EdgeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the pairing flow is (mock 17). */
sealed interface PairStep {
    data object Intro : PairStep
    data object Picking : PairStep

    /** Android 8–11: Edges the app's own scan found so far; [done] once the scan ends. */
    data class Scanning(val found: List<app.sotreus.edge.android.EdgePairing.FoundEdge>, val done: Boolean = false) : PairStep

    /** Android is asking for the code on the Edge's display, then the app reads the Edge. */
    data object Bonding : PairStep
    data class Setup(val paired: EdgeRepository.PairResult.Paired) : PairStep
    data class Failed(val reason: String) : PairStep
}

/** Setting a stationary Edge's position (HANDOFF_V2_EDGE.md §4.3). */
sealed interface PositionStep {
    data object Idle : PositionStep

    /** Averaging this phone's fixes; null until the first fix. */
    data class Averaging(val estimate: PlacementAverager.Estimate?) : PositionStep

    /** Averaging ended with an estimate, inside the limit or not. */
    data class Estimated(val estimate: PlacementAverager.Estimate) : PositionStep
    data object NoPermission : PositionStep
    data object LocationOff : PositionStep
    data object NoFixes : PositionStep
    data object Saving : PositionStep
}

data class PositionUi(
    val step: PositionStep = PositionStep.Idle,
    /** Why the last save failed, shown next to the buttons. */
    val error: String? = null,
    /** The typed coordinates could not be read. */
    val typedInvalid: Boolean = false,
    /** What the Edge reported keeping after the last save. */
    val saved: app.sotreus.edge.protocol.EdgePlacement? = null,
    val limitM: Int = SotreusDefaults.EDGE_STATIONARY_LIMIT_M,
) {
    val estimate: PlacementAverager.Estimate?
        get() = when (step) {
            is PositionStep.Averaging -> step.estimate
            is PositionStep.Estimated -> step.estimate
            else -> null
        }
}

data class EdgeUiState(
    val device: EdgeDeviceEntity? = null,
    val live: EdgeLive = EdgeLive(),
    val lastFix: GeoFix? = null,
    val untimed: Int = 0,
    val source: SensingSource = SensingSource.PHONE,
    val maxAccuracyM: Int = SotreusDefaults.EDGE_MAX_ACCURACY_M,
    val pairing: PairStep = PairStep.Intro,
    val position: PositionUi = PositionUi(),
    val message: String? = null,
    val nowMs: Long = System.currentTimeMillis(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EdgeViewModel @Inject constructor(
    private val repo: EdgeRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val pairing = MutableStateFlow<PairStep>(PairStep.Intro)
    private val position = MutableStateFlow(PositionUi())
    private val message = MutableStateFlow<String?>(null)
    private var averageJob: Job? = null

    private val device = repo.devices.map { it.firstOrNull() }
    private val untimed = device.flatMapLatest { d -> d?.let { repo.untimedCount(it.deviceId) } ?: flowOf(0) }

    val state: StateFlow<EdgeUiState> = combine(
        combine(device, repo.live, repo.lastFix, untimed) { d, live, fix, n -> Quad(d, live, fix, n) },
        settings.settings,
        pairing,
        combine(position, repo.positionSaved) { pos, saved -> pos.copy(saved = saved) },
        message,
    ) { q, s, p, pos, m ->
        EdgeUiState(q.device, q.live, q.fix, q.untimed, s.sensingSource, s.edgeMaxAccuracyM, p, pos.copy(limitM = s.edgeStationaryLimitM), m, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EdgeUiState())

    private data class Quad(val device: EdgeDeviceEntity?, val live: EdgeLive, val fix: GeoFix?, val untimed: Int)

    // ---- Pairing ---------------------------------------------------------------------------

    fun startPairing(activity: android.app.Activity, launch: (IntentSender) -> Unit) {
        pairing.value = PairStep.Picking
        runCatching { repo.associate(activity, onPicker = launch, onError = { pairing.value = PairStep.Failed(it) }) }
            .onFailure { pairing.value = PairStep.Failed(it.message ?: "The pairing picker could not open") }
    }

    val useSystemPicker: Boolean get() = repo.useSystemPicker
    private var scanJob: Job? = null

    /** Android 8–11: scan for up to 30 s, listing Edges as they are found, strongest first. */
    fun startScanning() {
        scanJob?.cancel()
        pairing.value = PairStep.Scanning(emptyList())
        scanJob = viewModelScope.launch {
            val found = LinkedHashMap<String, app.sotreus.edge.android.EdgePairing.FoundEdge>()
            fun list() = found.values.sortedByDescending { it.rssi }
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(SCAN_MS) {
                    repo.scanForEdges().collect { f ->
                        found[f.device.address] = f
                        pairing.value = PairStep.Scanning(list())
                    }
                }
            }.onFailure { pairing.value = PairStep.Failed(it.message ?: "Bluetooth scan failed"); return@launch }
            if (pairing.value is PairStep.Scanning) pairing.value = PairStep.Scanning(list(), done = true)
        }
    }

    fun pick(found: app.sotreus.edge.android.EdgePairing.FoundEdge) {
        scanJob?.cancel()
        pairing.value = PairStep.Bonding
        viewModelScope.launch {
            pairing.value = when (val r = repo.pairDevice(found.device)) {
                is EdgeRepository.PairResult.Paired -> PairStep.Setup(r)
                EdgeRepository.PairResult.Cancelled -> PairStep.Intro
                is EdgeRepository.PairResult.Failed -> PairStep.Failed(r.reason)
            }
        }
    }

    fun onPicked(result: Intent?) {
        if (result == null) {
            pairing.value = PairStep.Intro
            return
        }
        pairing.value = PairStep.Bonding
        viewModelScope.launch {
            pairing.value = when (val r = repo.pair(result)) {
                is EdgeRepository.PairResult.Paired -> PairStep.Setup(r)
                EdgeRepository.PairResult.Cancelled -> PairStep.Intro
                is EdgeRepository.PairResult.Failed -> PairStep.Failed(r.reason)
            }
        }
    }

    fun finishSetup(deployment: EdgeDeployment, tagging: Boolean, importOlder: Boolean = false) {
        val step = pairing.value as? PairStep.Setup ?: return
        viewModelScope.launch {
            repo.finishSetup(step.paired.info.deviceId, deployment, tagging, importOlder, step.paired.oldest)
            pairing.value = PairStep.Intro
        }
    }

    fun resetPairing() {
        scanJob?.cancel()
        viewModelScope.launch { repo.abandonSetup() }
        pairing.value = PairStep.Intro
    }

    // ---- Device ----------------------------------------------------------------------------

    private fun run(block: suspend () -> Result<*>) {
        viewModelScope.launch { block().onFailure { message.value = it.message } }
    }

    fun clearMessage() { message.value = null }
    fun setDeployment(d: EdgeDeployment) = run { repo.setDeployment(d) }
    fun setTagging(on: Boolean) = viewModelScope.launch { repo.setPhoneTagging(on) }
    fun setDataLink(link: EdgeDataLink) = run { repo.setDataLink(link) }
    fun setAwareness(key: String, on: Boolean) = run { repo.setAwareness(mapOf(key to on)) }
    fun setMode(mode: EdgeMode) = run { repo.setMode(mode) }
    fun syncNow() = repo.syncNow()
    fun reopenPairing() = run { repo.reopenPairing() }
    fun restart() = run { repo.restartEdge() }
    fun confirmStillHere() = viewModelScope.launch { state.value.device?.let { repo.confirmStillHere(it.deviceId) } }
    fun markMoved() = viewModelScope.launch { repo.markMoved() }
    fun useEdge() = viewModelScope.launch { repo.useEdge() }
    fun usePhoneRadios() = viewModelScope.launch { repo.usePhoneRadios() }
    fun forget() = viewModelScope.launch { state.value.device?.let { repo.forget(it.deviceId) } }

    /** "Set from this phone" for a stationary Edge: averages fixes for up to 30 s. */
    fun startAveraging() {
        averageJob?.cancel()
        repo.clearPositionSaved()
        val blocked = when (repo.phoneLocationAccess()) {
            app.sotreus.core.data.edge.PhoneLocationAccess.NO_PERMISSION -> PositionStep.NoPermission
            app.sotreus.core.data.edge.PhoneLocationAccess.OFF -> PositionStep.LocationOff
            app.sotreus.core.data.edge.PhoneLocationAccess.OK -> null
        }
        if (blocked != null) {
            position.update { it.copy(step = blocked, error = null, typedInvalid = false) }
            return
        }
        position.update { it.copy(step = PositionStep.Averaging(null), error = null, typedInvalid = false) }
        averageJob = viewModelScope.launch {
            val limit = settings.settings.first().edgeStationaryLimitM.toFloat()
            var last: PlacementAverager.Estimate? = null
            runCatching {
                repo.averagePosition(limit).collect { e ->
                    last = e
                    position.update { it.copy(step = PositionStep.Averaging(e)) }
                }
            }
            position.update { it.copy(step = averagingEnded(last)) }
        }
    }

    /** Saves the estimate: accepted, or [anyway] with the accuracy it really has. */
    fun savePosition(anyway: Boolean = false) {
        val e = position.value.estimate ?: return
        val p = e.toPlacement(System.currentTimeMillis(), anyway) ?: return
        averageJob?.cancel()
        store(p.lat!!, p.lon!!, p.accuracyM!!, PlacementSources.PHONE, after = PositionStep.Estimated(e))
    }

    /** Typed "latitude, longitude"; false (with the reason shown) when it cannot be read. */
    fun setTypedPosition(text: String): Boolean {
        val t = ManualPosition.parseTyped(text)
        if (t == null) {
            position.update { it.copy(typedInvalid = true, error = null) }
            return false
        }
        store(t.lat, t.lon, t.accuracyM, PlacementSources.MAP, after = position.value.step)
        return true
    }

    private fun store(lat: Double, lon: Double, accuracyM: Float, source: String, after: PositionStep) {
        repo.clearPositionSaved()
        position.update { it.copy(step = PositionStep.Saving, error = null, typedInvalid = false) }
        viewModelScope.launch {
            repo.setPosition(lat, lon, accuracyM, source)
                .onSuccess { position.update { it.copy(step = PositionStep.Idle, error = null) } }
                .onFailure { e -> position.update { it.copy(step = after, error = e.message ?: SAVE_FAILED) } }
        }
    }

    companion object {
        private const val SCAN_MS = 30_000L
        const val SAVE_FAILED = "The position could not be saved"

        /** Where averaging leaves the screen: the estimate it reached, inside the limit or not, or no fixes. */
        fun averagingEnded(last: PlacementAverager.Estimate?): PositionStep =
            last?.let { PositionStep.Estimated(it) } ?: PositionStep.NoFixes
    }
}
