package app.sotreus.feature.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.repository.PlaceRepository
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.navigation.PlaceMapRoute
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.sensing.LocationSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import javax.inject.Inject

data class PickerState(val name: String = "", val start: Pair<Double, Double>? = null, val zoom: Double = 15.5, val ready: Boolean = false)

@HiltViewModel
class PlaceMapPickerViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val places: PlaceRepository,
    private val location: LocationSource,
) : ViewModel() {
    private val id = handle.toRoute<PlaceMapRoute>().placeId
    val state = MutableStateFlow(PickerState())

    init {
        viewModelScope.launch {
            val p = places.observePlace(id).first()
            val saved = if (p?.lat != null && p.lon != null) p.lat!! to p.lon!! else null
            val start = saved ?: location.currentFix(8_000)?.let { it.lat to it.lon }
            state.value = PickerState(p?.name.orEmpty(), start ?: (20.0 to 0.0), if (start != null) 15.5 else 1.5, ready = true)
        }
    }

    suspend fun myLocation(): Pair<Double, Double>? = location.currentFix()?.let { it.lat to it.lon }

    fun save(lat: Double, lon: Double, done: () -> Unit) = viewModelScope.launch {
        places.setLocation(id, lat, lon)
        done()
    }
}

/** Map picker (not designed): a full map with a fixed pin, composed from existing parts. */
@Composable
internal fun PlaceMapPickerScreen(onBack: () -> Unit, vm: PlaceMapPickerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = SotreusTheme.colors
    var center by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = SotreusTheme.spacing.screenH, vertical = SotreusTheme.spacing.screenH), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
        BackTopBar(onBack = onBack)
        Text(stringResource(R.string.map_picker_title), style = SotreusTheme.typography.titleS, color = c.text)
        Text(stringResource(R.string.map_picker_lead, state.name), style = SotreusTheme.typography.bodyS, color = c.textMuted)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val start = state.start
            if (state.ready && start != null) {
                PlaceMap(
                    start.first, start.second, state.zoom, stringResource(R.string.map_a11y, state.name), Modifier.fillMaxSize(),
                    onCenterChanged = { lat, lon -> center = lat to lon },
                    onReady = { map = it; center = start },
                )
            }
        }
        Text(stringResource(R.string.map_attribution), style = SotreusTheme.typography.monoLabelS, color = c.textDim)
        center?.let { (lat, lon) -> MonoLabel(stringResource(R.string.loc_coords, formatCoord(lat), formatCoord(lon))) }
        Text(stringResource(R.string.map_note), style = SotreusTheme.typography.caption, color = c.textDim)
        GhostButton(
            stringResource(R.string.map_picker_center),
            {
                scope.launch {
                    vm.myLocation()?.let { (lat, lon) ->
                        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16.0))
                        center = lat to lon
                    }
                }
            },
            minHeight = SotreusTheme.sizes.minTouch,
            strong = true,
        )
        PrimaryButton(stringResource(R.string.map_picker_save), { center?.let { (lat, lon) -> vm.save(lat, lon, onBack) } }, enabled = center != null)
    }
}
