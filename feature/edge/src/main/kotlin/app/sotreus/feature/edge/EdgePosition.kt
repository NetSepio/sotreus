package app.sotreus.feature.edge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.edge.EdgeRepository
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.PinMap
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.TextInputDialog
import app.sotreus.edge.location.ManualPosition
import app.sotreus.edge.location.PlacementAverager
import app.sotreus.edge.location.PlacementSources
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import javax.inject.Inject

/** What the stationary position block can ask for. */
internal class PositionActions(
    val setFromPhone: () -> Unit = {},
    val save: (anyway: Boolean) -> Unit = {},
    val pickOnMap: () -> Unit = {},
    val typed: (String) -> Boolean = { true },
    val allowLocation: () -> Unit = {},
)

@Composable
internal fun placeSourceLabel(source: String?): String = when (source) {
    PlacementSources.PHONE -> stringResource(R.string.edge_source_phone)
    PlacementSources.MAP -> stringResource(R.string.edge_source_map)
    null -> ""
    else -> source
}

@Composable
private fun estimateLine(e: PlacementAverager.Estimate) = stringResource(R.string.edge_position_averaging, "%.0f".format(e.accuracyM), e.fixes)

/**
 * A stationary Edge's position (HANDOFF_V2_EDGE.md §4.3): set from this phone (averaged), picked on a
 * map, or typed. An estimate outside the limit stays on screen and can be saved with its real accuracy.
 */
@Composable
internal fun StationaryPosition(pos: PositionUi, actions: PositionActions) {
    val c = SotreusTheme.colors
    var typing by remember { mutableStateOf(false) }
    val est = pos.estimate
    val step = pos.step
    val line: String? = when (step) {
        is PositionStep.Averaging -> step.estimate?.let { estimateLine(it) } ?: stringResource(R.string.edge_position_waiting)
        is PositionStep.Estimated -> estimateLine(step.estimate)
        PositionStep.NoPermission -> stringResource(R.string.edge_position_no_permission)
        PositionStep.LocationOff -> stringResource(R.string.edge_position_location_off)
        PositionStep.NoFixes -> stringResource(R.string.edge_position_no_fixes)
        PositionStep.Saving -> stringResource(R.string.edge_position_saving)
        PositionStep.Idle -> pos.saved?.let { stringResource(R.string.edge_position_saved, "%.0f".format(it.accuracyM ?: 0f), placeSourceLabel(it.source)) }
    }
    line?.let { Text(it, style = SotreusTheme.typography.bodyS, color = c.text) }
    if (step is PositionStep.Estimated && !step.estimate.accepted) {
        Text(stringResource(R.string.edge_position_wide, pos.limitM), style = SotreusTheme.typography.bodyS, color = c.textMuted)
    }
    when {
        step == PositionStep.NoPermission -> PrimaryButton(stringResource(R.string.edge_position_allow), actions.allowLocation)
        est?.accepted == true -> PrimaryButton(stringResource(R.string.edge_position_save, "%.0f".format(est.accuracyM)), { actions.save(false) })
        step is PositionStep.Estimated -> GhostButton(stringResource(R.string.edge_position_save_anyway, "%.0f".format(step.estimate.accuracyM)), { actions.save(true) }, strong = true)
    }
    val retry = step is PositionStep.Estimated || step == PositionStep.NoFixes || step == PositionStep.LocationOff
    GhostButton(
        stringResource(if (retry) R.string.edge_position_try_again else R.string.edge_position_set),
        actions.setFromPhone,
        enabled = step !is PositionStep.Averaging && step != PositionStep.Saving,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
        GhostButton(stringResource(R.string.edge_position_map), actions.pickOnMap, Modifier.weight(1f), enabled = step != PositionStep.Saving)
        GhostButton(stringResource(R.string.edge_position_type), { typing = true }, Modifier.weight(1f), enabled = step != PositionStep.Saving)
    }
    // Failures sit next to the buttons that caused them.
    pos.error?.let { Text(it, style = SotreusTheme.typography.bodyS, color = c.accent) }
    if (pos.typedInvalid) Text(stringResource(R.string.edge_position_typed_invalid), style = SotreusTheme.typography.bodyS, color = c.accent)
    if (typing) {
        TextInputDialog(
            title = stringResource(R.string.edge_position_type),
            initial = "",
            placeholder = stringResource(R.string.edge_position_type_hint),
            confirm = stringResource(R.string.edge_save),
            dismiss = stringResource(R.string.edge_cancel),
            onConfirm = { actions.typed(it); typing = false },
            onDismiss = { typing = false },
        )
    }
}

// ---- Pick on a map ----------------------------------------------------------------------------

data class EdgeMapState(val start: Pair<Double, Double>? = null, val zoom: Double = 16.0, val ready: Boolean = false, val saving: Boolean = false, val error: String? = null)

