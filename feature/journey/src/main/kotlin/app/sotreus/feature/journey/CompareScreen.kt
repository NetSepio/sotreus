package app.sotreus.feature.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.crypto.Sha256
import app.sotreus.core.crypto.toHex
import app.sotreus.core.data.export.ExportService
import app.sotreus.core.data.repository.CompareRow
import app.sotreus.core.data.repository.CompareView
import app.sotreus.core.data.repository.SessionRepository
import app.sotreus.core.database.entity.SessionEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.PresenceState
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.UserEntityState
import app.sotreus.core.navigation.CompareRoute
import app.sotreus.core.navigation.EntityRoute
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CardStyle
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.CompactButton
import app.sotreus.core.ui.EntityRow
import app.sotreus.core.ui.InlineLink
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SotreusCard
import app.sotreus.core.ui.Stat
import app.sotreus.core.ui.StatGrid
import app.sotreus.core.ui.UnderlineTabs
import app.sotreus.core.ui.confidenceLabel
import app.sotreus.core.ui.dayLabel
import app.sotreus.core.ui.durationLabel
import app.sotreus.core.ui.entityTitle
import app.sotreus.core.ui.glyphFor
import app.sotreus.core.ui.shareFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import javax.inject.Inject
import app.sotreus.core.ui.R as UiR

enum class CompareTab { ONLY_B, ONLY_A, CHANGED }

data class CompareUiState(val sits: List<SessionEntity> = emptyList(), val a: Long? = null, val b: Long? = null, val view: CompareView? = null, val loading: Boolean = true)

@HiltViewModel
class CompareViewModel @Inject constructor(handle: SavedStateHandle, private val sessions: SessionRepository, private val exports: ExportService) : ViewModel() {
    private val route = handle.toRoute<CompareRoute>()
    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val sits = sessions.sits()
            val b = route.b.takeIf { it > 0 } ?: sits.getOrNull(0)?.id
            val bSit = sits.firstOrNull { it.id == b }
            val a = route.a.takeIf { it > 0 }
                ?: sits.firstOrNull { it.id != b && bSit?.placeId != null && it.placeId == bSit.placeId }?.id
                ?: sits.firstOrNull { it.id != b }?.id
            _state.value = CompareUiState(sits, a, b, loading = false)
            load()
        }
    }

    fun pickA(id: Long) { _state.value = _state.value.copy(a = id); viewModelScope.launch { load() } }
    fun pickB(id: Long) { _state.value = _state.value.copy(b = id); viewModelScope.launch { load() } }

    private suspend fun load() {
        val s = _state.value
        _state.value = s.copy(view = if (s.a != null && s.b != null && s.a != s.b) sessions.compare(s.a, s.b) else null)
    }

    /** Privacy-reduced comparison: per-export hashed identifiers, families and presence only. */
    fun export(onReady: (android.net.Uri) -> Unit) = viewModelScope.launch {
        val v = _state.value.view ?: return@launch
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        fun id(raw: String) = Sha256.hmac(salt, raw.toByteArray()).copyOf(8).toHex()
        fun rows(list: List<CompareRow>) = JsonArray(
            list.map { r -> buildJsonObject { put("id", id(r.entityId)); put("family", r.entity?.family?.name); put("presentMinutes", r.presentMs / 60_000); put("avgRssi", r.avgRssi) } },
        )
        val json = buildJsonObject {
            put("format", "sotreus-compare")
            put("privacyReduced", JsonPrimitive(true))
            put("note", "A radio present in one sit and not the other may simply have been off, asleep, or out of range.")
            put("inBoth", v.both)
            put("onlyInA", rows(v.onlyA))
            put("onlyInB", rows(v.onlyB))
            put("changed", rows(v.changed))
        }.toString()
        onReady(exports.writeText("sotreus-compare-${System.currentTimeMillis()}.json", json))
    }
}

