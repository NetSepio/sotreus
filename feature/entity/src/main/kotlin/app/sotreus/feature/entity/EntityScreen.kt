package app.sotreus.feature.entity

import androidx.compose.ui.unit.dp
import app.sotreus.core.ui.CompactButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.device.DeviceProfileRepository
import app.sotreus.core.data.export.ExportOptions
import app.sotreus.core.data.export.ExportService
import app.sotreus.core.data.repository.EntityDetail
import app.sotreus.core.data.repository.EntityRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.entity.EntityEntity
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.BleAddressType
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.UserEntityState
import app.sotreus.core.navigation.EntityRoute
import app.sotreus.core.navigation.EvidenceRoute
import app.sotreus.core.navigation.ProximityRoute
import app.sotreus.core.navigation.ReceiptRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.AutoSizeText
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CardStyle
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.ExportChoice
import app.sotreus.core.ui.ExportSheet
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.KeyValueTable
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.NoteField
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.SelectChip
import app.sotreus.core.ui.SotreusCard
import app.sotreus.core.ui.Stat
import app.sotreus.core.ui.StatGrid
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.TextInputDialog
import app.sotreus.core.ui.ValueRow
import app.sotreus.core.ui.ageAgo
import app.sotreus.core.ui.confidenceLabel
import app.sotreus.core.ui.dateShort
import app.sotreus.core.ui.dayLabel
import app.sotreus.core.ui.entityTitle
import app.sotreus.core.ui.familySignature
import app.sotreus.core.ui.isSameDay
import app.sotreus.core.ui.shareFile
import app.sotreus.core.ui.userLabel
import app.sotreus.intelligence.Fingerprint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.sotreus.core.ui.R as UiR

data class EntityUiState(
    val loading: Boolean = true,
    val detail: EntityDetail? = null,
    val avgRssi30: Int? = null,
    val now: Long = System.currentTimeMillis(),
    val staleHoldSeconds: Int = 30,
    val maskCoordinates: Boolean = true,
    val proofsEnabled: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class EntityViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repository: EntityRepository,
    private val exports: ExportService,
    settings: SettingsRepository,
    device: DeviceProfileRepository,
) : ViewModel() {
    val entityId: String = handle.toRoute<EntityRoute>().entityId
    private val note = MutableStateFlow<String?>(null)
    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }

    val state: StateFlow<EntityUiState> = combine(
        repository.observeDetail(entityId),
        ticker,
        settings.settings,
        device.capabilities,
    ) { detail, now, s, caps ->
        EntityUiState(
            loading = false,
            detail = detail,
            avgRssi30 = repository.liveSighting(entityId)?.averageRssi(30_000, now)?.toInt(),
            now = now,
            staleHoldSeconds = s.staleHoldSeconds,
            maskCoordinates = s.maskCoordinates,
            proofsEnabled = caps.onChainProofStamping,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntityUiState())

    init {
        viewModelScope.launch {
            note.drop(1).debounce(600).collect { text -> if (text != null) repository.setNote(entityId, text) }
        }
    }

    fun setLabel(state: UserEntityState) = viewModelScope.launch { repository.setLabel(entityId, state) }
    fun rename(name: String) = viewModelScope.launch { repository.rename(entityId, name) }
    fun editNote(text: String) { note.value = text }

    fun export(choice: ExportChoice, onReady: (android.net.Uri) -> Unit) = viewModelScope.launch {
        exports.exportEntity(entityId, choice.toOptions())?.let(onReady)
    }
}

internal fun ExportChoice.toOptions() = ExportOptions(
    coordinates = if (exactCoordinates) ExportOptions.Coordinates.EXACT else ExportOptions.Coordinates.COARSE,
    hashIdentifiers = !rawIdentifiers,
    includeNotes = includeNotes,
    timeBucketMinutes = if (exactTimes) 0 else 15,
)

