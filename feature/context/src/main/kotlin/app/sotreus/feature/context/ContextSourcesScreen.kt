package app.sotreus.feature.context

import androidx.compose.foundation.layout.Column
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
import app.sotreus.context.ContextRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.AircraftMode
import app.sotreus.core.model.SatelliteGroup
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ConfirmDialog
import app.sotreus.core.ui.DestructiveTextButton
import app.sotreus.core.ui.KeyValueTable
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SectionHeader
import app.sotreus.core.ui.SegmentedToggle
import app.sotreus.core.ui.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContextSourcesUiState(val settings: SotreusSettings = SotreusSettings(), val records: Int = 0)

@HiltViewModel
class ContextSourcesViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val repo: ContextRepository,
) : ViewModel() {
    val state: StateFlow<ContextSourcesUiState> = combine(settings.settings, repo.recordCount()) { s, n -> ContextSourcesUiState(s, n) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContextSourcesUiState())

    fun setEnabled(v: Boolean) = viewModelScope.launch { settings.setContextEnabled(v) }
    fun setAircraftMode(v: AircraftMode) = viewModelScope.launch { settings.setAircraftMode(v) }
    fun setRadius(v: Int) = viewModelScope.launch { settings.setAircraftRadiusKm(v) }
    fun setSatellites(v: Boolean) = viewModelScope.launch { settings.setSatellitesEnabled(v) }
    fun toggleGroup(g: SatelliteGroup, on: Boolean) = viewModelScope.launch {
        val cur = state.value.settings.satelliteGroups
        settings.setSatelliteGroups(if (on) cur + g else cur - g)
    }
    fun setRemoteId(v: Boolean) = viewModelScope.launch { settings.setRemoteIdEnabled(v) }
    fun clear() = viewModelScope.launch { repo.clearAll() }
}

/** Every context source, what it sends and what it is labelled. Each can be switched off. */
@Composable
internal fun ContextSourcesScreen(onBack: () -> Unit, vm: ContextSourcesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    var confirmClear by remember { mutableStateOf(false) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.ctx_sources_title))
        MonoLabel(stringResource(R.string.ctx_sources_lead), small = true)
        SwitchRow(stringResource(R.string.ctx_master), s.contextEnabled, { vm.setEnabled(it) }, subtitle = stringResource(R.string.ctx_master_sub))

        val on = s.contextEnabled
        SectionHeader(stringResource(R.string.ctx_air_section))
        SegmentedToggle(
            listOf(
                AircraftMode.OFF to stringResource(R.string.ctx_mode_off),
                AircraftMode.COARSE_AREA to stringResource(R.string.ctx_mode_coarse),
                AircraftMode.EXACT_AREA to stringResource(R.string.ctx_mode_exact),
            ),
            s.aircraftMode,
            { if (on) vm.setAircraftMode(it) },
        )
        MonoLabel(
            stringResource(
                when (s.aircraftMode) {
                    AircraftMode.OFF -> R.string.ctx_mode_off_body
                    AircraftMode.COARSE_AREA -> R.string.ctx_mode_coarse_body
                    AircraftMode.EXACT_AREA -> R.string.ctx_mode_exact_body
                },
            ),
            small = true,
        )
        if (s.aircraftMode != AircraftMode.OFF) {
            MonoLabel(stringResource(R.string.ctx_radius_label), small = true)
            SegmentedToggle(RADII.map { it to "$it km" }, s.aircraftRadiusKm.takeIf { it in RADII } ?: 25, { if (on) vm.setRadius(it) })
        }

        SectionHeader(stringResource(R.string.ctx_sat_section))
        SwitchRow(stringResource(R.string.ctx_sat_switch), s.satellitesEnabled, { vm.setSatellites(it) }, subtitle = stringResource(R.string.ctx_sat_switch_sub), enabled = on)
        if (s.satellitesEnabled) {
            Column {
                SatelliteGroup.entries.forEach { g ->
                    SwitchRow(groupName(g), g in s.satelliteGroups, { vm.toggleGroup(g, it) }, bordered = false, enabled = on)
                }
            }
        }

        SectionHeader(stringResource(R.string.ctx_rid_section))
        SwitchRow(stringResource(R.string.ctx_rid_switch), s.remoteIdEnabled, { vm.setRemoteId(it) }, subtitle = stringResource(R.string.ctx_rid_switch_sub), enabled = on)
        CaveatBox(stringResource(R.string.ctx_rid_region_kicker), stringResource(R.string.ctx_rid_region_body))

        KeyValueTable(
            listOf(
                stringResource(R.string.ctx_sent_opensky) to stringResource(if (s.aircraftMode == AircraftMode.EXACT_AREA) R.string.ctx_sent_opensky_exact else if (s.aircraftMode == AircraftMode.COARSE_AREA) R.string.ctx_sent_opensky_coarse else R.string.ctx_sent_nothing),
                stringResource(R.string.ctx_sent_celestrak) to stringResource(if (s.satellitesEnabled) R.string.ctx_sent_celestrak_value else R.string.ctx_sent_nothing),
                stringResource(R.string.ctx_sent_remote_id) to stringResource(R.string.ctx_sent_nothing),
            ),
            title = stringResource(R.string.ctx_sent_title),
        )
        MonoLabel(stringResource(R.string.ctx_records, state.records), small = true)
        DestructiveTextButton(stringResource(R.string.ctx_clear), { confirmClear = true }, Modifier)
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.ctx_clear_title),
            body = stringResource(R.string.ctx_clear_body),
            confirm = stringResource(R.string.ctx_clear_confirm),
            dismiss = stringResource(R.string.ctx_cancel),
            onConfirm = { vm.clear(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
}

private val RADII = listOf(10, 25, 50, 100)
