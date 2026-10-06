package app.sotreus.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.export.ExportService
import app.sotreus.core.data.live.LiveSnapshot
import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.AppBuildInfo
import app.sotreus.core.model.GeotagMode
import app.sotreus.core.model.ScanIntensity
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.DashedPanel
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.KeyValueRow
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SegmentedToggle
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.ageShort
import app.sotreus.core.ui.shareFile
import app.sotreus.feature.settings.diagnostics.DeviceInfo
import app.sotreus.feature.settings.diagnostics.DeviceInfoSource
import app.sotreus.sensing.PermissionGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

data class SensorsUiState(
    val snapshot: LiveSnapshot = LiveSnapshot(),
    val settings: SotreusSettings = SotreusSettings(),
    val device: DeviceInfo? = null,
    val build: AppBuildInfo? = null,
)

@HiltViewModel
class SensorsViewModel @Inject constructor(
    pipeline: ObservationPipeline,
    private val settings: SettingsRepository,
    private val exports: ExportService,
    private val build: AppBuildInfo,
    deviceInfo: DeviceInfoSource,
) : ViewModel() {
    private val device = deviceInfo.current()
    val state: StateFlow<SensorsUiState> = combine(pipeline.snapshot, settings.settings) { snap, s -> SensorsUiState(snap, s, device, build) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensorsUiState(device = device, build = build))

    fun setIntensity(i: ScanIntensity) = viewModelScope.launch { settings.setScanIntensity(i) }

    /** Redacted by default (handoff §26, §34): no addresses, SSIDs, coordinates, tokens or wallet. */
    fun exportDiagnostics(onReady: (android.net.Uri) -> Unit) = viewModelScope.launch {
        val s = state.value
        val st = s.snapshot.status
        val json = buildJsonObject {
            put("format", "sotreus-diagnostics")
            put("redacted", true)
            put("appVersion", build.versionName)
            put("distribution", build.distribution.flavorName)
            put("buildType", build.buildType)
            put("databaseSchema", build.databaseSchemaVersion)
            put("android", device.androidRelease)
            put("apiLevel", device.apiLevel)
            put("deviceModel", "${device.manufacturer} ${device.model}")
            put("observing", s.snapshot.observing)
            put("simulatedRadios", s.settings.simulatedRadios)
            put("scanIntensity", s.settings.scanIntensity.name)
            put("bleRunning", st.bleRunning)
            put("bleResultsPerMinute", st.bleResultsPerMinute)
            put("bleLastResultAgeMs", st.bleLastResultMs.takeIf { it > 0 }?.let { s.snapshot.atMs - it })
            put("wifiLastFreshAgeMs", st.wifiLastFreshMs.takeIf { it > 0 }?.let { s.snapshot.atMs - it })
            put("wifiLastRequestRejected", st.wifiLastRequestRejected)
            put("permissionsGranted", s.snapshot.access?.granted?.joinToString(",") { it.name })
            put("bluetoothOn", s.snapshot.access?.bluetoothOn)
            put("wifiOn", s.snapshot.access?.wifiOn)
            put("locationOn", s.snapshot.access?.locationOn)
            put("liveRadioCount", s.snapshot.live.size)
        }.toString()
        onReady(exports.writeText("sotreus-diagnostics-${System.currentTimeMillis()}.json", json))
    }
}

