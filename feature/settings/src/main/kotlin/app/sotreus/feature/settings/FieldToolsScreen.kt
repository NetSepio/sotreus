package app.sotreus.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.field.CameraList
import app.sotreus.core.data.field.CameraListFailure
import app.sotreus.core.data.field.CameraListState
import app.sotreus.core.data.field.FieldInsights
import app.sotreus.core.data.field.KnownCamerasRepository
import app.sotreus.core.data.field.GnssHealthRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.ui.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FieldToolsViewModel @Inject constructor(
    private val preferences: SettingsRepository,
    val health: GnssHealthRepository,
    private val insights: FieldInsights,
    private val cameras: KnownCamerasRepository,
) : ViewModel() {
    val cameraLists = cameras.state
    val settings = preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), app.sotreus.core.model.SotreusSettings())
    fun stops(on: Boolean) = viewModelScope.launch { preferences.setTrackerStopHistory(on) }
    fun monitor(on: Boolean) = viewModelScope.launch { preferences.setGnssMonitoring(on) }
    fun spoof(on: Boolean) = viewModelScope.launch { preferences.setGnssSpoofChecks(on) }
    fun sensitivity(level: String) = viewModelScope.launch { preferences.setGnssSensitivity(level) }
    fun path(on: Boolean) = viewModelScope.launch { preferences.setLivePath(on) }
    fun maps(on: Boolean) = viewModelScope.launch { preferences.setOnlineRouteMaps(on) }
    fun cameraLayer(list: CameraList, on: Boolean) = viewModelScope.launch {
        if (list == CameraList.PLATES) preferences.setCameraPlates(on) else preferences.setCameraOthers(on)
    }
    fun download(list: CameraList) = cameras.download(list)
    fun cancel(list: CameraList) = cameras.cancel(list)
    fun delete(list: CameraList) = viewModelScope.launch { cameras.delete(list) }
    fun clearStops() = viewModelScope.launch { insights.clearStops() }
    fun clearProfile() = viewModelScope.launch { preferences.setGnssMonitoring(false); health.clearProfile() }
}

@Composable
internal fun FieldToolsScreen(onBack: () -> Unit, onMap: () -> Unit, vm: FieldToolsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val health by vm.health.state.collectAsStateWithLifecycle()
    val lists by vm.cameraLists.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(false) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.field_tools_title), lead = stringResource(R.string.field_tools_lead))
        SwitchRow(stringResource(R.string.field_stops), settings.trackerStopHistory, vm::stops, subtitle = stringResource(R.string.field_stops_sub))
        GhostButton(stringResource(R.string.field_clear_stops), { clearing = true })
        SwitchRow(stringResource(R.string.field_live_path), settings.livePath, vm::path, subtitle = stringResource(R.string.field_live_path_sub))
        GhostButton(stringResource(R.string.field_open_path), onMap)
        SwitchRow(stringResource(R.string.field_online_maps), settings.onlineRouteMaps, vm::maps, subtitle = stringResource(R.string.field_online_maps_sub))
        MonoLabel(stringResource(R.string.field_cameras_title))
        Text(stringResource(R.string.field_cameras_sub), style = SotreusTheme.typography.bodyS, color = SotreusTheme.colors.textMuted)
        CameraList.entries.forEach { list ->
            val on = if (list == CameraList.PLATES) settings.cameraPlates else settings.cameraOthers
            CameraListRows(list, lists.getValue(list), on, { vm.cameraLayer(list, it) }, { vm.download(list) }, { vm.cancel(list) }, { vm.delete(list) })
        }
        Text(stringResource(R.string.field_cameras_credit), style = SotreusTheme.typography.caption, color = SotreusTheme.colors.textDim)
        MonoLabel(stringResource(R.string.field_gnss_title))
        Text(stringResource(R.string.field_gnss_sub), style = SotreusTheme.typography.bodyS, color = SotreusTheme.colors.textMuted)
        SwitchRow(stringResource(R.string.field_gnss_enable), settings.gnssMonitoring, vm::monitor, enabled = health.profile != null,
            subtitle = health.profile?.summary() ?: stringResource(R.string.field_gnss_calibrate_first))
        SwitchRow(stringResource(R.string.field_spoof), settings.gnssSpoofChecks, vm::spoof,
            subtitle = stringResource(R.string.field_spoof_sub))
        RadioGroupRows(listOf(RadioOption("LOW", stringResource(R.string.field_low)), RadioOption("MEDIUM", stringResource(R.string.field_medium)), RadioOption("HIGH", stringResource(R.string.field_high))), settings.gnssSensitivity, vm::sensitivity)
        KeyValueTable(listOf(stringResource(R.string.field_state) to health.status) + health.bands.mapIndexed { i, line -> "${i + 1}" to line })
        if (health.calibration.running) {
            MonoLabel(stringResource(R.string.field_calibrating, health.calibration.leftSec))
            GhostButton(stringResource(R.string.field_cancel_calibration), { vm.health.stopCalibration() })
        } else GhostButton(stringResource(R.string.field_calibrate), { vm.health.calibrate() })
        if (health.calibration.rejection.isNotBlank()) WarningBanner(health.calibration.rejection)
        if (health.profile != null) GhostButton(stringResource(R.string.field_clear_calibration), { profile = true })
    }
    if (clearing) ConfirmDialog(stringResource(R.string.field_clear_stops), stringResource(R.string.field_clear_stops_body),
        stringResource(app.sotreus.core.ui.R.string.core_ui_delete), stringResource(app.sotreus.core.ui.R.string.core_ui_cancel),
        { clearing = false; vm.clearStops() }, { clearing = false })
    if (profile) ConfirmDialog(stringResource(R.string.field_clear_calibration), stringResource(R.string.field_clear_calibration_body),
        stringResource(app.sotreus.core.ui.R.string.core_ui_delete), stringResource(app.sotreus.core.ui.R.string.core_ui_cancel),
        { profile = false; vm.clearProfile() }, { profile = false })
}

