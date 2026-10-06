package app.sotreus.feature.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.device.DeviceProfileRepository
import app.sotreus.core.data.export.ExportOptions
import app.sotreus.core.data.export.ExportService
import app.sotreus.core.data.repository.DataControls
import app.sotreus.core.data.repository.ProofRepository
import app.sotreus.core.data.repository.SessionLiveView
import app.sotreus.core.data.repository.SessionRepository
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.SessionEventKind
import app.sotreus.core.model.SessionKind
import app.sotreus.core.model.SolanaCluster
import app.sotreus.core.navigation.CompareRoute
import app.sotreus.core.navigation.SessionRoute
import app.sotreus.core.navigation.StampRoute
import app.sotreus.core.ui.AutoSizeText
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ConfirmDialog
import app.sotreus.core.ui.DestructiveTextButton
import app.sotreus.core.ui.ExportSheet
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.SessionTimeline
import app.sotreus.core.ui.Stat
import app.sotreus.core.ui.StatGrid
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.dayLabel
import app.sotreus.core.ui.durationLabel
import app.sotreus.core.ui.shareFile
import app.sotreus.context.ContextRepository
import app.sotreus.context.SessionContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.sotreus.core.ui.R as UiR

data class SummaryUiState(val loading: Boolean = true, val view: SessionLiveView? = null, val proofs: Boolean = false, val context: SessionContext? = null)

@HiltViewModel
class SessionSummaryViewModel @Inject constructor(
    handle: SavedStateHandle,
    sessions: SessionRepository,
    device: DeviceProfileRepository,
    private val proofs: ProofRepository,
    private val controls: DataControls,
    private val exports: ExportService,
    contextSources: ContextRepository,
) : ViewModel() {
    val id = handle.toRoute<SessionRoute>().sessionId
    private val overlaps = flow { emit(null); emit(runCatching { contextSources.sessionContext(id) }.getOrNull()) }
    val state: StateFlow<SummaryUiState> = combine(sessions.observeLive(id), device.capabilities, overlaps) { v, caps, ctx ->
        SummaryUiState(false, v, caps.onChainProofStamping, ctx)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUiState())

    fun stamp(onBatch: (Long?) -> Unit) = viewModelScope.launch { onBatch(proofs.createForSession(id, SolanaCluster.DEVNET)) }
    fun delete(done: () -> Unit) = viewModelScope.launch { controls.deleteSession(id); done() }
    fun export(options: ExportOptions, onReady: (android.net.Uri) -> Unit) = viewModelScope.launch { exports.exportSession(id, options)?.let(onReady) }
}

/** Session summary (not designed): stats and timeline from screen 11, plus actions. */
@Composable
internal fun SessionSummaryScreen(navigate: (Any) -> Unit, onBack: () -> Unit, vm: SessionSummaryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var noRecords by remember { mutableStateOf(false) }
    val shareTitle = stringResource(UiR.string.export_share)
    val v = state.view
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        if (v == null) {
            if (!state.loading) CaveatBox(stringResource(R.string.feature_journey_title), stringResource(R.string.summary_missing))
            return@ScreenColumn
        }
        val s = v.session
        val end = s.endedAtMs ?: System.currentTimeMillis()
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            MonoLabel(stringResource(if (s.kind == SessionKind.JOURNEY) R.string.summary_kicker_journey else R.string.summary_kicker_sit, durationLabel(end - s.startedAtMs)))
            AutoSizeText(s.name, SotreusTheme.typography.title, SotreusTheme.colors.text)
            Text(
                stringResource(R.string.summary_when, dayLabel(s.startedAtMs), clockTime(s.startedAtMs), clockTime(end)),
                style = SotreusTheme.typography.body,
                color = SotreusTheme.colors.textMuted,
            )
        }
        StatGrid(
            listOf(
                Stat(v.radios.toString(), stringResource(R.string.stat_radios)),
                Stat(v.newToYou.toString(), stringResource(R.string.stat_new_to_you)),
                Stat(v.attention.toString(), stringResource(R.string.stat_attention), attention = v.attention > 0),
            ),
            columns = 3,
        )
        MonoLabel(stringResource(R.string.timeline))
        SessionTimeline(v.events.filter { it.kind != SessionEventKind.ENDED }.map { timelineItem(it) })
        state.context?.let { SessionContextSection(it) }
        if (noRecords) CaveatBox(stringResource(R.string.feature_journey_title), stringResource(R.string.summary_no_records))
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            if (state.proofs && s.endedAtMs != null) {
                PrimaryButton(stringResource(R.string.summary_stamp), { vm.stamp { id -> if (id != null) navigate(StampRoute(id)) else noRecords = true } })
            }
            if (s.kind == SessionKind.SIT && s.endedAtMs != null) GhostButton(stringResource(R.string.summary_compare), { navigate(CompareRoute(b = s.id)) }, strong = true)
            GhostButton(stringResource(R.string.summary_export), { exporting = true })
            DestructiveTextButton(stringResource(R.string.summary_delete), { confirm = true })
        }
    }
    if (confirm && v != null) {
        ConfirmDialog(
            stringResource(R.string.summary_delete_title, v.session.name), stringResource(R.string.summary_delete_body),
            stringResource(UiR.string.core_ui_delete), stringResource(UiR.string.core_ui_cancel),
            { confirm = false; vm.delete(onBack) }, { confirm = false },
        )
    }
    if (exporting) {
        ExportSheet(
            onExport = { choice ->
                exporting = false
                vm.export(
                    ExportOptions(
                        coordinates = if (choice.exactCoordinates) ExportOptions.Coordinates.EXACT else ExportOptions.Coordinates.COARSE,
                        hashIdentifiers = !choice.rawIdentifiers, includeNotes = choice.includeNotes, timeBucketMinutes = if (choice.exactTimes) 0 else 15,
                    ),
                ) { shareFile(context, it, shareTitle) }
            },
            onDismiss = { exporting = false },
        )
    }
}
