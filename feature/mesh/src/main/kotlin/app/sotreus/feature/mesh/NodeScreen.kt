package app.sotreus.feature.mesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.mesh.MeshHub
import app.sotreus.core.data.mesh.MeshLive
import app.sotreus.core.data.mesh.MeshRows
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.MeshDao
import app.sotreus.core.database.entity.MeshNodeEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.MapBackground
import app.sotreus.core.navigation.MeshNodeRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.KeyValueTable
import app.sotreus.core.ui.MeshMap
import app.sotreus.core.ui.MeshMapPin
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.SotreusCard
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.SwitchRow
import app.sotreus.core.ui.WarningBanner
import app.sotreus.edge.protocol.mesh.MeshNodeId
import app.sotreus.edge.protocol.mesh.MeshPrecision
import app.sotreus.edge.protocol.mesh.MeshTraceroute
import app.sotreus.sensing.LocationSource
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
import javax.inject.Inject

internal data class NodeUi(
    val num: Long = 0,
    val node: MeshNodeEntity? = null,
    val nodes: Map<Long, MeshNodeEntity> = emptyMap(),
    val live: MeshLive = MeshLive(),
    val phone: LocationSource.Fix? = null,
    val mapBackground: MapBackground = MapBackground.NONE,
    val nowMs: Long = System.currentTimeMillis(),
    val message: String? = null,
    /** The Edge can drop a node from its table (`caps.mesh_node_remove`). */
    val canRemove: Boolean = false,
) {
    val route: MeshTraceroute? get() = live.routes[num]
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class NodeViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val mesh: MeshHub,
    dao: MeshDao,
    location: PhoneLocation,
    settings: SettingsRepository,
    edge: app.sotreus.core.data.edge.EdgeHub,
) : ViewModel() {
    val num: Long = handle.toRoute<MeshNodeRoute>().num
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<NodeUi> = combine(
        mesh.state,
        mesh.state.map { it.deviceId }.distinctUntilChanged().flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeNodes(id) },
        location.latest,
        settings.settings.map { it.mapBackground }.distinctUntilChanged(),
        combine(message, edge.state.map { it.info?.caps?.meshNodeRemove == true }.distinctUntilChanged(), ::Pair),
    ) { live, nodes, phone, bg, (msg, canRemove) ->
        val byNum = nodes.associateBy { it.num }
        NodeUi(num, byNum[num], byNum, live, phone, bg, System.currentTimeMillis(), msg, canRemove)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodeUi(num))

    private fun act(block: suspend () -> Result<*>) = viewModelScope.launch { block().onFailure { message.value = it.message } }

    fun traceroute() = act { mesh.traceroute(num) }
    fun requestPosition() = act { mesh.requestPosition(num) }
    fun setFavorite(on: Boolean) = act { mesh.setFavorite(num, on) }
    fun setIgnored(on: Boolean) = act { mesh.setIgnored(num, on) }
    fun clearMessage() { message.value = null }

    fun remove(done: () -> Unit) = viewModelScope.launch {
        mesh.removeNode(num).onSuccess { done() }.onFailure { message.value = it.message }
    }
}

@Composable
internal fun NodeScreen(onBack: () -> Unit, onMessage: (String) -> Unit, vm: NodeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    NodeContent(state, onBack, onMessage, vm::traceroute, vm::requestPosition, vm::setFavorite, vm::setIgnored, vm::clearMessage, onRemove = { vm.remove(onBack) })
}

