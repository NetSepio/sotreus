package app.sotreus.feature.journey

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class RouteMapState(val points: List<Pair<Double, Double>> = emptyList(), val online: Boolean = false, val saved: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RouteMapViewModel @Inject constructor(handle: SavedStateHandle, sessions: SessionDao, live: LivePathRepository, settings: SettingsRepository) : ViewModel() {
    private val id = handle.toRoute<RouteMapRoute>().sessionId
    private val session = if (id > 0) sessions.observe(id) else sessions.observeActive()
    private val stored = session.flatMapLatest { row -> if (row?.geotag == true) sessions.observeLocations(row.id) else flowOf(emptyList()) }
    val state = combine(session, stored, live.state, settings.settings) { row, locations, path, prefs ->
        val memory = path.enabled && (id <= 0 || path.sessionId == id) && row?.endedAtMs == null
        val data = if (memory) path.points.map { it.lat to it.lon } else locations
            .filter { it.accuracyM?.let { a -> a in 0f..50f } == true }.map { it.lat to it.lon }
        val step = (data.size / 2_000).coerceAtLeast(1)
        RouteMapState(data.filterIndexed { index, _ -> index % step == 0 || index == data.lastIndex }, prefs.onlineRouteMaps, !memory && row?.geotag == true)
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
            RouteMap(state.points, state.online, Modifier.fillMaxWidth().height(420.dp))
            MonoLabel(stringResource(if (state.saved) R.string.route_saved else R.string.route_memory))
        }
        GhostButton(stringResource(R.string.route_tools), onTools)
    }
}
