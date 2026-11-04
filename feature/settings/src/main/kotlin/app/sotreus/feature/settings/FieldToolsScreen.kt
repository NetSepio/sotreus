package app.sotreus.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.sotreus.core.data.field.OfflineAreaSize
import app.sotreus.core.data.field.OfflineMapFailure
import app.sotreus.core.data.field.OfflineMapRepository
import app.sotreus.core.data.field.OfflineMapState
import app.sotreus.core.data.field.OfflineMaps
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.model.MapBackground
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
    private val offline: OfflineMapRepository,
    private val location: PhoneLocation,
) : ViewModel() {
    init { offline.refresh() }
    val cameraLists = cameras.state
    val offlineArea = offline.state
    /** Tile count for each size near this phone; an estimate, shown before anything downloads. */
    fun estimate(size: OfflineAreaSize): Long = OfflineMaps.tiles(location.latest.value?.lat ?: 0.0, location.latest.value?.lon ?: 0.0, size.km)
    fun downloadArea(size: OfflineAreaSize) = offline.download(size)
    fun cancelArea() = offline.cancel()
    fun deleteArea() = offline.delete()
    val settings = preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), app.sotreus.core.model.SotreusSettings())
    fun stops(on: Boolean) = viewModelScope.launch { preferences.setTrackerStopHistory(on) }
    fun monitor(on: Boolean) = viewModelScope.launch { preferences.setGnssMonitoring(on) }
    fun spoof(on: Boolean) = viewModelScope.launch { preferences.setGnssSpoofChecks(on) }
    fun sensitivity(level: String) = viewModelScope.launch { preferences.setGnssSensitivity(level) }
    fun path(on: Boolean) = viewModelScope.launch { preferences.setLivePath(on) }
    fun maps(v: MapBackground) = viewModelScope.launch { preferences.setMapBackground(v) }
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
        MapBackgroundRows(settings.mapBackground, vm::maps)
        val area by vm.offlineArea.collectAsStateWithLifecycle()
        OfflineAreaRows(area, vm::estimate, vm::downloadArea, vm::cancelArea, vm::deleteArea)
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

/** None, a downloaded area only, or online tiles; the line under the choices says what the chosen one sends. */
@Composable
private fun MapBackgroundRows(selected: MapBackground, onSelect: (MapBackground) -> Unit) {
    MonoLabel(stringResource(R.string.field_map_title))
    RadioGroupRows(listOf(
        RadioOption(MapBackground.NONE, stringResource(R.string.field_map_none)),
        RadioOption(MapBackground.OFFLINE, stringResource(R.string.field_map_offline)),
        RadioOption(MapBackground.ONLINE, stringResource(R.string.field_online_maps)),
    ), selected, onSelect)
    Text(stringResource(when (selected) {
        MapBackground.NONE -> R.string.field_map_none_sub
        MapBackground.OFFLINE -> R.string.field_map_offline_sub
        MapBackground.ONLINE -> R.string.field_online_maps_sub
    }), style = SotreusTheme.typography.bodyS, color = SotreusTheme.colors.textMuted)
}

/** The one downloaded street area: its size, what is saved, and download / cancel / delete. */
@Composable
private fun OfflineAreaRows(
    state: OfflineMapState,
    estimate: (OfflineAreaSize) -> Long,
    onDownload: (OfflineAreaSize) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    var size by rememberSaveable { mutableStateOf(OfflineAreaSize.MEDIUM) }
    var deleting by remember { mutableStateOf(false) }
    MonoLabel(stringResource(R.string.field_offline_title))
    Text(stringResource(R.string.field_offline_sub), style = SotreusTheme.typography.bodyS, color = SotreusTheme.colors.textMuted)
    val area = state.area
    val percent = state.percent
    val failure = state.failure
    KeyValueTable(listOfNotNull(
        stringResource(R.string.field_state) to when {
            percent != null -> stringResource(R.string.field_offline_downloading, percent)
            area != null -> stringResource(R.string.field_offline_saved, area.km, "%.1f".format(area.bytes / 1_048_576.0), dayLabel(area.savedAtMs))
            else -> stringResource(R.string.field_offline_none)
        },
    ))
    if (failure != null) WarningBanner(stringResource(offlineFailureText(failure)))
    if (percent != null) {
        GhostButton(stringResource(app.sotreus.core.ui.R.string.core_ui_cancel), onCancel)
    } else {
        SegmentedToggle(OfflineAreaSize.entries.map { it to stringResource(R.string.field_offline_size, it.km) }, size, { size = it })
        val tiles = estimate(size)
        MonoLabel(stringResource(R.string.field_offline_estimate, "%,d".format(tiles)), small = true)
        val fits = tiles <= OfflineMaps.TILE_LIMIT
        if (!fits) MonoLabel(stringResource(R.string.field_offline_fail_large), small = true)
        GhostButton(stringResource(if (area == null) R.string.field_offline_download else R.string.field_offline_replace), { onDownload(size) }, enabled = fits)
        if (area != null) DestructiveTextButton(stringResource(R.string.field_offline_delete), { deleting = true }, androidx.compose.ui.Modifier)
    }
    if (deleting) ConfirmDialog(stringResource(R.string.field_offline_delete), stringResource(R.string.field_offline_delete_body),
        stringResource(app.sotreus.core.ui.R.string.core_ui_delete), stringResource(app.sotreus.core.ui.R.string.core_ui_cancel),
        { deleting = false; onDelete() }, { deleting = false })
}

private fun offlineFailureText(f: OfflineMapFailure) = when (f) {
    OfflineMapFailure.NO_FIX -> R.string.field_offline_fail_fix
    OfflineMapFailure.REACH -> R.string.field_offline_fail_reach
    OfflineMapFailure.SERVER -> R.string.field_offline_fail_server
    OfflineMapFailure.LARGE -> R.string.field_offline_fail_large
    OfflineMapFailure.OTHER -> R.string.field_offline_fail_other
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