/** A node (mock M4): reported position with distance, link, traceroute, telemetry, neighbours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NodeContent(
    state: NodeUi,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    onTraceroute: () -> Unit = {},
    onAskPosition: () -> Unit = {},
    onFavorite: (Boolean) -> Unit = {},
    onIgnore: (Boolean) -> Unit = {},
    onClearMessage: () -> Unit = {},
    onRemove: () -> Unit = {},
) {
    val c = SotreusTheme.colors
    val n = state.node ?: MeshNodeEntity(deviceId = "", num = state.num)
    val canSend = state.live.canSend
    var confirmRemove by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack, title = stringResource(R.string.mesh_tab_nodes))
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Well(shortTag(n, n.num), large = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(n.title(), style = SotreusTheme.typography.displayM, color = c.text)
                Text(
                    listOf(MeshNodeId.format(n.num), roleLabel(n.role), stringResource(R.string.mesh_heard_ago, ago(state.nowMs, n.lastHeardMs))).joinToString(" · "),
                    style = SotreusTheme.typography.caption, color = c.textMuted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (n.favorite) StateChip(stringResource(R.string.mesh_chip_favourite), ChipTone.ACCENT)
                    if (n.ignored) StateChip(stringResource(R.string.mesh_chip_ignored), ChipTone.NEUTRAL)
                    StateChip(stringResource(R.string.mesh_network), ChipTone.NETWORK)
                }
            }
        }
        state.message?.let { WarningBanner(it); GhostButton(stringResource(R.string.mesh_ok), onClearMessage) }
        Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            GhostButton(stringResource(R.string.mesh_action_message), { onMessage("dm:${n.num}") }, Modifier.weight(1f))
            GhostButton(stringResource(R.string.mesh_action_traceroute), onTraceroute, Modifier.weight(1f), enabled = canSend && n.num !in state.live.routing)
            GhostButton(stringResource(R.string.mesh_action_position), onAskPosition, Modifier.weight(1f), enabled = canSend)
        }
        PositionCard(n, state)
        MonoLabel(stringResource(R.string.mesh_link), small = true)
        KeyValueTable(
            listOfNotNull(
                hopsLabel(n.hopsAway)?.let { stringResource(R.string.mesh_hops_away) to it },
                if (n.snr != null || n.rssi != null) stringResource(R.string.mesh_last_packet) to listOfNotNull(
                    snr(n.snr)?.let { stringResource(R.string.mesh_snr, it) }, n.rssi?.let { stringResource(R.string.mesh_rssi, it.toString().replace("-", "−")) },
                ).joinToString(" · ") else null,
                stringResource(R.string.mesh_key) to stringResource(if (n.hasKey) R.string.mesh_key_known else R.string.mesh_key_unknown),
                n.hwModel?.let { stringResource(R.string.mesh_hardware) to stringResource(R.string.mesh_hardware_model, it) },
            ),
        )
        RouteCard(state)
        if (n.battery != null || n.channelUtil != null) {
            Header(stringResource(R.string.mesh_device), n.telemetryMs, state.nowMs)
            KeyValueTable(
                listOfNotNull(
                    n.battery?.let { b -> stringResource(R.string.mesh_battery_label) to if (b > 100) stringResource(R.string.mesh_on_power) else listOfNotNull("$b%", n.voltage?.let { "%.2f V".format(java.util.Locale.ROOT, it) }).joinToString(" · ") },
                    n.channelUtil?.let { stringResource(R.string.mesh_channel_use) to "%.1f%%".format(java.util.Locale.ROOT, it) },
                    n.airUtil?.let { stringResource(R.string.mesh_own_airtime) to "%.1f%%".format(java.util.Locale.ROOT, it) },
                    n.uptimeS?.let { stringResource(R.string.mesh_up_for) to app.sotreus.core.ui.durationLabel(it * 1000) },
                ),
            )
        }
        if (n.temperatureC != null || n.humidityPct != null || n.pressureHpa != null) {
            Header(stringResource(R.string.mesh_environment), n.telemetryMs, state.nowMs)
            KeyValueTable(
                listOfNotNull(
                    n.temperatureC?.let { stringResource(R.string.mesh_temperature) to "%.1f °C".format(java.util.Locale.ROOT, it) },
                    n.humidityPct?.let { stringResource(R.string.mesh_humidity) to "%.0f%%".format(java.util.Locale.ROOT, it) },
                    n.pressureHpa?.let { stringResource(R.string.mesh_pressure) to "%.0f hPa".format(java.util.Locale.ROOT, it) },
                ),
            )
        }
        val neighbours = MeshRows.neighbors(n.neighbors)
        if (neighbours.isNotEmpty()) {
            MonoLabel(stringResource(R.string.mesh_neighbours, neighbours.size), small = true)
            KeyValueTable(neighbours.map { (num, s) ->
                val nb = state.nodes[num]
                "${shortTag(nb, num)} · ${nb?.title() ?: MeshNodeId.format(num)}" to (snr(s)?.let { stringResource(R.string.mesh_snr, it) } ?: "—")
            })
        }
        SwitchRow(stringResource(R.string.mesh_favourite), n.favorite, onFavorite, enabled = canSend)
        SwitchRow(stringResource(R.string.mesh_ignore), n.ignored, onIgnore, subtitle = stringResource(R.string.mesh_ignore_note), enabled = canSend)
        // Only when the Edge can do it, the mesh runs, and this is another node.
        if (state.canRemove && canSend && n.num != state.live.selfNum) {
            app.sotreus.core.ui.DestructiveTextButton(stringResource(R.string.mesh_remove), { confirmRemove = true })
        }
    }
    if (confirmRemove) {
        app.sotreus.core.ui.ConfirmDialog(
            stringResource(R.string.mesh_remove_title), stringResource(R.string.mesh_remove_body),
            stringResource(R.string.mesh_remove_confirm), stringResource(R.string.mesh_cancel),
            onConfirm = { confirmRemove = false; onRemove() }, onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun Header(label: String, atMs: Long?, nowMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MonoLabel(label, Modifier.weight(1f), small = true)
        atMs?.let { Text(stringResource(R.string.mesh_ago, ago(nowMs, it)), style = SotreusTheme.typography.monoLabelS, color = SotreusTheme.colors.textDim) }
    }
}

@Composable
private fun PositionCard(n: MeshNodeEntity, state: NodeUi) {
    val c = SotreusTheme.colors
    val lat = n.lat
    val lon = n.lon
    SotreusCard {
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(stringResource(R.string.mesh_reported_position), Modifier.weight(1f), small = true)
                StateChip(stringResource(R.string.mesh_network), ChipTone.NETWORK)
            }
            if (lat == null || lon == null) {
                Text(stringResource(R.string.mesh_no_position_yet), style = SotreusTheme.typography.bodyS, color = c.textMuted)
                return@Column
            }
            val stale = isStale(n, state.nowMs)
            MeshMap(
                phoneLat = state.phone?.lat, phoneLon = state.phone?.lon,
                pins = listOf(MeshMapPin(n.num, lat, lon, MeshPrecision.metres(n.precisionBits ?: 32), stale, selected = true)),
                waypoints = emptyList(), background = state.mapBackground,
                contentDescription = stringResource(R.string.mesh_position_a11y, n.title()),
                onPinTap = {},
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
            val b = between(state.phone, n, state.nowMs)
            when {
                b != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(distanceLabel(b), style = SotreusTheme.typography.titleS, color = c.text, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.mesh_from_phone_short), style = SotreusTheme.typography.monoLabelS, color = c.textMuted)
                }
                stale -> StateChip(stringResource(R.string.mesh_chip_stale), ChipTone.DASHED)
            }
            Text(stringResource(R.string.mesh_reported_by, ago(state.nowMs, n.positionMs), precisionLabel(n.precisionBits)), style = SotreusTheme.typography.caption, color = c.textDim)
            Text(
                listOfNotNull("%.4f, %.4f".format(java.util.Locale.ROOT, lat, lon), n.altM?.let { stringResource(R.string.mesh_altitude, it) }).joinToString(" · "),
                style = SotreusTheme.typography.monoValue, color = c.textSoft,
            )
        }
    }
}

@Composable
private fun RouteCard(state: NodeUi) {
    val c = SotreusTheme.colors
    val waiting = state.num in state.live.routing
    val r = state.route
    if (r == null && !waiting) return
    SotreusCard {
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(stringResource(R.string.mesh_traceroute, state.live.routeAtMs[state.num]?.let { app.sotreus.core.ui.clockTime(it) } ?: "…"), Modifier.weight(1f), small = true)
                Text(stringResource(R.string.mesh_db_per_hop), style = SotreusTheme.typography.monoLabelS, color = c.textDim)
            }
            if (r == null) {
                Text(stringResource(R.string.mesh_route_waiting), style = SotreusTheme.typography.bodyS, color = c.textMuted)
                return@Column
            }
            val self = state.live.selfNum ?: 0
            Hops(listOf<Long?>(self) + r.route + r.to, r.snrTowards, state)
            if (r.routeBack.isNotEmpty() || r.snrBack.isNotEmpty()) {
                Text(stringResource(R.string.mesh_route_back), style = SotreusTheme.typography.monoLabelS, color = c.textDim)
                Hops(listOf<Long?>(r.to) + r.routeBack + self, r.snrBack, state)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hops(nodes: List<Long?>, snrs: List<Double?>, state: NodeUi) {
    val c = SotreusTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        nodes.forEachIndexed { i, num ->
            val label = when {
                num == null -> "?"
                num == state.live.selfNum -> state.live.config?.shortName?.ifBlank { null } ?: shortTag(null, num)
                else -> shortTag(state.nodes[num], num)
            }
            StateChip(label, ChipTone.TEXT, small = false)
            if (i < nodes.lastIndex) {
                Text(
                    listOfNotNull(snrs.getOrNull(i)?.let { "%.1f".format(java.util.Locale.ROOT, it).replace("-", "−") }, "→").joinToString(" "),
                    style = SotreusTheme.typography.monoLabelS, color = c.textDim, modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

// ---- Previews (FakeSotreusData) -----------------------------------------------------------------

@Preview(widthDp = 390, heightDp = 1640, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun NodePreview() {
    val nodes = FakeSotreusData.meshNodes.associateBy { it.num }
    SotreusTheme {
        NodeContent(
            NodeUi(
                FakeSotreusData.MESH_BASE, nodes[FakeSotreusData.MESH_BASE], nodes,
                FakeSotreusData.meshLive.copy(routes = mapOf(FakeSotreusData.MESH_BASE to FakeSotreusData.meshRoute), routeAtMs = mapOf(FakeSotreusData.MESH_BASE to FakeSotreusData.NOW - 120_000)),
                LocationSource.Fix(FakeSotreusData.NOW, FakeSotreusData.PHONE_LAT, FakeSotreusData.PHONE_LON, 8f), nowMs = FakeSotreusData.NOW,
                canRemove = true,
            ),
            {}, {},
        )
    }
}

/** A node heard without a position, out of reach: what is stored, no actions. */
@Preview(widthDp = 390, heightDp = 900, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun NodeNoPositionPreview() {
    val nodes = FakeSotreusData.meshNodes.associateBy { it.num }
    SotreusTheme {
        NodeContent(NodeUi(FakeSotreusData.MESH_KITE, nodes[FakeSotreusData.MESH_KITE], nodes, FakeSotreusData.meshLive.copy(connected = false), nowMs = FakeSotreusData.NOW), {}, {})
    }
}
