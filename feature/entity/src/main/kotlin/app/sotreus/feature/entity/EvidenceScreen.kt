package app.sotreus.feature.entity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.repository.EntityRepository
import app.sotreus.core.data.repository.PlaceRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.entity.ObservationEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.navigation.EvidenceRoute
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.dayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class EvidenceUiState(val rows: List<ObservationEntity> = emptyList(), val places: Map<Long, String> = emptyMap(), val mask: Boolean = true)

@HiltViewModel
class EvidenceViewModel @Inject constructor(handle: SavedStateHandle, entities: EntityRepository, places: PlaceRepository, settings: SettingsRepository) : ViewModel() {
    private val id = handle.toRoute<EvidenceRoute>().entityId
    val state: StateFlow<EvidenceUiState> = combine(entities.observeEvidence(id), places.observePlaces(), settings.settings) { rows, p, s ->
        EvidenceUiState(rows, p.associate { it.place.id to it.place.name }, s.maskCoordinates)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EvidenceUiState())
}

/** Raw evidence list (not designed): every stored observation, composed from rows. */
@Composable
internal fun EvidenceScreen(onBack: () -> Unit, vm: EvidenceViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.evidence_title), lead = stringResource(R.string.evidence_lead))
        androidx.compose.foundation.layout.Column {
            MonoLabel("${state.rows.size}")
            state.rows.forEach { o ->
                val place = o.placeId?.let(state.places::get) ?: stringResource(R.string.evidence_unsaved)
                val coords = when {
                    o.lat == null && o.lon == null && o.plusCode == null -> null
                    state.mask -> stringResource(R.string.evidence_coords_masked)
                    o.lat != null && o.lon != null -> stringResource(R.string.evidence_coords, "%.5f, %.5f".format(o.lat, o.lon))
                    else -> o.plusCode?.let { stringResource(R.string.evidence_coords, it) }
                }
                InfoRow(
                    title = "${dayLabel(o.observedAtMs)} ${clockTime(o.observedAtMs)} · $place",
                    subtitle = listOfNotNull(stringResource(R.string.evidence_row, o.rssi, o.radio.name).replace("-", "−"), coords).joinToString("\n"),
                )
            }
        }
    }
}