/** Screen 13. Throttling is surfaced, never hidden. */
@Composable
internal fun SensorsScreen(onBack: () -> Unit, vm: SensorsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(app.sotreus.core.ui.R.string.export_share)
    val snap = state.snapshot
    val st = snap.status
    val access = snap.access
    val now = snap.atMs
    val never = stringResource(R.string.kv_never)
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.sensors_title))

        val bleGranted = access?.granted?.contains(PermissionGroup.NEARBY_DEVICES) == true
        SensorCard(
            title = stringResource(R.string.sensor_ble),
            status = when {
                st.simulated && st.running -> stringResource(R.string.status_simulated)
                st.bleRunning -> stringResource(R.string.status_scanning)
                st.bleRetrying -> stringResource(R.string.status_retrying)
                else -> stringResource(R.string.status_off)
            },
            statusTone = if (st.bleRunning) ChipTone.ACCENT_FILLED else ChipTone.NEUTRAL,
            attention = false,
            rows = listOf(
                stringResource(R.string.kv_permission) to stringResource(if (bleGranted) R.string.kv_granted else R.string.kv_not_granted),
                stringResource(R.string.kv_last_result) to (st.bleLastResultMs.takeIf { it > 0 }?.let { stringResource(R.string.kv_ago, ageShort(now - it)) } ?: never),
                stringResource(R.string.kv_results_min) to "%,d".format(st.bleResultsPerMinute),
            ),
        )
        val wifiAge = st.wifiLastFreshMs.takeIf { it > 0 }?.let { now - it }
        val limited = st.running && (st.wifiLastRequestRejected || (wifiAge ?: 0) > st.wifiScanIntervalMs)
        val wifiGranted = access?.granted?.contains(PermissionGroup.NEARBY_WIFI) == true
        SensorCard(
            title = stringResource(R.string.sensor_wifi),
            status = when {
                limited -> stringResource(R.string.status_os_limiting)
                st.running -> stringResource(if (st.simulated) R.string.status_simulated else R.string.status_scanning)
                else -> stringResource(R.string.status_off)
            },
            statusTone = if (limited) ChipTone.ACCENT else if (st.running) ChipTone.ACCENT_FILLED else ChipTone.NEUTRAL,
            attention = limited,
            rows = listOf(
                stringResource(R.string.kv_permission) to stringResource(if (wifiGranted) R.string.kv_granted else R.string.kv_not_granted),
                stringResource(R.string.kv_last_scan) to (wifiAge?.let { stringResource(R.string.kv_ago, ageShort(it)) } ?: never),
                stringResource(R.string.kv_scan_requested) to when {
                    st.wifiLastRequestRejected -> stringResource(R.string.kv_scan_rejected)
                    st.wifiScanRequested || st.running -> stringResource(R.string.kv_scan_yes_next, ageShort((st.wifiNextScanMs - now).coerceAtLeast(0)))
                    else -> stringResource(R.string.kv_scan_no)
                },
            ),
            note = stringResource(R.string.wifi_note),
        )
        val locGranted = access?.granted?.contains(PermissionGroup.LOCATION) == true
        SensorCard(
            title = stringResource(R.string.sensor_location),
            status = stringResource(if (locGranted) R.string.status_while_in_use else R.string.status_not_granted),
            statusTone = ChipTone.NEUTRAL,
            attention = false,
            rows = listOf(
                stringResource(R.string.kv_used_for) to stringResource(R.string.kv_scan_apis),
                stringResource(R.string.kv_session_geotag) to stringResource(
                    when (state.settings.geotagMode) {
                        GeotagMode.ASK_EACH_TIME -> R.string.geotag_ask
                        GeotagMode.ALWAYS -> R.string.geotag_always
                        GeotagMode.NEVER -> R.string.geotag_never
                    },
                ),
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            MonoLabel(stringResource(R.string.scan_intensity))
            SegmentedToggle(
                listOf(
                    ScanIntensity.SAVER to stringResource(R.string.intensity_saver),
                    ScanIntensity.BALANCED to stringResource(R.string.intensity_balanced),
                    ScanIntensity.PERFORMANCE to stringResource(R.string.intensity_performance),
                ),
                state.settings.scanIntensity,
                vm::setIntensity,
            )
            Text(stringResource(R.string.stale_after, state.settings.staleHoldSeconds), style = SotreusTheme.typography.bodyS, color = SotreusTheme.colors.textMuted)
        }
        DashedPanel {
            MonoLabel(stringResource(R.string.diag_kicker), small = true)
            state.device?.let { d ->
                KeyValueRow(stringResource(R.string.kv_android), stringResource(R.string.kv_android_value, d.androidRelease, d.apiLevel))
                KeyValueRow(stringResource(R.string.kv_device), "${d.manufacturer} ${d.model}")
            }
            state.build?.let { KeyValueRow(stringResource(R.string.kv_db_schema), stringResource(R.string.kv_schema_value, it.databaseSchemaVersion)) }
            GhostButton(stringResource(R.string.export_diagnostics), { vm.exportDiagnostics { shareFile(context, it, shareTitle) } }, Modifier.padding(top = 8.dp, bottom = 10.dp), minHeight = 44.dp)
        }
    }
}

@Composable
private fun SensorCard(title: String, status: String, statusTone: ChipTone, attention: Boolean, rows: List<Pair<String, String>>, note: String? = null) {
    val c = SotreusTheme.colors
    Column(
        Modifier.fillMaxWidth().background(if (attention) c.attentionSurfaceLarge else c.surface, SotreusTheme.shapes.featureCard)
            .border(1.dp, if (attention) c.attentionLine else c.line, SotreusTheme.shapes.featureCard)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = SotreusTheme.typography.bodyL.copy(fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
            StateChip(status, statusTone, small = false)
        }
        rows.forEachIndexed { i, (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, style = SotreusTheme.typography.monoValue, color = if (attention) c.textMuted else c.textDim)
                Text(v, style = SotreusTheme.typography.monoValue, color = c.text)
            }
            if (i < rows.lastIndex || note != null) RowDivider()
        }
        note?.let { Text(it, style = SotreusTheme.typography.bodyS, color = c.textSoft, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)) }
    }
}