/** Screen 12. Differences are evidence to weigh, not conclusions. */
@Composable
internal fun CompareScreen(navigate: (Any) -> Unit, onBack: () -> Unit, vm: CompareViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(UiR.string.export_share)
    var tab by remember { mutableStateOf(CompareTab.ONLY_B) }
    var shown by remember { mutableIntStateOf(4) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack) { if (state.view != null) CompactButton(stringResource(R.string.compare_export), { vm.export { shareFile(context, it, shareTitle) } }) }
        ScreenTitle(stringResource(R.string.compare_title))
        if (!state.loading && state.sits.size < 2) {
            CaveatBox(stringResource(R.string.compare_title), stringResource(R.string.compare_need_two))
            return@ScreenColumn
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            SitPicker(stringResource(R.string.compare_a), state.sits, state.a, vm::pickA, Modifier.weight(1f))
            SitPicker(stringResource(R.string.compare_b), state.sits, state.b, vm::pickB, Modifier.weight(1f))
        }
        val v = state.view ?: return@ScreenColumn
        StatGrid(
            listOf(
                Stat(v.both.toString(), stringResource(R.string.compare_both)),
                Stat(v.onlyA.size.toString(), stringResource(R.string.compare_only_a)),
                Stat(v.onlyB.size.toString(), stringResource(R.string.compare_only_b), attention = true),
            ),
            columns = 3,
        )
        UnderlineTabs(
            listOf(CompareTab.ONLY_B to stringResource(R.string.compare_only_b), CompareTab.ONLY_A to stringResource(R.string.compare_only_a), CompareTab.CHANGED to stringResource(R.string.compare_changed)),
            tab,
            { tab = it; shown = 4 },
        )
        val rows = when (tab) {
            CompareTab.ONLY_B -> v.onlyB
            CompareTab.ONLY_A -> v.onlyA
            CompareTab.CHANGED -> v.changed
        }
        val side = if (tab == CompareTab.ONLY_A) v.a else v.b
        val total = (side.endedAtMs ?: side.startedAtMs) - side.startedAtMs
        Column {
            if (rows.isEmpty()) Text(stringResource(R.string.compare_empty_tab), style = SotreusTheme.typography.bodyS, color = SotreusTheme.colors.textMuted)
            rows.take(shown).forEach { r ->
                val e = r.entity
                val kind = e?.radio ?: RadioKind.BLE
                val long = r.presentMs > total / 4
                val sub = when {
                    tab == CompareTab.CHANGED -> stringResource(R.string.compare_changed_row, durationLabel(r.otherPresentMs ?: 0), durationLabel(r.presentMs))
                    long -> stringResource(R.string.compare_present, durationLabel(r.presentMs), durationLabel(total)) + (e?.let { " · " + confidenceLabel(it.guessConfidence) } ?: "")
                    else -> stringResource(R.string.compare_passing, durationLabel(r.presentMs))
                }
                EntityRow(
                    title = e?.let { entityTitle(it.userName, it.advertisedName, it.radio, it.family) } ?: stringResource(UiR.string.entity_unnamed_advertiser),
                    subtitle = sub,
                    glyph = glyphFor(kind, e?.userState ?: UserEntityState.UNCLASSIFIED, if (tab == CompareTab.ONLY_B && long) PresenceState.NEW else PresenceState.FAMILIAR, false, false),
                    onClick = { navigate(EntityRoute(r.entityId)) },
                    wellGlyph = false,
                    trailingValue = r.avgRssi.toString().replace("-", "−"),
                )
            }
            if (rows.size > shown) InlineLink(stringResource(R.string.compare_show_more, rows.size - shown), { shown = rows.size })
        }
        CaveatBox(stringResource(R.string.compare_caveat_kicker), stringResource(R.string.compare_caveat_body))
    }
}

@Composable
private fun SitPicker(kicker: String, sits: List<SessionEntity>, selected: Long?, onPick: (Long) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val sit = sits.firstOrNull { it.id == selected }
    Box(modifier) {
        SotreusCard(Modifier.fillMaxHeight(), style = CardStyle.RAISED, onClick = { open = true }, padding = SotreusTheme.spacing.xl) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MonoLabel(kicker, small = true)
                Text(sit?.name ?: stringResource(R.string.compare_pick), style = SotreusTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = SotreusTheme.colors.text, maxLines = 2)
                sit?.let { Text("${dayLabel(it.startedAtMs)} · ${durationLabel((it.endedAtMs ?: it.startedAtMs) - it.startedAtMs)}", style = SotreusTheme.typography.caption, color = SotreusTheme.colors.textMuted) }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SotreusTheme.colors.surfaceRaised) {
            sits.forEach { s ->
                DropdownMenuItem(
                    text = { Text("${s.name} · ${dayLabel(s.startedAtMs)}", style = SotreusTheme.typography.body, color = if (s.id == selected) SotreusTheme.colors.accent else SotreusTheme.colors.text) },
                    onClick = { open = false; onPick(s.id) },
                )
            }
        }
    }
}

