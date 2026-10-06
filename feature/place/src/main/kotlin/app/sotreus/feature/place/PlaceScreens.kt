package app.sotreus.feature.place

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.repository.BaselineEntry
import app.sotreus.core.data.repository.DataControls
import app.sotreus.core.data.repository.PlaceBaselineView
import app.sotreus.core.data.repository.PlaceRepository
import app.sotreus.core.data.repository.PlaceSummary
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.navigation.EntityRoute
import app.sotreus.core.navigation.PlaceRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.CompactButton
import app.sotreus.core.ui.CompositionBar
import app.sotreus.core.ui.ConfirmDialog
import app.sotreus.core.ui.CountTriple
import app.sotreus.core.ui.DestructiveTextButton
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.InlineLink
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SectionHeader
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.SwitchRow
import app.sotreus.core.ui.TextInputDialog
import app.sotreus.core.ui.ValueRow
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.confidenceLabel
import app.sotreus.core.ui.dayLabel
import app.sotreus.core.ui.entityTitle
import app.sotreus.core.ui.familySignature
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.sotreus.core.ui.R as UiR

data class PlaceUiState(val loading: Boolean = true, val view: PlaceBaselineView? = null)

@HiltViewModel
class PlaceViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val places: PlaceRepository,
    private val controls: DataControls,
) : ViewModel() {
    private val id = handle.toRoute<PlaceRoute>().placeId
    val state: StateFlow<PlaceUiState> = places.observeBaseline(id).map { PlaceUiState(false, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaceUiState())

    fun rename(name: String) = viewModelScope.launch { places.rename(id, name) }
    fun keepLearning(on: Boolean) = viewModelScope.launch { places.setKeepLearning(id, on) }
    fun deleteHistory() = viewModelScope.launch { controls.deletePlaceHistory(id) }
    fun deletePlace(done: () -> Unit) = viewModelScope.launch {
        if (places.currentPlace()?.id == id) places.select(null)
        controls.deletePlace(id)
        done()
    }
}

@Composable
internal fun PlaceScreen(navigate: (Any) -> Unit, onBack: () -> Unit, vm: PlaceViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    PlaceContent(state, onBack, vm::rename, vm::keepLearning, vm::deleteHistory, { vm.deletePlace(onBack) }, { navigate(EntityRoute(it)) })
}

/** Screen 09. Baseline rules: normally present ≥ 70 % of visits, occasional 20–70 %. */
@Composable
internal fun PlaceContent(
    state: PlaceUiState,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onKeepLearning: (Boolean) -> Unit,
    onDeleteHistory: () -> Unit,
    onDeletePlace: () -> Unit,
    onEntity: (String) -> Unit,
) {
    val c = SotreusTheme.colors
    var renaming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(0) }
    var showAll by remember { mutableStateOf(false) }
    val v = state.view
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack) { if (v != null) CompactButton(stringResource(R.string.place_rename), { renaming = true }) }
        if (v == null) {
            if (!state.loading) CaveatBox(stringResource(R.string.place_kicker), stringResource(R.string.place_missing))
            return@ScreenColumn
        }
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            MonoLabel(stringResource(R.string.place_kicker))
            Text(v.place.name, style = SotreusTheme.typography.displayM, color = c.text)
            Text(
                if (v.completedVisits == 0) stringResource(R.string.place_learning_line)
                else pluralStringResource(R.plurals.place_baseline_from, v.completedVisits, v.completedVisits, "${dayLabel(v.place.updatedAtMs).lowercase()} ${clockTime(v.place.updatedAtMs)}"),
                style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f),
                color = c.textMuted,
            )
        }
        SwitchRow(stringResource(R.string.place_keep_learning), v.place.keepLearning, onKeepLearning, subtitle = stringResource(R.string.place_keep_learning_sub))
        val newCount = v.newThisVisit.size
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            MonoLabel(stringResource(R.string.place_this_visit))
            CompositionBar(v.thisVisitNormal, v.thisVisitOccasional, newCount, stringResource(R.string.place_comp_a11y, v.thisVisitNormal, v.thisVisitOccasional, newCount))
            CountTriple(
                listOf(
                    Triple(v.thisVisitNormal.toString(), stringResource(R.string.place_normal), false),
                    Triple(v.thisVisitOccasional.toString(), stringResource(R.string.place_occasional), false),
                    Triple(newCount.toString(), stringResource(R.string.place_new), true),
                ),
            )
        }
        val changes = v.newThisVisit.filter { it.entity?.notable == true || it.entity?.family?.attentionRelevant == true }.take(3)
        val missing = v.missing.take(3)
        if (changes.isNotEmpty() || missing.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                MonoLabel(stringResource(R.string.place_changes))
                changes.forEach { ch -> ChangeCard(ch, attention = true, completed = v.completedVisits, onClick = { onEntity(ch.entityId) }) }
                missing.forEach { ch -> ChangeCard(ch, attention = false, completed = v.completedVisits, onClick = { onEntity(ch.entityId) }) }
            }
        }
        if (v.normal.isNotEmpty()) {
            Column {
                SectionHeader(stringResource(R.string.place_normally_present, v.normal.size)) {
                    if (v.normal.size > 3) InlineLink(stringResource(if (showAll) R.string.place_show_fewer else R.string.place_see_all), { showAll = !showAll })
                }
                val list = if (showAll) v.normal else v.normal.take(3)
                list.forEachIndexed { i, n ->
                    ValueRow(
                        n.entity?.let { entityTitle(it.userName, it.advertisedName, it.radio, it.family) } ?: n.entityId,
                        stringResource(R.string.place_visits_value, n.visits, v.completedVisits),
                        showDivider = i < list.lastIndex,
                        onClick = { onEntity(n.entityId) },
                    )
                }
            }
        }
        CaveatBox(stringResource(R.string.place_caveat_kicker), stringResource(R.string.place_caveat_body))
        Spacer(Modifier.weight(1f))
        GhostButton(stringResource(R.string.place_delete), { confirm = 1 })
        DestructiveTextButton(stringResource(R.string.place_delete_place), { confirm = 2 })
    }
    if (renaming && v != null) {
        TextInputDialog(stringResource(R.string.place_rename_title), v.place.name, "", stringResource(UiR.string.core_ui_save), stringResource(UiR.string.core_ui_cancel), { renaming = false; onRename(it) }, { renaming = false })
    }
    if (confirm == 1 && v != null) {
        ConfirmDialog(stringResource(R.string.place_delete_title, v.place.name), stringResource(R.string.place_delete_body), stringResource(UiR.string.core_ui_delete), stringResource(UiR.string.core_ui_cancel), { confirm = 0; onDeleteHistory() }, { confirm = 0 })
    }
    if (confirm == 2 && v != null) {
        ConfirmDialog(stringResource(R.string.place_delete_place_title, v.place.name), stringResource(R.string.place_delete_place_body), stringResource(UiR.string.core_ui_delete), stringResource(UiR.string.core_ui_cancel), { confirm = 0; onDeletePlace() }, { confirm = 0 })
    }
}

