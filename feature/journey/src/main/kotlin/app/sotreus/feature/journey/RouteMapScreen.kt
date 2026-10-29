package app.sotreus.feature.journey

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.field.LivePathRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.navigation.RouteMapRoute
import app.sotreus.core.ui.*
import app.sotreus.core.database.entity.SessionEventEntity
import app.sotreus.core.model.SessionEventKind
import app.sotreus.intelligence.PathMarks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class RouteMapState(val points: List<Pair<Double, Double>> = emptyList(), val online: Boolean = false, val saved: Boolean = false, val marks: List<PathMarks.Placed<SessionEventEntity>> = emptyList(), val approximate: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RouteMapViewModel @Inject constructor(handle: SavedStateHandle, sessions: SessionDao, live: LivePathRepository, settings: SettingsRepository) : ViewModel() {
    private val id = handle.toRoute<RouteMapRoute>().sessionId
    private val session = if (id > 0) sessions.observe(id) else sessions.observeActive()
    private val stored = session.flatMapLatest { row -> if (row?.geotag == true) sessions.observeLocations(row.id) else flowOf(emptyList()) }
    private val events = session.flatMapLatest { row -> if (row != null) sessions.observeEvents(row.id) else flowOf(emptyList()) }
    val state = combine(session, stored, live.state, settings.settings, events) { row, locations, path, prefs, heard ->
        // An empty live path (no GPS yet) falls back to the session's saved route instead of showing nothing.
        val memory = path.enabled && (id <= 0 || path.sessionId == id) && row?.endedAtMs == null && (path.points.isNotEmpty() || row?.geotag != true)
        val accurate = locations.filter { it.accuracyM?.let { a -> a in 0f..ACCURATE_M } == true }
        // Indoors only network fixes may arrive; show them rather than nothing, labelled as approximate.
        val approximate = !memory && accurate.isEmpty() && locations.isNotEmpty()
        val timed = if (memory) path.points.map { PathMarks.PathPoint(it.at, it.lat, it.lon) } else (if (approximate) locations else accurate)
            .map { PathMarks.PathPoint(it.atMs, it.lat, it.lon) }
        val step = (timed.size / 2_000).coerceAtLeast(1)
        // Heard radios sit on this phone's path at the time they were heard; never on the radio.
        val marks = PathMarks.place(heard.filter { it.kind in MARKED }.map { it.atMs to it }, timed)
        RouteMapState(
            timed.filterIndexed { index, _ -> index % step == 0 || index == timed.lastIndex }.map { it.lat to it.lon },
            prefs.onlineRouteMaps, !memory && row?.geotag == true, marks, approximate,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteMapState())
}

@Composable
internal fun RouteMapScreen(onBack: () -> Unit, onTools: () -> Unit, vm: RouteMapViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.route_title), lead = stringResource(R.string.route_lead))
        if (state.points.isEmpty()) {
            CaveatBox(stringResource(R.string.route_empty_title), stringResource(R.string.route_empty_body))
        } else {
            // Same wording as the session timeline, so a mark reads like its timeline row.
            val marks = state.marks.map { RouteMark(it.lat, it.lon, timelineItem(it.item).text, it.item.kind == SessionEventKind.ATTENTION) }
            RouteMap(state.points, state.online, Modifier.fillMaxWidth().height(420.dp), marks)
            MonoLabel(stringResource(if (state.saved) R.string.route_saved else R.string.route_memory))
            if (state.approximate) MonoLabel(stringResource(R.string.route_approximate))
            if (state.marks.isNotEmpty()) MonoLabel(pluralStringResource(R.plurals.route_marks, state.marks.size, state.marks.size))
        }
        GhostButton(stringResource(R.string.route_tools), onTools)
    }
}

private val MARKED = setOf(SessionEventKind.NEW_FINGERPRINT, SessionEventKind.TAGGED_REENCOUNTER, SessionEventKind.FAMILY_SIGNATURE, SessionEventKind.ATTENTION)

/** Fixes this tight draw the route; looser ones are used only when nothing tighter was saved. */
private const val ACCURATE_M = 50f
