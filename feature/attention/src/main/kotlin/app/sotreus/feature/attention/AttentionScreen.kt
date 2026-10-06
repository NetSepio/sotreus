package app.sotreus.feature.attention

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.repository.AttentionDetail
import app.sotreus.core.data.repository.AttentionRepository
import app.sotreus.core.data.repository.EntityRepository
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.UserEntityState
import app.sotreus.core.navigation.AttentionRoute
import app.sotreus.core.navigation.EntityRoute
import app.sotreus.core.navigation.EvidenceRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.InlineLink
import app.sotreus.core.ui.InputBars
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.ReasonList
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.SectionHeader
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.attentionHeadline
import app.sotreus.core.ui.attentionReasonItem
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.dayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AttentionUiState(val loading: Boolean = true, val detail: AttentionDetail? = null)

@HiltViewModel
class AttentionViewModel @Inject constructor(
    handle: SavedStateHandle,
    repository: AttentionRepository,
    private val entities: EntityRepository,
) : ViewModel() {
    private val id = handle.toRoute<AttentionRoute>().eventId
    val state: StateFlow<AttentionUiState> = repository.observeDetail(id).map { AttentionUiState(false, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttentionUiState())

    fun mark(state: UserEntityState, done: () -> Unit) = viewModelScope.launch {
        this@AttentionViewModel.state.value.detail?.event?.entityId?.let { entities.setLabel(it, state) }
        done()
    }
}

@Composable
internal fun AttentionScreen(navigate: (Any) -> Unit, onBack: () -> Unit, vm: AttentionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    AttentionContent(
        state = state,
        onBack = onBack,
        onMine = { vm.mark(UserEntityState.MINE, onBack) },
        onExpected = { vm.mark(UserEntityState.EXPECTED, onBack) },
        onEntity = { state.detail?.event?.entityId?.let { navigate(EntityRoute(it)) } },
        onEvidence = { state.detail?.event?.entityId?.let { navigate(EvidenceRoute(it)) } },
    )
}

/** Screen 05. Every event shows at least one human reason; the score is never a threat score. */
@Composable
internal fun AttentionContent(
    state: AttentionUiState,
    onBack: () -> Unit,
    onMine: () -> Unit,
    onExpected: () -> Unit,
    onEntity: () -> Unit,
    onEvidence: () -> Unit,
) {
    val c = SotreusTheme.colors
    val d = state.detail
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack) {
            d?.let {
                val t = it.event.createdAtMs
                MonoLabel(
                    if (it.placeName != null) stringResource(R.string.att_meta, dayLabel(t), clockTime(t), it.placeName!!) else stringResource(R.string.att_meta_no_place, dayLabel(t), clockTime(t)),
                    small = true,
                )
            }
        }
        if (d == null) {
            if (!state.loading) CaveatBox(stringResource(R.string.att_caveat_kicker), stringResource(R.string.att_missing))
            return@ScreenColumn
        }
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            Text(stringResource(R.string.att_kicker).uppercase(), style = SotreusTheme.typography.monoLabel, color = c.accent)
            Text(attentionHeadline(d.event.headline, d.entity?.family), style = SotreusTheme.typography.titleS.copy(fontSize = SotreusTheme.typography.titleS.fontSize * 0.94f), color = c.text, modifier = Modifier.semantics { heading() })
        }
        Row(
            Modifier.fillMaxWidth().border(1.dp, c.lineStrong, SotreusTheme.shapes.featureCard).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xxl),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
                MonoLabel(stringResource(R.string.att_score_kicker), small = true)
                Text("%.2f".format(d.event.score), style = SotreusTheme.typography.displayL.copy(lineHeight = SotreusTheme.typography.displayL.fontSize * 0.9f), color = c.text)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                StateChip(stringResource(R.string.att_needs), ChipTone.ACCENT, small = false)
                // Required copy (HANDOFF_V1_UI.md §8): "Not a threat score."
                Text(stringResource(R.string.att_not_threat), style = SotreusTheme.typography.bodyS, color = c.textMuted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xs)) {
            MonoLabel(stringResource(R.string.att_reasons), modifier = Modifier.padding(bottom = 4.dp))
            ReasonList(d.reasons.map { attentionReasonItem(it) })
        }
        InputBars(
            stringResource(R.string.att_inputs),
            listOf(
                Triple(stringResource(R.string.att_in_reencounter), d.inputs.reEncounter, false),
                Triple(stringResource(R.string.att_in_tag), d.inputs.yourTag, false),
                Triple(stringResource(R.string.att_in_persistence), d.inputs.persistence, false),
                Triple(stringResource(R.string.att_in_novelty), d.inputs.novelty, false),
                Triple(stringResource(R.string.att_in_signature), d.inputs.knownSignature, false),
                Triple(stringResource(R.string.att_in_freshness), d.inputs.freshness, true),
            ),
        )
        Column {
            SectionHeader(stringResource(R.string.att_evidence, d.evidence.size, d.evidenceTotal)) {
                InlineLink(stringResource(R.string.att_view_all), onEvidence)
            }
            d.evidence.forEachIndexed { i, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
                    Text("${dayLabel(row.observation.observedAtMs)} ${clockTime(row.observation.observedAtMs)}", style = SotreusTheme.typography.monoValue, color = c.textDim, modifier = Modifier.width(84.dp))
                    Text(row.placeName ?: stringResource(R.string.att_unsaved), style = SotreusTheme.typography.bodyS, color = c.text, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(if (row.observation.radio == RadioKind.WIFI) R.string.att_evidence_rssi_wifi else R.string.att_evidence_rssi, row.observation.rssi).replace("-", "−").uppercase(),
                        style = SotreusTheme.typography.monoValue,
                        color = c.textMuted,
                    )
                }
                if (i < d.evidence.lastIndex) RowDivider()
            }
            Text(stringResource(R.string.att_places_note), style = SotreusTheme.typography.caption, color = c.textDim)
        }
        CaveatBox(
            stringResource(R.string.att_caveat_kicker),
            stringResource(if (d.entity?.family == DeviceFamily.FINDER_TAG) R.string.att_caveat_finder else R.string.att_caveat_generic),
        )
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                GhostButton(stringResource(R.string.att_mark_mine), onMine, Modifier.weight(1f), strong = true)
                GhostButton(stringResource(R.string.att_mark_expected), onExpected, Modifier.weight(1f), strong = true)
            }
            PrimaryButton(stringResource(R.string.att_open_entity), onEntity)
        }
    }
}

@Preview(widthDp = 390, heightDp = 1200, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun AttentionPreview() {
    SotreusTheme { AttentionContent(AttentionUiState(false, FakeSotreusData.attentionDetail), {}, {}, {}, {}, {}) }
}