/** One camera list: the map switch, what is saved, and download / update / delete. */
@Composable
private fun CameraListRows(
    list: CameraList,
    state: CameraListState,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val percent = state.percent
    val failure = state.failure
    val status = when {
        percent == 100 -> stringResource(R.string.field_cameras_reading)
        percent != null -> stringResource(R.string.field_cameras_downloading, percent)
        failure != null && state.count != null -> stringResource(R.string.field_cameras_failed_kept, stringResource(failureText(failure)))
        failure != null -> stringResource(failureText(failure))
        state.count != null -> stringResource(R.string.field_cameras_saved, "%,d".format(state.count), state.savedAtMs?.let { dayLabel(it) }.orEmpty())
        else -> stringResource(R.string.field_cameras_missing, stringResource(if (list == CameraList.PLATES) R.string.field_cameras_size_plates else R.string.field_cameras_size_others))
    }
    SwitchRow(
        stringResource(if (list == CameraList.PLATES) R.string.field_cameras_plates else R.string.field_cameras_others),
        on, onToggle, subtitle = status,
    )
    when {
        state.percent != null -> GhostButton(stringResource(app.sotreus.core.ui.R.string.core_ui_cancel), onCancel)
        state.count == null -> GhostButton(stringResource(R.string.field_cameras_download), onDownload)
        else -> {
            GhostButton(stringResource(R.string.field_cameras_update), onDownload)
            DestructiveTextButton(stringResource(R.string.field_cameras_delete), onDelete, androidx.compose.ui.Modifier)
        }
    }
}

private fun failureText(f: CameraListFailure) = when (f) {
    CameraListFailure.REACH -> R.string.field_cameras_fail_reach
    CameraListFailure.TRUST -> R.string.field_cameras_fail_trust
    CameraListFailure.SERVER -> R.string.field_cameras_fail_server
    CameraListFailure.SPACE -> R.string.field_cameras_fail_space
    CameraListFailure.LARGE -> R.string.field_cameras_fail_large
    CameraListFailure.READ -> R.string.field_cameras_fail_read
    CameraListFailure.SAVE -> R.string.field_cameras_fail_save
    CameraListFailure.OTHER -> R.string.field_cameras_fail_other
}