@Composable
private fun ChangeCard(entry: BaselineEntry, attention: Boolean, completed: Int, onClick: () -> Unit) {
    val c = SotreusTheme.colors
    val e = entry.entity
    val name = e?.let { entityTitle(it.userName, it.advertisedName, it.radio, it.family) } ?: entry.entityId
    val title = when {
        !attention -> stringResource(R.string.place_change_missing, name)
        e?.family != null -> stringResource(R.string.place_change_new_plain, familySignature(e.family!!))
        else -> stringResource(R.string.place_change_new_plain, name)
    }
    val sub = if (attention) stringResource(R.string.place_change_new_sub, confidenceLabel(e?.guessConfidence ?: app.sotreus.core.model.Confidence.LOW)) else stringResource(R.string.place_change_missing_sub, entry.visits, completed)
    app.sotreus.core.ui.SotreusCard(style = if (attention) app.sotreus.core.ui.CardStyle.ATTENTION else app.sotreus.core.ui.CardStyle.RAISED, onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
            Box(
                Modifier.padding(top = 5.dp).size(8.dp).then(
                    if (attention) Modifier.background(c.accent, SotreusTheme.shapes.pill) else Modifier.border(1.5.dp, c.textMuted, SotreusTheme.shapes.pill),
                ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f), color = c.text)
                Text(sub, style = SotreusTheme.typography.caption, color = c.textMuted)
            }
        }
    }
}

// --- Places list ---------------------------------------------------------------------------

data class PlacesUiState(val places: List<PlaceSummary> = emptyList(), val currentId: Long? = null)

@HiltViewModel
class PlacesViewModel @Inject constructor(private val places: PlaceRepository, settings: SettingsRepository) : ViewModel() {
    val state: StateFlow<PlacesUiState> = combine(places.observePlaces(), settings.settings) { p, s -> PlacesUiState(p, s.currentPlaceId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacesUiState())

    fun create(name: String) = viewModelScope.launch { places.create(name, select = false) }
}

/** Places list (not designed): rows and a create dialog. */
@Composable
internal fun PlacesScreen(navigate: (Any) -> Unit, onBack: () -> Unit, vm: PlacesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack) { CompactButton(stringResource(R.string.places_new), { creating = true }) }
        ScreenTitle(stringResource(R.string.places_title), lead = stringResource(R.string.places_lead))
        if (state.places.isEmpty()) CaveatBox(stringResource(R.string.places_kicker), stringResource(R.string.places_empty))
        Column {
            state.places.forEach { p ->
                InfoRow(p.place.name, subtitle = pluralStringResource(R.plurals.places_visits, p.visits, p.visits), onClick = { navigate(PlaceRoute(p.place.id)) }) {
                    if (p.place.id == state.currentId) StateChip(stringResource(R.string.places_current), ChipTone.ACCENT_FILLED)
                }
            }
        }
    }
    if (creating) {
        TextInputDialog(stringResource(R.string.places_new), "", stringResource(R.string.places_new_hint), stringResource(UiR.string.core_ui_save), stringResource(UiR.string.core_ui_cancel), { creating = false; vm.create(it) }, { creating = false })
    }
}

@Preview(widthDp = 390, heightDp = 1100, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun PlacePreview() {
    val f = FakeSotreusData
    val view = PlaceBaselineView(
        place = f.office,
        completedVisits = 14,
        normal = listOf(BaselineEntry(f.nsOffice, f.nsOffice.id, 14), BaselineEntry(null, "Meeting-room display", 14), BaselineEntry(null, "Door access reader", 12)),
        occasional = 8,
        newThisVisit = listOf(BaselineEntry(f.camera, f.camera.id, 0), BaselineEntry(f.advertiser, f.advertiser.id, 0), BaselineEntry(f.earbuds, f.earbuds.id, 0)),
        missing = listOf(BaselineEntry(f.entities[2].copy(advertisedName = "NS-Guest", userState = app.sotreus.core.model.UserEntityState.UNCLASSIFIED), "ns-guest", 13)),
        thisVisitNormal = 31,
        thisVisitOccasional = 8,
    )
    SotreusTheme { PlaceContent(PlaceUiState(false, view), {}, {}, {}, {}, {}, {}) }
}

