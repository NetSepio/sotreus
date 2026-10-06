package app.sotreus.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.crypto.Base58
import app.sotreus.core.data.device.DeviceProfileRepository
import app.sotreus.core.data.live.LiveSnapshot
import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.repository.DataControls
import app.sotreus.core.data.repository.ProfileRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.entity.LinkedIdentityEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.AppBuildInfo
import app.sotreus.core.model.DeviceProfile
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.DashedPanel
import app.sotreus.core.ui.KeyValueRow
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SwitchRow
import app.sotreus.feature.settings.diagnostics.DeviceInfo
import app.sotreus.feature.settings.diagnostics.DeviceInfoSource
import app.sotreus.intelligence.SignatureClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    val build: AppBuildInfo,
    val device: DeviceInfo,
    val profile: DeviceProfile? = null,
    val snapshot: LiveSnapshot = LiveSnapshot(),
    val settings: SotreusSettings = SotreusSettings(),
    val wallet: LinkedIdentityEntity? = null,
    val catalog: Int = 0,
    val debuggable: Boolean = false,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    build: AppBuildInfo,
    deviceInfo: DeviceInfoSource,
    private val device: DeviceProfileRepository,
    pipeline: ObservationPipeline,
    private val settings: SettingsRepository,
    profiles: ProfileRepository,
    classifier: SignatureClassifier,
    private val controls: DataControls,
) : ViewModel() {
    private val base = DiagnosticsUiState(build, deviceInfo.current(), catalog = classifier.catalogSize, debuggable = device.isDebuggable)
    val state: StateFlow<DiagnosticsUiState> = combine(device.profile, pipeline.snapshot, settings.settings, profiles.wallet) { p, snap, s, w ->
        base.copy(profile = p, snapshot = snap, settings = s, wallet = w)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), base)

    fun setSimulated(on: Boolean) = viewModelScope.launch {
        settings.setSimulatedRadios(on)
        if (!on) controls.deleteSimulated()
    }

    fun setForceSolana(on: Boolean) = viewModelScope.launch { settings.setForceSolanaUi(on) }
}

/** Diagnostics (architecture handoff §34). Shows identifiers only abbreviated. */
@Composable
internal fun DiagnosticsScreen(onBack: () -> Unit, vm: DiagnosticsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val yes = stringResource(R.string.kv_yes)
    val no = stringResource(R.string.kv_no)
    val on = stringResource(R.string.kv_on)
    val off = stringResource(R.string.kv_off)
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.diagnostics_title))
        DashedPanel {
            MonoLabel(stringResource(R.string.diagnostics_build), small = true)
            KeyValueRow(stringResource(R.string.kv_distribution), s.build.distribution.flavorName)
            KeyValueRow(stringResource(R.string.kv_app_version), stringResource(R.string.kv_version_value, s.build.versionName, s.build.versionCode))
            KeyValueRow(stringResource(R.string.kv_build_type), s.build.buildType)
            KeyValueRow(stringResource(R.string.kv_application_id), s.build.applicationId)
            KeyValueRow(stringResource(R.string.kv_db_schema), stringResource(R.string.kv_schema_value, s.build.databaseSchemaVersion))
        }
        DashedPanel {
            MonoLabel(stringResource(R.string.diagnostics_device), small = true)
            KeyValueRow(stringResource(R.string.kv_android), stringResource(R.string.kv_android_value, s.device.androidRelease, s.device.apiLevel))
            KeyValueRow(stringResource(R.string.kv_device), "${s.device.manufacturer} ${s.device.model}")
            KeyValueRow(stringResource(R.string.kv_solana_device), if (s.profile?.isSolanaMobile == true) yes else no)
        }
        val access = s.snapshot.access
        DashedPanel {
            MonoLabel(stringResource(R.string.diagnostics_sensors), small = true)
            KeyValueRow(stringResource(R.string.kv_bluetooth), if (access?.bluetoothOn == true) on else off)
            KeyValueRow(stringResource(R.string.kv_wifi), if (access?.wifiOn == true) on else off)
            KeyValueRow(stringResource(R.string.kv_location), if (access?.locationOn == true) on else off)
            KeyValueRow(stringResource(R.string.kv_permissions), stringResource(if (access?.canScan == true) R.string.kv_scan_ok else R.string.kv_scan_missing))
            KeyValueRow(stringResource(R.string.kv_results_min), "%,d".format(s.snapshot.status.bleResultsPerMinute))
        }
        DashedPanel {
            MonoLabel(stringResource(R.string.diagnostics_identity), small = true)
            KeyValueRow(stringResource(R.string.kv_identity_type), stringResource(if (s.wallet != null) R.string.kv_identity_wallet else R.string.kv_identity_local))
            s.wallet?.let {
                KeyValueRow(stringResource(R.string.kv_wallet), Base58.abbreviate(it.publicKey))
                KeyValueRow(stringResource(R.string.kv_cluster), it.cluster?.name?.lowercase() ?: "—")
            }
            KeyValueRow(stringResource(R.string.kv_presence), if (s.settings.nearbyPresence) on else off)
            KeyValueRow(stringResource(R.string.kv_signature_catalog), stringResource(R.string.kv_catalog_value, s.catalog))
        }
        Column {
            MonoLabel(stringResource(R.string.diagnostics_testing))
            SwitchRow(stringResource(R.string.simulated_radios), s.settings.simulatedRadios, vm::setSimulated, subtitle = stringResource(R.string.simulated_radios_sub), bordered = false)
            if (s.debuggable) {
                RowDivider()
                SwitchRow(stringResource(R.string.force_solana), s.settings.forceSolanaUi, vm::setForceSolana, subtitle = stringResource(R.string.force_solana_sub), bordered = false)
            }
        }
        Text(stringResource(R.string.diagnostics_note), style = SotreusTheme.typography.caption, color = SotreusTheme.colors.textDim, modifier = Modifier)
    }
}

