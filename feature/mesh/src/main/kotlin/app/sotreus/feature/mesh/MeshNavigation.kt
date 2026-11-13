package app.sotreus.feature.mesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sotreus.core.data.mesh.MeshHub
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.navigation.MeshChannelsRoute
import app.sotreus.core.navigation.MeshConversationRoute
import app.sotreus.core.navigation.MeshNodeRoute
import app.sotreus.core.navigation.MeshPositionMapRoute
import app.sotreus.core.navigation.MeshRoute
import app.sotreus.core.navigation.MeshSettingsRoute
import app.sotreus.core.navigation.SotreusNavigator
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.PinMap
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.WarningBanner
import app.sotreus.edge.protocol.mesh.MeshPositionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

fun NavGraphBuilder.meshGraph(nav: SotreusNavigator) {
    composable<MeshRoute> {
        MeshScreen(
            onConversation = { nav.navigate(MeshConversationRoute(it)) },
            onNode = { nav.navigate(MeshNodeRoute(it)) },
            onSettings = { nav.navigate(MeshSettingsRoute) },
            onPickPosition = { nav.navigate(MeshPositionMapRoute) },
        )
    }
    composable<MeshConversationRoute> { ConversationScreen(onBack = nav::back, onNode = { nav.navigate(MeshNodeRoute(it)) }) }
    composable<MeshNodeRoute> { NodeScreen(onBack = nav::back, onMessage = { nav.navigate(MeshConversationRoute(it)) }) }
    composable<MeshSettingsRoute> {
        MeshSettingsScreen(onBack = nav::back, onChannels = { nav.navigate(MeshChannelsRoute) }, onPickPosition = { nav.navigate(MeshPositionMapRoute) })
    }
    composable<MeshChannelsRoute> { ChannelsScreen(onBack = nav::back) }
    composable<MeshPositionMapRoute> { MeshPositionMapScreen(onBack = nav::back) }
}

@HiltViewModel
internal class MeshPositionMapViewModel @Inject constructor(
    private val mesh: MeshHub,
    private val location: PhoneLocation,
    private val settings: app.sotreus.core.data.settings.SettingsRepository,
) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** Field tools › Map background: with None the picker loads nothing unless asked. */
    val background = MutableStateFlow<app.sotreus.core.model.MapBackground?>(null)

    init {
        viewModelScope.launch { background.value = settings.current().mapBackground }
    }

    /** This phone's latest fix as the fixed point; false when there is none. */
    fun saveHere(done: () -> Unit): Boolean {
        val fix = location.latest.value?.takeIf { !it.mock } ?: return false
        save(fix.lat, fix.lon, done)
        return true
    }

    /** "12.97160, 77.59460": false when it is not a latitude and a longitude. */
    fun saveTyped(text: String, done: () -> Unit): Boolean {
        val t = app.sotreus.edge.location.ManualPosition.parseTyped(text) ?: return false
        save(t.lat, t.lon, done)
        return true
    }

    /** Where the map opens: the current fixed point, this phone, or the Edge's last placement. */
    fun start(): Pair<Double, Double> {
        val pos = mesh.state.value.config?.position
        if (pos?.lat != null && pos.lon != null) return pos.lat!! to pos.lon!!
        location.latest.value?.let { return it.lat to it.lon }
        return DEFAULT_START
    }

    fun save(lat: Double, lon: Double, done: () -> Unit) = viewModelScope.launch {
        mesh.choosePosition(MeshPositionMode.FIXED, lat to lon).onSuccess { done() }.onFailure { _error.value = it.message }
    }

    private companion object {
        val DEFAULT_START = 20.0 to 78.0
    }
}

/** Picks the point the Edge sends as its mesh position (`fixed`), with a pin fixed at the centre. */
@Composable
internal fun MeshPositionMapScreen(onBack: () -> Unit, vm: MeshPositionMapViewModel = hiltViewModel()) {
    val c = SotreusTheme.colors
    val start = remember { vm.start() }
    var center by remember { mutableStateOf(start) }
    val error by vm.error.collectAsStateWithLifecycle()
    val setting by vm.background.collectAsStateWithLifecycle()
    var online by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    val bg = if (online) app.sotreus.core.model.MapBackground.ONLINE else setting ?: return
    Column(Modifier.fillMaxSize().padding(SotreusTheme.spacing.screenH), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
        BackTopBar(onBack = onBack, title = stringResource(R.string.mesh_map_pick_title))
        if (bg == app.sotreus.core.model.MapBackground.NONE) {
            // Street maps are off: set the point without a map, or load it for this screen.
            app.sotreus.core.ui.MapOffPanel({ online = true })
            app.sotreus.core.ui.GhostButton(stringResource(R.string.mesh_map_pick_here), { if (!vm.saveHere(onBack)) problem = R.string.mesh_map_pick_no_fix })
            app.sotreus.core.ui.GhostButton(stringResource(R.string.mesh_map_pick_type), { typing = true })
        } else {
            Text(stringResource(R.string.mesh_map_pick_lead), style = SotreusTheme.typography.bodyS, color = c.textMuted)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PinMap(start.first, start.second, 14.0, stringResource(R.string.mesh_map_pick_a11y), Modifier.fillMaxSize(), onCenterChanged = { lat, lon -> center = lat to lon }, background = bg)
            }
            Text(stringResource(R.string.mesh_osm), style = SotreusTheme.typography.monoLabelS, color = c.textDim)
            if (bg == app.sotreus.core.model.MapBackground.OFFLINE) app.sotreus.core.ui.MapOfflineAreaNote()
            MonoLabel("%.5f, %.5f".format(java.util.Locale.ROOT, center.first, center.second))
            PrimaryButton(stringResource(R.string.mesh_map_pick_save), { vm.save(center.first, center.second, onBack) })
        }
        problem?.let { WarningBanner(stringResource(it)) }
        error?.let { WarningBanner(it) }
    }
    if (typing) app.sotreus.core.ui.TextInputDialog(
        stringResource(R.string.mesh_map_pick_type), "", "12.97160, 77.59460", stringResource(R.string.mesh_map_pick_save), stringResource(R.string.mesh_cancel),
        onConfirm = { t -> if (!vm.saveTyped(t, onBack)) problem = R.string.mesh_map_pick_type_invalid; typing = false },
        onDismiss = { typing = false },
    )
}
