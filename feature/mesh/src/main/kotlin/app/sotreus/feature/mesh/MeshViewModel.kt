package app.sotreus.feature.mesh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.edge.EdgeHub
import app.sotreus.core.data.mesh.MeshHub
import app.sotreus.core.data.mesh.MeshLive
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.MeshConversationRow
import app.sotreus.core.database.dao.MeshDao
import app.sotreus.core.database.entity.MeshNodeEntity
import app.sotreus.core.database.entity.MeshWaypointEntity
import app.sotreus.core.model.MapBackground
import app.sotreus.edge.link.EdgeLinkState
import app.sotreus.edge.protocol.mesh.MeshPositionMode
import app.sotreus.sensing.LocationSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal enum class MeshTab { MESSAGES, NODES, MAP }

internal enum class NodeFilter { ALL, FAVOURITES, RECENT, TAK }

internal data class MeshData(
    val conversations: List<MeshConversationRow> = emptyList(),
    val nodes: List<MeshNodeEntity> = emptyList(),
    val waypoints: List<MeshWaypointEntity> = emptyList(),
)

/** Everything the Mesh tab (M1, M3, M5, M8) shows. */
internal data class MeshUi(
    val live: MeshLive = MeshLive(),
    val link: EdgeLinkState = EdgeLinkState.Idle,
    val data: MeshData = MeshData(),
    val phone: LocationSource.Fix? = null,
    val mapBackground: MapBackground = MapBackground.NONE,
    val nowMs: Long = System.currentTimeMillis(),
    val message: String? = null,
    /** A stationary Edge's set position (lat, lon, ± m): the natural mesh position for it. */
    val edgePosition: Triple<Double, Double, Float>? = null,
) {
    val nodesByNum: Map<Long, MeshNodeEntity> get() = data.nodes.associateBy { it.num }

    /** The position disclosure (M8) shows once, the first time this Edge is seen in Mesh mode. */
    val askPosition: Boolean get() = live.meshMode && live.connected && live.positionChoice == null && live.config != null
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class MeshViewModel @Inject constructor(
    private val mesh: MeshHub,
    private val dao: MeshDao,
    private val edge: EdgeHub,
    location: PhoneLocation,
    settings: SettingsRepository,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private val tick = flow { while (true) { emit(System.currentTimeMillis()); delay(TICK_MS) } }

    private val data = mesh.state.map { it.deviceId }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(MeshData()) else combine(
            dao.observeConversations(id), dao.observeNodes(id), dao.observeWaypoints(id, System.currentTimeMillis()),
        ) { c, n, w -> MeshData(c, n, w) }
    }

    val state: StateFlow<MeshUi> = combine(
        combine(mesh.state, edge.state.map { it.link to it.device?.stationaryPosition() }.distinctUntilChanged(), ::Pair),
        data, location.latest, settings.settings.map { it.mapBackground }.distinctUntilChanged(),
        combine(tick, message, ::Pair),
    ) { (live, edgeState), d, phone, bg, (now, msg) ->
        MeshUi(live, edgeState.first, d, phone, bg, now, msg, edgeState.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeshUi())

    fun refresh() = viewModelScope.launch { mesh.refresh() }

    fun clearMessage() { message.value = null }

    fun dismissRegionNote() = mesh.dismissRegionNote()

    fun missedMessages(router: Long?) = viewModelScope.launch {
        mesh.missedMessages(router).onFailure { message.value = it.message }
    }

    fun choosePosition(mode: MeshPositionMode, fixed: Pair<Double, Double>? = null) = viewModelScope.launch {
        mesh.choosePosition(mode, fixed).onFailure { message.value = it.message }
    }

    fun addWaypoint(lat: Double, lon: Double, name: String) = viewModelScope.launch {
        mesh.addWaypoint(lat, lon, name, null, null).onFailure { message.value = it.message }
    }

    private companion object {
        const val TICK_MS = 15_000L
    }
}

/** The set position of a stationary Edge, if any. */
internal fun app.sotreus.core.database.entity.EdgeDeviceEntity.stationaryPosition(): Triple<Double, Double, Float>? {
    if (deployment != app.sotreus.core.model.EdgeDeployment.STATIONARY) return null
    val la = placeLat ?: return null
    val lo = placeLon ?: return null
    return Triple(la, lo, placeAccuracyM ?: 0f)
}
