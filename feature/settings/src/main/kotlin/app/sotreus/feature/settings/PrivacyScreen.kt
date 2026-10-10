package app.sotreus.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.device.DeviceProfileRepository
import app.sotreus.core.data.repository.DataControls
import app.sotreus.core.data.repository.PlaceRepository
import app.sotreus.core.data.repository.PlaceSummary
import app.sotreus.core.data.repository.SessionListItem
import app.sotreus.core.data.repository.SessionRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.GeotagMode
import app.sotreus.core.model.RetentionPolicy
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.ConfirmDialog
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.NavRow
import app.sotreus.core.ui.RadioGroupRows
import app.sotreus.core.ui.RadioOption
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.Stat
import app.sotreus.core.ui.StatGrid
import app.sotreus.core.ui.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrivacyUiState(
    val settings: SotreusSettings = SotreusSettings(),
    val sessions: List<SessionListItem> = emptyList(),
    val places: List<PlaceSummary> = emptyList(),
    val solana: Boolean = false,
)

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val settings: SettingsRepository,
    sessions: SessionRepository,
    places: PlaceRepository,
    device: DeviceProfileRepository,
    private val controls: DataControls,
) : ViewModel() {
    val state: StateFlow<PrivacyUiState> = combine(settings.settings, sessions.observeList(), places.observePlaces(), device.capabilities) { s, ss, p, caps ->
        PrivacyUiState(s, ss.filter { it.session.endedAtMs != null }, p, caps.onChainProofStamping)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacyUiState())

    fun retention(r: RetentionPolicy) = viewModelScope.launch {
        settings.setRetention(r)
        controls.applyRetention(r)
    }
    fun mask(on: Boolean) = viewModelScope.launch { settings.setMaskCoordinates(on) }
    fun placeByLocation(on: Boolean) = viewModelScope.launch { settings.setPlaceByLocation(on) }
    fun plusCodeTags(on: Boolean) = viewModelScope.launch { settings.setPlusCodeTags(on) }
    fun geotag(m: GeotagMode) = viewModelScope.launch { settings.setGeotagMode(m) }
    fun deleteSession(id: Long) = viewModelScope.launch { controls.deleteSession(id) }
    fun deletePlace(id: Long) = viewModelScope.launch { controls.deletePlaceHistory(id) }
    fun deletePresence() = viewModelScope.launch { controls.deletePresenceKeys() }
    fun deleteAll() = viewModelScope.launch { controls.deleteAllObservations() }
    fun clearPending() = viewModelScope.launch { controls.clearPendingProofs() }
}

private sealed interface Pending {
    data object PickSession : Pending
    data object PickPlace : Pending
    data object GeotagChoice : Pending
    data class Session(val item: SessionListItem) : Pending
    data class Place(val place: PlaceSummary) : Pending
    data object Presence : Pending
    data object All : Pending
    data object Proofs : Pending
}

