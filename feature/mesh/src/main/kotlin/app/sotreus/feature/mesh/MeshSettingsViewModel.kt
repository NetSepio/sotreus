package app.sotreus.feature.mesh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.mesh.MeshHub
import app.sotreus.core.data.mesh.MeshLive
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.database.dao.MeshDao
import app.sotreus.core.database.entity.MeshNodeEntity
import app.sotreus.edge.protocol.mesh.MeshChannel
import app.sotreus.edge.protocol.mesh.MeshConfigPatch
import app.sotreus.edge.protocol.mesh.MeshPositionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

internal data class MeshSettingsUi(
    val live: MeshLive = MeshLive(),
    val nodes: Map<Long, MeshNodeEntity> = emptyMap(),
    val nowMs: Long = System.currentTimeMillis(),
    val busy: Boolean = false,
    val message: String? = null,
    /** The channel link once read (Channels › Share). */
    val link: String? = null,
    /** A stationary Edge's set position (lat, lon, ± m). */
    val edgePosition: Triple<Double, Double, Float>? = null,
)

/** Mesh settings (M7) and Channels (M6). Every change is written to the Edge's `config.mesh`. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class MeshSettingsViewModel @Inject constructor(
    private val mesh: MeshHub,
    dao: MeshDao,
    private val location: PhoneLocation,
    edge: app.sotreus.core.data.edge.EdgeHub,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val link = MutableStateFlow<String?>(null)

    val state: StateFlow<MeshSettingsUi> = combine(
        mesh.state,
        mesh.state.map { it.deviceId }.distinctUntilChanged().flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeNodes(id) },
        combine(busy, message, link, ::Triple),
        edge.state.map { it.device?.stationaryPosition() }.distinctUntilChanged(),
    ) { live, nodes, (b, m, l), pos -> MeshSettingsUi(live, nodes.associateBy { it.num }, System.currentTimeMillis(), b, m, l, pos) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeshSettingsUi())

    fun hasLocationPermission(): Boolean = location.hasPermission()

    private fun act(block: suspend () -> Result<*>) = viewModelScope.launch {
        busy.value = true
        block().onFailure { message.value = it.message }
        busy.value = false
    }

    fun clearMessage() { message.value = null }

    fun choosePosition(mode: MeshPositionMode, fixed: Pair<Double, Double>? = null) = act { mesh.choosePosition(mode, fixed) }

    /** One top-level `mesh` field: `region`, `preset`, `role`, `hop_limit`, `long_name`… */
    fun set(key: String, value: Any) = act { mesh.setConfig(MeshConfigPatch.fields(key to value)) }

    /** One field of a module object (`mqtt`, `store_forward`, `admin`, `tak`…); the Edge merges it. */
    fun setModule(module: String, key: String, value: Any) = act {
        val v = when (value) { is Boolean -> JsonPrimitive(value); is Number -> JsonPrimitive(value); else -> JsonPrimitive(value.toString()) }
        mesh.setConfig(MeshConfigPatch.fields(module to JsonObject(mapOf(key to v))))
    }

    fun saveChannels(list: List<MeshChannel>) = act { mesh.setConfig(MeshConfigPatch.channels(list)) }

    fun readLink() = act { mesh.channelLink().onSuccess { link.value = it } }

    fun importLink(url: String, replace: Boolean) = act { mesh.importChannels(url.trim(), replace).onSuccess { link.value = it } }

    fun newIdentity() = act { mesh.newIdentity() }

    fun takChat(text: String) = act { mesh.takChat(text) }

    fun takPosition() = act { mesh.takPosition() }

    fun refresh() = act { mesh.refresh(nodes = false); Result.success(Unit) }
}