@Composable
internal fun EntityScreen(navigate: (Any) -> Unit, onBack: () -> Unit, vm: EntityViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    val shareTitle = stringResource(UiR.string.export_share)
    EntityContent(
        state = state,
        onBack = onBack,
        onLabel = vm::setLabel,
        onRename = vm::rename,
        onNote = vm::editNote,
        onEvidence = { navigate(EvidenceRoute(vm.entityId)) },
        onProximity = { navigate(ProximityRoute(vm.entityId)) },
        onExport = { exporting = true },
        onBatch = { navigate(ReceiptRoute(it)) },
    )
    if (exporting) {
        ExportSheet(
            onExport = { choice -> exporting = false; vm.export(choice) { shareFile(context, it, shareTitle) } },
            onDismiss = { exporting = false },
        )
    }
}

@Composable
internal fun EntityContent(
    state: EntityUiState,
    onBack: () -> Unit,
    onLabel: (UserEntityState) -> Unit,
    onRename: (String) -> Unit,
    onNote: (String) -> Unit,
    onEvidence: () -> Unit,
    onProximity: () -> Unit,
    onExport: () -> Unit,
    onBatch: (Long) -> Unit,
) {
    val c = SotreusTheme.colors
    val d = state.detail
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        if (d == null) {
            if (!state.loading) CaveatBox(stringResource(R.string.entity_identity_kicker), stringResource(R.string.entity_not_found))
            return@ScreenColumn
        }
        val e = d.entity
        var renaming by remember { mutableStateOf(false) }
        val title = entityTitle(e.userName, e.advertisedName, e.radio, e.family)
        val age = state.now - e.lastSeenMs
        val stale = age > state.staleHoldSeconds * 1000L * if (e.radio == RadioKind.WIFI) 4 else 1

        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            MonoLabel(stringResource(if (e.radio == RadioKind.WIFI) R.string.entity_kicker_wifi else R.string.entity_kicker_ble))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                AutoSizeText(title, SotreusTheme.typography.displayM, c.text, Modifier.weight(1f, fill = false).semantics { heading() })
                IconButton(onClick = { renaming = true }, modifier = Modifier.size(SotreusTheme.sizes.minTouch)) {
                    Icon(SotreusIcons.Edit, contentDescription = stringResource(R.string.entity_rename), tint = c.textMuted, modifier = Modifier.size(SotreusTheme.sizes.iconSize))
                }
            }
            e.guess?.takeIf { it.isNotBlank() }?.let { Text(it, style = SotreusTheme.typography.bodyL, color = c.textSoft) }
            AddressLine(e, state.maskCoordinates)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
                StateChip(stringResource(R.string.entity_chip_confidence, confidenceLabel(e.guessConfidence)), ChipTone.NEUTRAL, small = false)
                e.family?.let { StateChip(familySignature(it), ChipTone.NEUTRAL, small = false) }
                if (stale) {
                    StateChip(stringResource(R.string.entity_chip_stale, ageAgo(age)), ChipTone.DASHED, small = false)
                } else {
                    StateChip(stringResource(R.string.entity_chip_sensed, ageAgo(age)), ChipTone.ACCENT_FILLED, small = false)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            MonoLabel(stringResource(R.string.entity_your_label))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
                UserEntityState.entries.forEach { s ->
                    SelectChip(userLabel(s), e.userState == s, onClick = { onLabel(s) }, tall = true, role = Role.RadioButton)
                }
            }
        }

        StatGrid(
            listOf(
                Stat(dateShort(e.firstSeenMs), "", stringResource(R.string.entity_first_seen)),
                Stat(if (stale) dayLabel(e.lastSeenMs, state.now) else ageAgo(age), "", stringResource(R.string.entity_last_seen)),
                Stat(d.encounterCount.toString(), "", stringResource(R.string.entity_encounters)),
                Stat(d.byPlace.count { it.placeId != null }.toString(), "", stringResource(R.string.entity_places)),
            ),
            columns = 2,
        )

        if (d.byPlace.isNotEmpty()) {
            Column {
                MonoLabel(stringResource(R.string.entity_encountered_at))
                d.byPlace.forEachIndexed { i, p ->
                    ValueRow(
                        p.name ?: stringResource(R.string.entity_no_place),
                        pluralStringResource(R.plurals.entity_place_summary, p.count, p.count, dayLabel(p.lastMs, state.now).let { if (isSameDay(p.lastMs, state.now)) it.lowercase() else it }),
                        showDivider = i < d.byPlace.lastIndex,
                    )
                }
            }
        }

        CaveatBox(stringResource(R.string.entity_identity_kicker), identityText(e))
        KeyValueTable(metadata(e, state), title = stringResource(R.string.entity_metadata))

        SotreusCard(style = CardStyle.RAISED, onClick = onEvidence) {
            Text(stringResource(R.string.entity_raw_evidence, d.observationCount), style = SotreusTheme.typography.rowTitle, color = c.text)
        }

        if (state.proofsEnabled && d.proofBatches.isNotEmpty()) {
            Column {
                MonoLabel(stringResource(R.string.entity_proof_batches))
                d.proofBatches.forEach { b ->
                    InfoRow(b.title, subtitle = stringResource(R.string.entity_proof_row, b.recordCount, dayLabel(b.createdAtMs, state.now)), onClick = { onBatch(b.id) }) {
                        StateChip(b.state.name, if (b.state.name == "FINALIZED") ChipTone.NEUTRAL else ChipTone.DASHED)
                    }
                }
            }
        }

        var noteText by remember(e.id) { mutableStateOf(e.note.orEmpty()) }
        LaunchedEffect(e.id) { noteText = e.note.orEmpty() }
        NoteField(noteText, { noteText = it; onNote(it) }, stringResource(R.string.entity_notes_hint), stringResource(R.string.entity_notes))

        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            if (e.radio == RadioKind.BLE) PrimaryButton(stringResource(R.string.entity_check_proximity), onProximity)
            GhostButton(stringResource(R.string.entity_export), onExport)
        }

        if (renaming) {
            TextInputDialog(
                title = stringResource(R.string.entity_rename_title),
                initial = e.userName ?: e.advertisedName.orEmpty(),
                placeholder = stringResource(R.string.entity_rename_hint),
                confirm = stringResource(UiR.string.core_ui_save),
                dismiss = stringResource(UiR.string.core_ui_cancel),
                onConfirm = { renaming = false; onRename(it) },
                onDismiss = { renaming = false },
            )
        }
    }
}

