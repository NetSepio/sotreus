package app.sotreus.feature.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.device.DeviceProfileRepository
import app.sotreus.core.data.live.LiveSnapshot
import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.repository.ProfileRepository
import app.sotreus.core.data.repository.ProofRepository
import app.sotreus.core.data.repository.SessionEnd
import app.sotreus.core.data.repository.SessionLiveView
import app.sotreus.core.data.repository.SessionRepository
import app.sotreus.core.database.entity.SessionEntity
import app.sotreus.core.database.entity.SessionEventEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.SessionEventKind
import app.sotreus.core.model.SessionKind
import app.sotreus.core.model.SolanaCluster
import app.sotreus.core.navigation.SessionLiveRoute
import app.sotreus.core.navigation.SessionRoute
import app.sotreus.core.navigation.StampRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.CompactButton
import app.sotreus.core.ui.GlyphShape
import app.sotreus.core.ui.GlyphSpec
import app.sotreus.core.ui.GlyphTone
import app.sotreus.core.ui.LightButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.SessionTimeline
import app.sotreus.core.ui.Stat
import app.sotreus.core.ui.StatGrid
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.StatusPill
import app.sotreus.core.ui.TextInputDialog
import app.sotreus.core.ui.TimelineItem
import app.sotreus.core.ui.ageShort
import app.sotreus.core.ui.clockTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.sotreus.core.ui.R as UiR

data class LiveUiState(val loading: Boolean = true, val view: SessionLiveView? = null, val snapshot: LiveSnapshot = LiveSnapshot(), val now: Long = 0)

@HiltViewModel
class LiveSessionViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val proofs: ProofRepository,
    private val device: DeviceProfileRepository,
    private val profiles: ProfileRepository,
    pipeline: ObservationPipeline,
) : ViewModel() {
    val id = handle.toRoute<SessionLiveRoute>().sessionId
    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }
    val state: StateFlow<LiveUiState> = combine(sessions.observeLive(id), pipeline.snapshot, ticker) { v, snap, now -> LiveUiState(false, v, snap, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())

    fun addNote(text: String) = viewModelScope.launch { sessions.addNote(id, text) }

    /** Ends the session; on a Solana device with "ask at end" stamping, prepares a batch for S4. */
    fun end(navigate: (Any) -> Unit) = viewModelScope.launch {
        val stampingAvailable = device.capabilities.first().onChainProofStamping && profiles.wallet.first() != null
        when (val result = sessions.end(id, stampingAvailable)) {
            is SessionEnd.AskToStamp -> {
                val batch = proofs.createForSession(result.sessionId, SolanaCluster.DEVNET)
                navigate(if (batch != null) StampRoute(batch) else SessionRoute(id))
            }
            SessionEnd.Summary -> navigate(SessionRoute(id))
        }
    }
}

@Composable
internal fun LiveSessionScreen(replace: (Any) -> Unit, vm: LiveSessionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LiveSessionContent(state, vm::addNote, { vm.end(replace) }, { replace(SessionRoute(vm.id)) })
}

/** Screen 11. Freshness stays visible; Wi-Fi delays are shown as system notes. */
@Composable
internal fun LiveSessionContent(state: LiveUiState, onNote: (String) -> Unit, onEnd: () -> Unit, onSummary: () -> Unit) {
    val c = SotreusTheme.colors
    var noting by remember { mutableStateOf(false) }
    val v = state.view
    ScreenColumn {
        if (v == null) {
            if (!state.loading) CaveatBox(stringResource(R.string.feature_journey_title), stringResource(R.string.summary_missing))
            return@ScreenColumn
        }
        val s = v.session
        if (s.endedAtMs != null) {
            CaveatBox(stringResource(R.string.feature_journey_title), stringResource(R.string.live_ended))
            LightButton(stringResource(R.string.live_open_summary), onSummary)
            return@ScreenColumn
        }
        val journey = s.kind == SessionKind.JOURNEY
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(stringResource(if (journey) R.string.live_journey else R.string.live_sit), Modifier.weight(1f), filled = false)
            CompactButton(stringResource(R.string.live_add_note), { noting = true })
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(elapsed(state.now - s.startedAtMs), style = SotreusTheme.typography.monoTimer, color = c.text)
            Text(
                s.startLabel?.let { stringResource(R.string.live_started_near, clockTime(s.startedAtMs), it) } ?: stringResource(R.string.live_started, clockTime(s.startedAtMs)),
                style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f),
                color = c.textMuted,
            )
        }
        FreshnessChips(s, state.snapshot)
        StatGrid(
            listOf(
                Stat(v.radios.toString(), stringResource(R.string.stat_radios)),
                Stat(v.newToYou.toString(), stringResource(R.string.stat_new_to_you)),
                Stat(v.attention.toString(), stringResource(R.string.stat_attention), attention = true),
            ),
            columns = 3,
        )
        MonoLabel(stringResource(R.string.timeline))
        SessionTimeline(v.events.filter { it.kind != SessionEventKind.STARTED }.take(40).map { timelineItem(it) })
        Spacer(Modifier.weight(1f))
        LightButton(stringResource(if (journey) R.string.end_journey else R.string.end_sit), onEnd)
    }
    if (noting) {
        TextInputDialog(stringResource(R.string.live_note_title), "", stringResource(R.string.live_note_hint), stringResource(UiR.string.core_ui_save), stringResource(UiR.string.core_ui_cancel), { noting = false; onNote(it) }, { noting = false }, multiline = true)
    }
}

