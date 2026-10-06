package app.sotreus.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.repository.AttentionListItem
import app.sotreus.core.data.repository.AttentionRepository
import app.sotreus.core.data.repository.EntityRepository
import app.sotreus.core.data.repository.HistoryEntity
import app.sotreus.core.data.repository.PlaceRepository
import app.sotreus.core.data.repository.PlaceSummary
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.EncounterRow
import app.sotreus.core.database.entity.EntityEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.PresenceState
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.RetentionPolicy
import app.sotreus.core.model.UserEntityState
import app.sotreus.core.navigation.AttentionRoute
import app.sotreus.core.navigation.EntityRoute
import app.sotreus.core.navigation.PlaceRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.EntityRow
import app.sotreus.core.ui.FilterChipRow
import app.sotreus.core.ui.FilterOption
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SearchField
import app.sotreus.core.ui.UnderlineTabs
import app.sotreus.core.ui.ageShort
import app.sotreus.core.ui.attentionHeadline
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.dateShort
import app.sotreus.core.ui.dayLabel
import app.sotreus.core.ui.durationLabel
import app.sotreus.core.ui.entityTitle
import app.sotreus.core.ui.familyName
import app.sotreus.core.ui.glyphFor
import app.sotreus.core.ui.isSameDay
import app.sotreus.core.ui.userLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import app.sotreus.core.ui.R as UiR

enum class HistoryTab { ENTITIES, ENCOUNTERS, PLACES, ATTENTION }

enum class HistoryFilter { ALL, TAGGED, MINE, WATCH }

enum class HistorySort { ENCOUNTERS, RECENT, NAME }