/** Screen 14. Every delete has a confirm dialog that says exactly what is removed. */
@Composable
internal fun PrivacyScreen(onBack: () -> Unit, vm: PrivacyViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Pending?>(null) }
    val c = SotreusTheme.colors
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.privacy_title), lead = stringResource(R.string.privacy_lead))
        Column {
            MonoLabel(stringResource(R.string.keep_observations), Modifier)
            RadioGroupRows(
                listOf(
                    RadioOption(RetentionPolicy.KEEP_ALL, stringResource(R.string.keep_everything)),
                    RadioOption(RetentionPolicy.KEEP_30_DAYS, stringResource(R.string.keep_30)),
                    RadioOption(RetentionPolicy.KEEP_TAGGED_EXPIRE_REST, stringResource(R.string.keep_tagged)),
                ),
                state.settings.retention,
                vm::retention,
            )
        }
        Column {
            MonoLabel(stringResource(R.string.location_group))
            SwitchRow(stringResource(R.string.mask_coords), state.settings.maskCoordinates, vm::mask, subtitle = stringResource(R.string.mask_coords_sub), bordered = false)
            RowDivider()
            SwitchRow(
                stringResource(R.string.privacy_place_by_location), state.settings.placeByLocation, vm::placeByLocation,
                subtitle = stringResource(R.string.privacy_place_by_location_sub), bordered = false,
            )
            RowDivider()
            SwitchRow(
                stringResource(R.string.privacy_plus_codes), state.settings.plusCodeTags, vm::plusCodeTags,
                subtitle = stringResource(R.string.privacy_plus_codes_sub), bordered = false,
            )
            RowDivider()
            NavRow(stringResource(R.string.geotag_sessions), { pending = Pending.GeotagChoice }, value = geotagLabel(state.settings.geotagMode), showDivider = false)
        }
        Column {
            MonoLabel(stringResource(R.string.export_defaults))
            StatGrid(
                listOf(
                    Stat(stringResource(R.string.ed_coarse), "", stringResource(R.string.ed_coordinates)),
                    Stat(stringResource(R.string.ed_hashed), "", stringResource(R.string.ed_identifiers)),
                    Stat(stringResource(R.string.ed_excluded), "", stringResource(R.string.ed_notes)),
                    Stat(stringResource(R.string.ed_buckets), "", stringResource(R.string.ed_time)),
                ),
                columns = 2,
            )
        }
        Column {
            MonoLabel(stringResource(R.string.clear_data))
            NavRow(stringResource(R.string.delete_session), { pending = Pending.PickSession })
            NavRow(stringResource(R.string.delete_place), { pending = Pending.PickPlace })
            NavRow(stringResource(R.string.delete_presence), { pending = Pending.Presence })
            if (state.solana) NavRow(stringResource(R.string.clear_pending), { pending = Pending.Proofs })
            NavRow(stringResource(R.string.delete_all), { pending = Pending.All }, showDivider = false, destructive = true)
            if (state.solana) Text(stringResource(R.string.solana_note), style = SotreusTheme.typography.caption, color = c.textMuted)
        }
    }
    val cancel = stringResource(R.string.cancel)
    val delete = stringResource(app.sotreus.core.ui.R.string.core_ui_delete)
    when (val p = pending) {
        Pending.PickSession -> PickerDialog(stringResource(R.string.pick_session), state.sessions.map { it.session.name to { pending = Pending.Session(it) } }) { pending = null }
        Pending.PickPlace -> PickerDialog(stringResource(R.string.pick_place), state.places.map { it.place.name to { pending = Pending.Place(it) } }) { pending = null }
        Pending.GeotagChoice -> PickerDialog(
            stringResource(R.string.geotag_dialog_title),
            listOf(GeotagMode.ASK_EACH_TIME, GeotagMode.ALWAYS, GeotagMode.NEVER).map { m -> geotagLabel(m) to { vm.geotag(m); pending = null } },
        ) { pending = null }
        is Pending.Session -> ConfirmDialog(stringResource(R.string.confirm_session_title, p.item.session.name), stringResource(R.string.confirm_session_body), delete, cancel, { vm.deleteSession(p.item.session.id); pending = null }, { pending = null })
        is Pending.Place -> ConfirmDialog(stringResource(R.string.confirm_place_title, p.place.place.name), stringResource(R.string.confirm_place_body), delete, cancel, { vm.deletePlace(p.place.place.id); pending = null }, { pending = null })
        Pending.Presence -> ConfirmDialog(stringResource(R.string.confirm_presence_title), stringResource(R.string.confirm_presence_body), delete, cancel, { vm.deletePresence(); pending = null }, { pending = null })
        Pending.All -> ConfirmDialog(stringResource(R.string.confirm_all_title), stringResource(R.string.confirm_all_body), delete, cancel, { vm.deleteAll(); pending = null }, { pending = null })
        Pending.Proofs -> ConfirmDialog(stringResource(R.string.confirm_pending_title), stringResource(R.string.confirm_pending_body), delete, cancel, { vm.clearPending(); pending = null }, { pending = null })
        null -> Unit
    }
}

@Composable
private fun geotagLabel(m: GeotagMode) = stringResource(
    when (m) {
        GeotagMode.ASK_EACH_TIME -> R.string.geotag_ask
        GeotagMode.ALWAYS -> R.string.geotag_always
        GeotagMode.NEVER -> R.string.geotag_never
    },
)

@Composable
private fun PickerDialog(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val c = SotreusTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        shape = SotreusTheme.shapes.featureCard,
        title = { Text(title, style = SotreusTheme.typography.titleS.copy(fontSize = SotreusTheme.typography.titleS.fontSize * 0.8f), color = c.text) },
        text = {
            Column {
                if (options.isEmpty()) Text(stringResource(R.string.nothing_to_delete), style = SotreusTheme.typography.bodyS, color = c.textMuted)
                options.forEach { (label, action) -> InfoRow(label, onClick = action, titleStyleBody = true) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), style = SotreusTheme.typography.button.copy(fontWeight = FontWeight.Medium), color = c.textMuted) }
        },
    )
}