@Composable
private fun FreshnessChips(s: SessionEntity, snap: LiveSnapshot) {
    val now = snap.atMs
    val bleAge = snap.status.bleLastResultMs.takeIf { it > 0 }?.let { now - it }
    val wifiAge = snap.status.wifiLastFreshMs.takeIf { it > 0 }?.let { now - it }
    val throttled = snap.status.wifiLastRequestRejected || (wifiAge ?: 0) > snap.status.wifiScanIntervalMs
    val dash = stringResource(R.string.chip_dash)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
        StateChip(stringResource(if (s.geotag) R.string.chip_location_on else R.string.chip_location_off), ChipTone.NEUTRAL, small = false)
        StateChip(stringResource(R.string.chip_ble, bleAge?.let { ageShort(it) } ?: dash), ChipTone.NEUTRAL, small = false)
        StateChip(stringResource(R.string.chip_wifi, wifiAge?.let { ageShort(it) } ?: dash), if (throttled) ChipTone.ATTENTION_OUTLINE else ChipTone.NEUTRAL, small = false)
    }
}

/** Maps a session event to the shared timeline glyph language. */
@Composable
internal fun timelineItem(e: SessionEventEntity): TimelineItem {
    val (text, glyph, muted) = when (e.kind) {
        SessionEventKind.NEW_FINGERPRINT -> Triple(stringResource(UiR.string.event_new_fingerprint), GlyphSpec(GlyphShape.BLE, GlyphTone.NEW), false)
        SessionEventKind.FINGERPRINT_LOST -> Triple(stringResource(UiR.string.event_lost), GlyphSpec(GlyphShape.BLE, GlyphTone.NEW), true)
        SessionEventKind.TAGGED_REENCOUNTER -> Triple(stringResource(UiR.string.event_tagged), GlyphSpec(GlyphShape.TAGGED, GlyphTone.TAGGED), false)
        SessionEventKind.FAMILY_SIGNATURE -> Triple(
            e.text?.let { n -> app.sotreus.core.model.DeviceFamily.entries.firstOrNull { it.name == n } }
                ?.let { stringResource(UiR.string.headline_family, app.sotreus.core.ui.familySignature(it)) } ?: stringResource(UiR.string.headline_family_generic),
            GlyphSpec(GlyphShape.BLE, GlyphTone.NEW),
            false,
        )
        SessionEventKind.ATTENTION -> Triple(
            stringResource(UiR.string.event_attention, e.text?.let { h -> app.sotreus.core.model.AttentionHeadline.entries.firstOrNull { it.name == h } }?.let { app.sotreus.core.ui.attentionHeadline(it, null) } ?: ""),
            GlyphSpec(GlyphShape.BLE, GlyphTone.NEW),
            false,
        )
        SessionEventKind.WIFI_SCAN_DELAYED -> Triple(stringResource(UiR.string.event_wifi_delayed), GlyphSpec(GlyphShape.SYSTEM_NOTE, GlyphTone.NEUTRAL), true)
        SessionEventKind.BLE_PAUSED -> Triple(stringResource(UiR.string.event_ble_paused), GlyphSpec(GlyphShape.SYSTEM_NOTE, GlyphTone.NEUTRAL), true)
        SessionEventKind.USER_NOTE -> Triple(stringResource(R.string.note_prefix, e.text.orEmpty()), GlyphSpec(GlyphShape.SYSTEM_NOTE, GlyphTone.NEUTRAL), false)
        SessionEventKind.STARTED -> Triple(stringResource(UiR.string.event_started), GlyphSpec(GlyphShape.SYSTEM_NOTE, GlyphTone.NEUTRAL), true)
        SessionEventKind.ENDED -> Triple(stringResource(UiR.string.event_ended), GlyphSpec(GlyphShape.SYSTEM_NOTE, GlyphTone.NEUTRAL), true)
    }
    return TimelineItem(clockTime(e.atMs), text, glyph, muted)
}

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun LivePreview() {
    val now = FakeSotreusData.NOW
    val session = SessionEntity(9, SessionKind.JOURNEY, "Journey", now - 1_421_000, null, true, null, "Home")
    val events = listOf(
        SessionEventEntity(5, 9, now - 600_000, SessionEventKind.FAMILY_SIGNATURE, "x", "CAMERA"),
        SessionEventEntity(4, 9, now - 1_020_000, SessionEventKind.FINGERPRINT_LOST, "y"),
        SessionEventEntity(3, 9, now - 1_200_000, SessionEventKind.TAGGED_REENCOUNTER, "z", "Grey tag"),
        SessionEventEntity(2, 9, now - 1_260_000, SessionEventKind.WIFI_SCAN_DELAYED),
        SessionEventEntity(1, 9, now - 1_320_000, SessionEventKind.NEW_FINGERPRINT, "w"),
    )
    SotreusTheme { LiveSessionContent(LiveUiState(false, SessionLiveView(session, events, 87, 14, 1), FakeSotreusData.snapshot, now), {}, {}, {}) }
}