@HiltViewModel
class EdgeMapPickerViewModel @Inject constructor(
    private val repo: EdgeRepository,
    private val location: app.sotreus.sensing.LocationSource,
) : ViewModel() {
    val state = MutableStateFlow(EdgeMapState())

    init {
        viewModelScope.launch {
            val d = repo.devices.first().firstOrNull()
            val saved = if (d?.placeLat != null && d.placeLon != null) d.placeLat!! to d.placeLon!! else null
            val start = saved ?: location.currentFix(8_000)?.let { it.lat to it.lon }
            state.value = EdgeMapState(start ?: (20.0 to 0.0), if (start != null) 17.0 else 1.5, ready = true)
        }
    }

    suspend fun myLocation(): Pair<Double, Double>? = location.currentFix()?.let { it.lat to it.lon }

    /** Source "map", with the accuracy the zoom supports. */
    fun save(lat: Double, lon: Double, zoom: Double, done: () -> Unit) {
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            repo.clearPositionSaved()
            repo.setPosition(lat, lon, ManualPosition.mapAccuracyM(lat, zoom), PlacementSources.MAP)
                .onSuccess { state.update { it.copy(saving = false) }; done() }
                .onFailure { e -> state.update { it.copy(saving = false, error = e.message ?: EdgeViewModel.SAVE_FAILED) } }
        }
    }
}

/** Map picker for a stationary Edge, composed like the place picker: a map with a fixed pin. */
@Composable
internal fun EdgeMapPickerScreen(onBack: () -> Unit, vm: EdgeMapPickerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = SotreusTheme.colors
    var center by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var zoom by remember { mutableStateOf(state.zoom) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(SotreusTheme.spacing.screenH), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
        BackTopBar(onBack = onBack, title = stringResource(R.string.edge_map_title))
        Text(stringResource(R.string.edge_map_lead), style = SotreusTheme.typography.bodyS, color = c.textMuted)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val start = state.start
            if (state.ready && start != null) {
                PinMap(
                    start.first, start.second, state.zoom, stringResource(R.string.edge_map_a11y), Modifier.fillMaxSize(),
                    onCenterChanged = { lat, lon ->
                        center = lat to lon
                        map?.cameraPosition?.zoom?.let { zoom = it }
                    },
                    onReady = { map = it; center = start; zoom = state.zoom },
                )
            }
        }
        Text(stringResource(R.string.edge_map_attribution), style = SotreusTheme.typography.monoLabelS, color = c.textDim)
        val accuracy = center?.let { ManualPosition.mapAccuracyM(it.first, zoom) }
        center?.let { (lat, lon) -> MonoLabel(stringResource(R.string.edge_map_coords, "%.5f".format(lat), "%.5f".format(lon), "%.0f".format(accuracy))) }
        Text(stringResource(R.string.edge_map_note), style = SotreusTheme.typography.caption, color = c.textDim)
        GhostButton(
            stringResource(R.string.edge_map_center),
            {
                scope.launch {
                    vm.myLocation()?.let { (lat, lon) ->
                        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 17.0))
                        center = lat to lon
                        zoom = 17.0
                    }
                }
            },
            minHeight = SotreusTheme.sizes.minTouch,
            strong = true,
        )
        state.error?.let { Text(it, style = SotreusTheme.typography.bodyS, color = c.accent) }
        PrimaryButton(
            if (accuracy != null) stringResource(R.string.edge_position_save, "%.0f".format(accuracy)) else stringResource(R.string.edge_save),
            { center?.let { (lat, lon) -> vm.save(lat, lon, zoom, onBack) } },
            enabled = center != null && !state.saving,
        )
    }
}

// ---- Previews (FakeSotreusData) ---------------------------------------------------------------

@Composable
private fun PositionPreview(pos: PositionUi) {
    SotreusTheme {
        Column(Modifier.padding(SotreusTheme.spacing.screenH), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            StationaryPosition(pos, PositionActions())
        }
    }
}

@Preview(widthDp = 390, heightDp = 360, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun PositionWidePreview() = PositionPreview(PositionUi(PositionStep.Estimated(FakeSotreusData.edgeWideEstimate)))

@Preview(widthDp = 390, heightDp = 300, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun PositionAcceptedPreview() = PositionPreview(PositionUi(PositionStep.Averaging(FakeSotreusData.edgeGoodEstimate)))

@Preview(widthDp = 390, heightDp = 300, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun PositionNoPermissionPreview() = PositionPreview(PositionUi(PositionStep.NoPermission))

@Preview(widthDp = 390, heightDp = 300, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun PositionSavedPreview() = PositionPreview(PositionUi(saved = FakeSotreusData.edgeStationaryPlacement, error = null))

@Preview(widthDp = 390, heightDp = 300, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun PositionErrorPreview() = PositionPreview(PositionUi(PositionStep.Estimated(FakeSotreusData.edgeWideEstimate), error = "Connect to your Edge to change this"))