/**
 * The radio's address (BLE MAC or Wi-Fi BSSID), so you can recognise and label your own devices.
 * BSSIDs follow the on-screen privacy mask until revealed. Random BLE addresses are flagged as
 * changeable: a label sticks to this address, not to the physical device.
 */
@Composable
private fun AddressLine(e: EntityEntity, mask: Boolean) {
    val c = SotreusTheme.colors
    val context = LocalContext.current
    var revealed by remember(e.id) { mutableStateOf(false) }
    var copied by remember(e.id) { mutableStateOf(false) }
    val masked = e.radio == RadioKind.WIFI && mask && !revealed
    val note = when {
        e.radio == RadioKind.WIFI -> null
        e.addressType == BleAddressType.PUBLIC -> stringResource(R.string.entity_address_public)
        Fingerprint.randomSubtype(e.address) == Fingerprint.RandomSubtype.STATIC -> stringResource(R.string.entity_address_static)
        else -> stringResource(R.string.entity_address_random)
    }
    val clipLabel = stringResource(R.string.entity_copy_label)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MonoLabel(stringResource(if (e.radio == RadioKind.WIFI) R.string.entity_bssid_kicker else R.string.entity_address_kicker), small = true)
            Text(
                if (masked) maskMac(e.address) else e.address,
                style = SotreusTheme.typography.monoValue.copy(fontSize = SotreusTheme.typography.body.fontSize),
                color = c.text,
            )
            note?.let { Text(it, style = SotreusTheme.typography.caption, color = c.textMuted) }
        }
        if (masked) {
            CompactButton(stringResource(R.string.entity_show), { revealed = true })
        } else {
            CompactButton(stringResource(if (copied) R.string.entity_copied else R.string.entity_copy), {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText(clipLabel, e.address))
                copied = true
            })
        }
    }
}