data class HistoryUiState(
    val tab: HistoryTab = HistoryTab.ENTITIES,
    val query: String = "",
    val filter: HistoryFilter = HistoryFilter.ALL,
    val sort: HistorySort = HistorySort.ENCOUNTERS,
    val entities: List<HistoryEntity> = emptyList(),
    val encounters: List<EncounterRow> = emptyList(),
    val entityNames: Map<String, EntityEntity> = emptyMap(),
    val places: List<PlaceSummary> = emptyList(),
    val attention: List<AttentionListItem> = emptyList(),
    val retention: RetentionPolicy = RetentionPolicy.KEEP_30_DAYS,
    val now: Long = System.currentTimeMillis(),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    entities: EntityRepository,
    places: PlaceRepository,
    attention: AttentionRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(HistoryUiState())

    val state: StateFlow<HistoryUiState> = combine(
        ui,
        entities.observeHistory(),
        entities.observeEncounters(),
        combine(places.observePlaces(), attention.observeAll()) { p, a -> p to a },
        settings.settings,
    ) { u, ents, enc, (pl, att), s ->
        u.copy(
            entities = ents, encounters = enc, entityNames = ents.associate { it.entity.id to it.entity }, places = pl, attention = att,
            retention = s.retention, now = System.currentTimeMillis(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun tab(t: HistoryTab) { ui.value = ui.value.copy(tab = t) }
    fun query(q: String) { ui.value = ui.value.copy(query = q) }
    fun filter(f: HistoryFilter) { ui.value = ui.value.copy(filter = f) }
    fun sort(s: HistorySort) { ui.value = ui.value.copy(sort = s) }
}

@Composable
internal fun HistoryScreen(navigate: (Any) -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    HistoryContent(state, vm::tab, vm::query, vm::filter, vm::sort, navigate)
}

@Composable
internal fun HistoryContent(
    state: HistoryUiState,
    onTab: (HistoryTab) -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (HistoryFilter) -> Unit,
    onSort: (HistorySort) -> Unit,
    navigate: (Any) -> Unit,
) {
    ScreenColumn {
        ScreenTitle(stringResource(R.string.feature_history_title))
        UnderlineTabs(
            listOf(
                HistoryTab.ENTITIES to stringResource(R.string.tab_entities),
                HistoryTab.ENCOUNTERS to stringResource(R.string.tab_encounters),
                HistoryTab.PLACES to stringResource(R.string.tab_places),
                HistoryTab.ATTENTION to stringResource(R.string.tab_attention),
            ),
            state.tab,
            onTab,
        )
        when (state.tab) {
            HistoryTab.ENTITIES -> EntitiesTab(state, onQuery, onFilter, onSort, navigate)
            HistoryTab.ENCOUNTERS -> EncountersTab(state, navigate)
            HistoryTab.PLACES -> PlacesTab(state, navigate)
            HistoryTab.ATTENTION -> AttentionTab(state, navigate)
        }
    }
}

@Composable
private fun EntitiesTab(state: HistoryUiState, onQuery: (String) -> Unit, onFilter: (HistoryFilter) -> Unit, onSort: (HistorySort) -> Unit, navigate: (Any) -> Unit) {
    val all = state.entities
    SearchField(state.query, onQuery, stringResource(R.string.search_hint), stringResource(R.string.search_label))
    FilterChipRow(
        listOf(
            FilterOption(HistoryFilter.ALL, stringResource(R.string.hfilter_all, "%,d".format(all.size))),
            FilterOption(HistoryFilter.TAGGED, stringResource(R.string.hfilter_tagged, all.count { it.entity.userState == UserEntityState.TAGGED })),
            FilterOption(HistoryFilter.MINE, stringResource(R.string.hfilter_mine, all.count { it.entity.userState == UserEntityState.MINE })),
            FilterOption(HistoryFilter.WATCH, stringResource(R.string.hfilter_watch, all.count { it.entity.userState == UserEntityState.WATCH })),
        ),
        state.filter,
        onFilter,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { HistorySortMenu(state.sort, onSort) }
        MonoLabel(
            stringResource(
                when (state.retention) {
                    RetentionPolicy.KEEP_ALL -> R.string.retention_keep_all
                    RetentionPolicy.KEEP_30_DAYS -> R.string.retention_keep_30
                    RetentionPolicy.KEEP_TAGGED_EXPIRE_REST -> R.string.retention_keep_tagged
                },
            ),
            small = true,
        )
    }
    val rows = rowsFor(state)
    if (rows.isEmpty()) {
        CaveatBox(stringResource(R.string.h_empty_kicker), stringResource(R.string.h_empty_body))
        return
    }
    Column {
        RowDivider(strong = true)
        rows.take(500).forEach { h ->
            val e = h.entity
            val kind = e.family?.let { familyName(it) } ?: stringResource(if (e.radio == RadioKind.WIFI) UiR.string.entity_wifi_short else UiR.string.entity_ble_short)
            val where = when {
                h.places.size >= 4 -> stringResource(R.string.row_everywhere)
                h.places.isEmpty() -> stringResource(R.string.row_no_places)
                else -> h.places.joinToString(", ")
            }
            val subtitle = listOfNotNull(kind, e.userState.takeIf { it != UserEntityState.UNCLASSIFIED }?.let { userLabel(it) }, where).joinToString(" · ")
            val age = state.now - e.lastSeenMs
            EntityRow(
                title = entityTitle(e.userName, e.advertisedName, e.radio, e.family),
                subtitle = subtitle,
                glyph = glyphFor(e.radio, e.userState, if (e.notable) PresenceState.NEW else PresenceState.FAMILIAR, false, false),
                onClick = { navigate(EntityRoute(e.id)) },
                wellGlyph = false,
                trailingValue = h.encounters.toString(),
                trailingValueStrong = true,
                trailingCaption = when {
                    age < 5_000 -> stringResource(UiR.string.now)
                    age < 3_600_000 -> ageShort(age)
                    else -> dayLabel(e.lastSeenMs, state.now)
                },
            )
        }
    }
}

private fun rowsFor(state: HistoryUiState): List<HistoryEntity> {
    val q = state.query.trim().lowercase()
    return state.entities
        .filter {
            when (state.filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.TAGGED -> it.entity.userState == UserEntityState.TAGGED
                HistoryFilter.MINE -> it.entity.userState == UserEntityState.MINE
                HistoryFilter.WATCH -> it.entity.userState == UserEntityState.WATCH
            }
        }
        .filter { h ->
            q.isEmpty() || listOfNotNull(h.entity.userName, h.entity.advertisedName, h.entity.family?.name, h.entity.signatureName, h.entity.note)
                .any { it.lowercase().contains(q) }
        }
        .let { list ->
            when (state.sort) {
                HistorySort.ENCOUNTERS -> list.sortedByDescending { it.encounters }
                HistorySort.RECENT -> list.sortedByDescending { it.entity.lastSeenMs }
                HistorySort.NAME -> list.sortedBy { (it.entity.userName ?: it.entity.advertisedName ?: "￿").lowercase() }
            }
        }
}

@Composable
private fun HistorySortMenu(sort: HistorySort, onSort: (HistorySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.heightIn(min = SotreusTheme.sizes.minTouch).clickable(role = Role.DropdownList) { open = true }, contentAlignment = Alignment.CenterStart) {
            MonoLabel(
                stringResource(
                    when (sort) {
                        HistorySort.ENCOUNTERS -> R.string.hsort_encounters
                        HistorySort.RECENT -> R.string.hsort_recent
                        HistorySort.NAME -> R.string.hsort_name
                    },
                ),
                small = true,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SotreusTheme.colors.surfaceRaised) {
            listOf(HistorySort.ENCOUNTERS to R.string.hsort_menu_encounters, HistorySort.RECENT to R.string.hsort_menu_recent, HistorySort.NAME to R.string.hsort_menu_name).forEach { (s, l) ->
                DropdownMenuItem(text = { Text(stringResource(l), style = SotreusTheme.typography.body) }, onClick = { onSort(s); open = false })
            }
        }
    }
}

@Composable
private fun EncountersTab(state: HistoryUiState, navigate: (Any) -> Unit) {
    if (state.encounters.isEmpty()) {
        CaveatBox(stringResource(R.string.h_empty_kicker), stringResource(R.string.enc_empty_body))
        return
    }
    Column {
        state.encounters.forEach { enc ->
            val e = state.entityNames[enc.entityId]
            val title = e?.let { entityTitle(it.userName, it.advertisedName, it.radio, it.family) } ?: enc.entityId
            val whenText = if (isSameDay(enc.startedAtMs, state.now)) clockTime(enc.startedAtMs) else "${dayLabel(enc.startedAtMs, state.now)} ${clockTime(enc.startedAtMs)}"
            InfoRow(
                title = title,
                subtitle = stringResource(R.string.enc_row, enc.placeName ?: stringResource(R.string.enc_unsaved), whenText, durationLabel(enc.endedAtMs - enc.startedAtMs)),
                onClick = { navigate(EntityRoute(enc.entityId)) },
            ) { Text("${enc.maxRssi}".replace("-", "−"), style = SotreusTheme.typography.monoValue, color = SotreusTheme.colors.textMuted) }
        }
    }
}

@Composable
private fun PlacesTab(state: HistoryUiState, navigate: (Any) -> Unit) {
    if (state.places.isEmpty()) {
        CaveatBox(stringResource(R.string.h_empty_kicker), stringResource(R.string.places_empty_body))
        return
    }
    Column {
        state.places.forEach { p ->
            InfoRow(
                title = p.place.name,
                subtitle = stringResource(R.string.place_row_sub, pluralStringResource(R.plurals.place_visits, p.visits, p.visits), dateShort(p.place.createdAtMs)),
                onClick = { navigate(PlaceRoute(p.place.id)) },
            )
        }
    }
}

@Composable
private fun AttentionTab(state: HistoryUiState, navigate: (Any) -> Unit) {
    if (state.attention.isEmpty()) {
        CaveatBox(stringResource(R.string.h_empty_kicker), stringResource(R.string.att_empty_body))
        return
    }
    Column {
        state.attention.forEach { a ->
            val e = a.entity
            InfoRow(
                title = attentionHeadline(a.headline, e?.family),
                subtitle = stringResource(
                    R.string.att_row_sub,
                    e?.let { entityTitle(it.userName, it.advertisedName, it.radio, it.family) } ?: "",
                    "${dayLabel(a.event.updatedAtMs, state.now)} ${clockTime(a.event.updatedAtMs)}",
                    "%.2f".format(a.event.score),
                ),
                onClick = { navigate(AttentionRoute(a.event.id)) },
            )
        }
    }
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun HistoryPreview() {
    val ents = FakeSotreusData.entities.mapIndexed { i, e -> HistoryEntity(e, listOf(9, 3, 14, 5, 1, 3, 212)[i], listOf("Home", "Office", "Café").take(i % 3 + 1)) }
    SotreusTheme { HistoryContent(HistoryUiState(entities = ents, now = FakeSotreusData.NOW), {}, {}, {}, {}, {}) }
}