@Composable
private fun identityText(e: EntityEntity): String = when {
    e.radio == RadioKind.WIFI -> stringResource(R.string.entity_identity_wifi)
    e.addressType == BleAddressType.PUBLIC -> stringResource(R.string.entity_identity_public)
    else -> stringResource(R.string.entity_identity_random, confidenceLabel(e.linkConfidence))
}

@Composable
private fun metadata(e: EntityEntity, state: EntityUiState): List<Pair<String, String>> {
    val dash = stringResource(R.string.meta_dash)
    val avg = state.avgRssi30?.let { stringResource(UiR.string.dbm, it).replace("-", "−") } ?: dash
    return if (e.radio == RadioKind.WIFI) {
        listOf(
            stringResource(R.string.meta_ssid) to (e.advertisedName ?: dash),
            stringResource(R.string.meta_bssid) to if (state.maskCoordinates) maskMac(e.address) else e.address,
            stringResource(R.string.meta_security) to (e.security?.let(::securityText) ?: dash),
            stringResource(R.string.meta_band) to (e.frequencyMhz?.let { f -> stringResource(R.string.meta_band_value, if (f < 3000) "2.4 GHz" else if (f < 5925) "5 GHz" else "6 GHz", e.channel ?: 0) } ?: dash),
            stringResource(R.string.meta_standard) to (e.wifiStandard ?: dash),
            stringResource(R.string.meta_vendor) to (e.vendor ?: dash),
            stringResource(R.string.meta_rssi) to avg,
        )
    } else {
        val type = when (e.addressType) {
            BleAddressType.PUBLIC -> stringResource(R.string.meta_public)
            BleAddressType.RANDOM -> when (Fingerprint.randomSubtype(e.address)) {
                Fingerprint.RandomSubtype.STATIC -> stringResource(R.string.meta_random_static)
                Fingerprint.RandomSubtype.NON_RESOLVABLE -> stringResource(R.string.meta_random_nonresolvable)
                else -> stringResource(R.string.meta_random_resolvable)
            }
            else -> stringResource(R.string.meta_unknown)
        }
        listOf(
            stringResource(R.string.meta_address) to e.address,
            stringResource(R.string.meta_address_type) to type,
            stringResource(R.string.meta_advertised_name) to (e.advertisedName ?: dash),
            stringResource(R.string.meta_company_id) to (e.companyId?.let { "0x%04X".format(it) } ?: dash),
            stringResource(R.string.meta_service_data) to (e.serviceData?.split(",")?.firstOrNull()?.let { sd -> sd.substringBefore(":") + " · " + sd.substringAfter(":") + " B" } ?: dash),
            stringResource(R.string.meta_service_uuids) to e.serviceUuids.ifBlank { dash },
            stringResource(R.string.meta_connectable) to when (e.connectable) {
                true -> stringResource(R.string.meta_yes)
                false -> stringResource(R.string.meta_no)
                null -> dash
            },
            stringResource(R.string.meta_tx_power) to (e.txPower?.let { stringResource(UiR.string.dbm, it) } ?: stringResource(R.string.meta_not_advertised)),
            stringResource(R.string.meta_rssi) to avg,
        )
    }
}

/** Fieldwatch's plain-language Wi-Fi security explanation, guarded for calm copy. */
private fun securityText(raw: String): String =
    app.sotreus.intelligence.CalmCopy.orFallback(app.sotreus.intelligence.fieldwatch.DeviceExplain.wifiSecurityExplain(raw), raw)

private fun maskMac(mac: String): String {
    val parts = mac.split(":")
    if (parts.size != 6) return mac
    return listOf(parts[0], parts[1], parts[2], "••", "••", parts[5]).joinToString(":")
}

@Preview(widthDp = 390, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun EntityPreview() {
    SotreusTheme {
        EntityContent(
            EntityUiState(loading = false, detail = FakeSotreusData.greyTagDetail, avgRssi30 = -61, now = FakeSotreusData.NOW + 1_000),
            {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}
