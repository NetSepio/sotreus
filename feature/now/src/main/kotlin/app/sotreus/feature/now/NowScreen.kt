package app.sotreus.feature.now

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sotreus.core.data.live.LiveRadio
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.model.NowView
import app.sotreus.core.model.PresenceState
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.UserEntityState
import app.sotreus.core.navigation.AttentionRoute
import app.sotreus.core.navigation.EntityRoute
import app.sotreus.core.navigation.PermissionsRoute
import app.sotreus.core.navigation.PlaceRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.AutoSizeText
import app.sotreus.core.ui.AttentionRowCard
import app.sotreus.core.ui.BandDot
import app.sotreus.core.ui.BandLabels
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.EntityRow
import app.sotreus.core.ui.EntityStateChip
import app.sotreus.core.ui.FilterChipRow
import app.sotreus.core.ui.FilterOption
import app.sotreus.core.ui.Freshness
import app.sotreus.core.ui.FreshnessLine
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.GlyphLegend
import app.sotreus.core.ui.GlyphShape
import app.sotreus.core.ui.GlyphSpec
import app.sotreus.core.ui.GlyphTone
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.ProximityBands
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.SegmentedToggle
import app.sotreus.core.ui.Stat
import app.sotreus.core.ui.StatTiles
import app.sotreus.core.ui.StatusPill
import app.sotreus.core.ui.WarningBanner
import app.sotreus.core.ui.ageShort
import app.sotreus.core.ui.attentionHeadline
import app.sotreus.core.ui.entityTitle
import app.sotreus.core.ui.familyName
import app.sotreus.core.ui.familySignature
import app.sotreus.core.ui.glyphFor
import app.sotreus.core.ui.seenAtPlaces
import app.sotreus.intelligence.BandLayout
import app.sotreus.core.ui.R as UiR

@Composable
internal fun NowScreen(navigate: (Any) -> Unit, vm: NowViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val locationFailed by vm.locationFailed.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    if (locationFailed) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.locationFailed.value = false },
            containerColor = SotreusTheme.colors.surfaceRaised,
            text = { Text(stringResource(R.string.picker_location_failed), style = SotreusTheme.typography.body, color = SotreusTheme.colors.text) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.locationFailed.value = false }) {
                    Text(stringResource(R.string.picker_ok), color = SotreusTheme.colors.accent)
                }
            },
        )
    }
    NowContent(
        state = state,
        onView = vm::setView,
        onFilter = vm::setFilter,
        onSort = vm::setSort,
        onPlace = { picker = true },
        onEntity = { navigate(EntityRoute(it)) },
        onAttention = { navigate(AttentionRoute(it)) },
        onPermissions = { navigate(PermissionsRoute) },
        onShowAddresses = vm::setShowAddresses,
    )
    if (picker) {
        PlacePickerSheet(
            places = state.places,
            currentId = state.snapshot.placeId,
            onSelect = { vm.selectPlace(it); picker = false },
            onCreate = { name, withLocation -> vm.createPlace(name, withLocation); picker = false },
            onDetails = { id -> picker = false; navigate(PlaceRoute(id)) },
            onDismiss = { picker = false },
            byLocation = state.settings.placeByLocation,
        )
    }
}

@Composable
internal fun NowContent(
    state: NowUiState,
    onView: (NowView) -> Unit,
    onFilter: (NowFilter) -> Unit,
    onSort: (NowSort) -> Unit,
    onPlace: () -> Unit,
    onEntity: (String) -> Unit,
    onAttention: (Long) -> Unit,
    onPermissions: () -> Unit,
    onShowAddresses: (Boolean) -> Unit = {},
) {
    val snap = state.snapshot
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        Header(state, onPlace)
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
            FreshnessLine(freshness(state))
            SegmentedToggle(
                listOf(NowView.BANDS to stringResource(R.string.now_view_bands), NowView.LIST to stringResource(R.string.now_view_list)),
                state.view,
                onView,
            )
        }
        if (state.settings.simulatedRadios) WarningBanner(stringResource(R.string.now_simulated_banner))
        when {
            state.needsPermissions -> PermissionPrompt(onPermissions)
            else -> {
                snap.access?.radiosOff?.takeIf { it.isNotEmpty() && !state.settings.simulatedRadios }?.let { off ->
                    WarningBanner(stringResource(R.string.now_radios_off_body, off.map { radioName(it) }.joinToString(", ")))
                }
                if (state.view == NowView.BANDS) BandsView(state, onEntity, onAttention) else ListView(state, onFilter, onSort, onEntity, onShowAddresses)
            }
        }
    }
}

@Composable
private fun radioName(id: String) = stringResource(
    when (id) {
        "bluetooth" -> R.string.radio_bluetooth
        "wifi" -> R.string.radio_wifi
        else -> R.string.radio_location
    },
)

@Composable
private fun Header(state: NowUiState, onPlace: () -> Unit) {
    val c = SotreusTheme.colors
    val placeName = state.snapshot.placeName ?: stringResource(R.string.now_unsaved_place)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
        val a11y = stringResource(R.string.now_place_picker_a11y, placeName)
        Column(
            Modifier.weight(1f).heightIn(min = SotreusTheme.sizes.minTouch).clickable(role = Role.Button, onClick = onPlace)
                .semantics(mergeDescendants = true) { contentDescription = a11y },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MonoLabel(stringResource(if (state.snapshot.placeByLocation) R.string.now_place_kicker_location else R.string.now_place_kicker), small = true)
            // Long place names step down in size instead of being cut off.
            AutoSizeText(placeName, SotreusTheme.typography.titleS, c.text)
        }
        val pill = when {
            state.snapshot.locating -> stringResource(R.string.now_locating)
            !state.snapshot.observing -> stringResource(R.string.now_paused)
            state.settings.simulatedRadios -> stringResource(R.string.now_simulated)
            else -> stringResource(R.string.now_observing)
        }
        StatusPill(pill, live = state.snapshot.observing)
    }
}

@Composable
private fun freshness(state: NowUiState): List<Freshness> {
    val snap = state.snapshot
    val bleAge = snap.status.bleLastResultMs.takeIf { it > 0 }?.let { snap.atMs - it }
    val wifiAge = state.wifiAgeMs
    val wifiText = wifiAge?.let { ageShort(it) } ?: stringResource(R.string.fresh_none)
    return listOf(
        Freshness(stringResource(R.string.fresh_ble), bleAge?.let { ageShort(it) } ?: stringResource(R.string.fresh_none)),
        Freshness(
            stringResource(R.string.fresh_wifi),
            if (state.wifiThrottled) stringResource(R.string.fresh_throttled, wifiText) else wifiText,
            limited = state.wifiThrottled,
        ),
        Freshness(
            if (snap.placeId == null || state.learning) stringResource(R.string.fresh_learning) else pluralStringResource(R.plurals.fresh_baseline, snap.baselineVisits, snap.baselineVisits),
            null,
        ),
    )
}

@Composable
private fun BandsView(state: NowUiState, onEntity: (String) -> Unit, onAttention: (Long) -> Unit) {
    val live = state.snapshot.radios
    val labelled = live.filter { it.needsAttention || it.userState == UserEntityState.TAGGED || it.userState == UserEntityState.WATCH }
        .sortedByDescending { it.needsAttention }.take(3).map { it.entityId }.toSet()
    // Priority order: attention and tagged first, then strongest, so the full-view cap keeps what matters.
    val shown = live.sortedWith(compareByDescending<app.sotreus.core.data.live.LiveRadio> { it.needsAttention || it.entityId in labelled }.thenByDescending { it.avgRssi30 })
    val dots = shown.map { r ->
        val title = entityTitle(r.userName, r.advertisedName, r.kind, r.family)
        val labelBase = if (r.userName == null && r.advertisedName == null && r.family != null) {
            stringResource(UiR.string.family_short, app.sotreus.core.ui.familyAdjective(r.family!!))
        } else {
            title
        }
        BandDot(
            id = r.entityId,
            band = BandLayout.band(r.avgRssi30),
            angle = BandLayout.angleRadians(r.entityId),
            radial = BandLayout.radialJitter(r.entityId),
            glyph = glyphFor(r.kind, r.userState, r.presence, r.stale, r.needsAttention),
            zoomLabel = labelBase,
            label = if (r.entityId in labelled) {
                if (r.presence == PresenceState.NEW) stringResource(R.string.bands_label_new, labelBase) else stringResource(R.string.bands_label_seen, labelBase)
            } else {
                null
            },
        )
    }
    val near = dots.count { it.band == app.sotreus.core.model.ProximityBand.NEAR }
    val mid = dots.count { it.band == app.sotreus.core.model.ProximityBand.MID }
    ProximityBands(
        allDots = dots,
        fullViewLimit = BandLayout.MAX_DOTS,
        labels = BandLabels(stringResource(UiR.string.band_near), stringResource(UiR.string.band_mid), stringResource(UiR.string.band_far), stringResource(UiR.string.band_you)),
        contentDescription = stringResource(R.string.bands_a11y, live.size, near, mid, dots.size - near - mid, state.changed),
        onDotClick = onEntity,
        zoomHint = stringResource(R.string.bands_zoom_hint),
        resetLabel = stringResource(R.string.bands_zoom_reset),
    )
    Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
        GlyphLegend(
            listOf(
                GlyphSpec(GlyphShape.BLE, GlyphTone.NEW) to stringResource(UiR.string.legend_new),
                GlyphSpec(GlyphShape.BLE, GlyphTone.FAMILIAR) to stringResource(UiR.string.legend_familiar),
                GlyphSpec(GlyphShape.TAGGED, GlyphTone.TAGGED) to stringResource(UiR.string.legend_tagged),
                GlyphSpec(GlyphShape.WIFI, GlyphTone.NEUTRAL) to stringResource(UiR.string.legend_wifi),
            ),
        )
        // Required copy (HANDOFF_V1_UI.md §8). Never remove.
        Text(stringResource(R.string.bands_disclaimer), style = SotreusTheme.typography.caption, color = SotreusTheme.colors.textDim)
    }
    StatTiles(
        listOf(
            Stat(state.live.size.toString(), pluralStringResource(R.plurals.stat_families, state.families, state.families), stringResource(R.string.stat_here)),
            Stat(
                if (state.learning) stringResource(R.string.stat_dash) else state.familiar.toString(),
                if (state.learning) stringResource(R.string.stat_learning) else stringResource(R.string.stat_this_place),
                stringResource(R.string.stat_familiar),
            ),
            Stat(state.changed.toString(), stringResource(R.string.stat_new_here), stringResource(R.string.stat_changed), attention = true),
            Stat(state.seenElsewhere.toString(), stringResource(R.string.stat_elsewhere), stringResource(R.string.stat_seen)),
        ),
    )
    if (state.learning) CaveatBox(stringResource(R.string.now_first_visit_kicker), stringResource(R.string.now_first_visit_body))
    if (state.live.isEmpty() && state.snapshot.observing) CaveatBox(stringResource(R.string.now_empty_kicker), stringResource(R.string.now_empty_body))
    state.openAttention.firstOrNull()?.let { top ->
        AttentionRowCard(
            kicker = stringResource(R.string.now_needs_attention, state.openAttention.size),
            title = attentionHeadline(top.headline, top.entity?.family),
            onClick = { onAttention(top.event.id) },
        )
    }
}

@Composable
private fun ListView(state: NowUiState, onFilter: (NowFilter) -> Unit, onSort: (NowSort) -> Unit, onEntity: (String) -> Unit, onShowAddresses: (Boolean) -> Unit) {
    if (state.wifiThrottled) {
        val age = state.wifiAgeMs?.let { ageShort(it) } ?: stringResource(R.string.fresh_none)
        WarningBanner(
            if (state.snapshot.status.wifiLastRequestRejected) stringResource(R.string.now_wifi_banner_rejected, age) else stringResource(R.string.now_wifi_banner, age),
        )
    }
    FilterChipRow(
        listOf(
            FilterOption(NowFilter.ALL, stringResource(R.string.filter_all, state.snapshot.radios.size)),
            FilterOption(NowFilter.NEW, stringResource(R.string.filter_new, state.changed), accent = true),
            FilterOption(NowFilter.TAGGED, stringResource(R.string.filter_tagged, state.tagged)),
            FilterOption(NowFilter.FAMILIAR, stringResource(R.string.filter_familiar, state.familiar)),
            FilterOption(NowFilter.BY_CLASS, stringResource(R.string.filter_by_class)),
        ),
        state.filter,
        onFilter,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SortMenu(state.sort, onSort)
        MonoLabel(stringResource(R.string.hold_label, state.settings.staleHoldSeconds), small = true)
    }
    app.sotreus.core.ui.SwitchRow(stringResource(R.string.addresses_toggle), state.settings.showAddresses, onShowAddresses, bordered = false)
    val rows = state.rows()
    if (rows.isEmpty()) {
        CaveatBox(stringResource(R.string.now_empty_kicker), stringResource(R.string.now_empty_body))
        return
    }
    Column {
        RowDivider(strong = true)
        if (state.filter == NowFilter.BY_CLASS) {
            rows.groupBy { it.family }.toList().sortedBy { (f, _) -> f == null }.forEach { (family, group) ->
                ClassGroup(family, group, state.snapshot.atMs, onEntity, state.addressMode())
            }
        } else {
            rows.forEach { RadioRow(it, state.snapshot.atMs, onEntity, state.addressMode()) }
        }
    }
}

@Composable
private fun ClassGroup(family: DeviceFamily?, rows: List<LiveRadio>, now: Long, onEntity: (String) -> Unit, addresses: AddressMode) {
    var expanded by remember { mutableStateOf(true) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = SotreusTheme.sizes.minTouch).clickable(role = Role.Button) { expanded = !expanded }.padding(top = SotreusTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel("${family?.let { familyName(it) } ?: stringResource(R.string.class_unclassified)} · ${rows.size} ${if (expanded) "▾" else "▸"}")
    }
    if (expanded) rows.forEach { RadioRow(it, now, onEntity, addresses) }
}

@Composable
private fun RadioRow(r: LiveRadio, now: Long, onEntity: (String) -> Unit, addresses: AddressMode = AddressMode.HIDDEN) {
    val title = entityTitle(r.userName, r.advertisedName, r.kind, r.family)
    val detail = when {
        r.kind == RadioKind.WIFI -> stringResource(R.string.row_subtitle_wifi, securityShort(r.security), bandOf(r.frequencyMhz))
        r.family != null && r.otherPlaces > 0 -> stringResource(R.string.row_subtitle_family, app.sotreus.core.ui.familyAdjective(r.family!!), seenAtPlaces(r.otherPlaces + 1))
        r.family != null -> stringResource(R.string.row_subtitle_signature, familySignature(r.family!!), stringResource(UiR.string.entity_ble_short))
        else -> stringResource(R.string.row_subtitle_signature, stringResource(UiR.string.entity_no_class), stringResource(if (r.randomAddress) UiR.string.entity_random_address else UiR.string.entity_public_address))
    }
    val subtitle = when (addresses) {
        AddressMode.HIDDEN -> detail
        // Wi-Fi BSSIDs follow the on-screen privacy mask; BLE addresses are shown in full.
        AddressMode.SHOWN, AddressMode.SHOWN_MASK_WIFI ->
            stringResource(R.string.row_address_subtitle, if (addresses == AddressMode.SHOWN_MASK_WIFI && r.kind == RadioKind.WIFI) maskMac(r.address) else r.address, detail)
    }
    EntityRow(
        title = title,
        subtitle = subtitle,
        glyph = glyphFor(r.kind, r.userState, r.presence, r.stale, false),
        onClick = { onEntity(r.entityId) },
        trailingValue = if (r.stale) r.lastRssi.toString().replace("-", "−") else stringResource(R.string.row_rssi_age, r.avgRssi30.toInt(), ageShort(now - r.lastHeardMs)).replace("-", "−"),
        chip = { EntityStateChip(r.userState, r.presence, r.stale) },
    )
}

internal enum class AddressMode { HIDDEN, SHOWN, SHOWN_MASK_WIFI }

private fun NowUiState.addressMode() = when {
    !settings.showAddresses -> AddressMode.HIDDEN
    settings.maskCoordinates -> AddressMode.SHOWN_MASK_WIFI
    else -> AddressMode.SHOWN
}

private fun maskMac(mac: String): String {
    val p = mac.split(":")
    return if (p.size == 6) listOf(p[0], p[1], p[2], "••", "••", p[5]).joinToString(":") else mac
}

@Composable
private fun securityShort(raw: String?): String {
    val u = raw.orEmpty().uppercase()
    return when {
        "SAE" in u -> stringResource(R.string.sec_wpa3)
        "EAP" in u -> stringResource(R.string.sec_enterprise)
        "PSK" in u || "WPA" in u -> stringResource(R.string.sec_wpa2)
        else -> stringResource(R.string.sec_open)
    }
}

@Composable
private fun bandOf(freq: Int?): String = stringResource(
    when {
        freq == null || freq < 3000 -> R.string.band_24
        freq < 5925 -> R.string.band_5
        else -> R.string.band_6
    },
)

@Composable
private fun SortMenu(sort: NowSort, onSort: (NowSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.heightIn(min = SotreusTheme.sizes.minTouch).clickable(role = Role.DropdownList) { open = true }, contentAlignment = Alignment.CenterStart) {
            MonoLabel(
                stringResource(
                    when (sort) {
                        NowSort.STRONGEST -> R.string.sort_strongest
                        NowSort.NEWEST -> R.string.sort_newest
                        NowSort.NAME -> R.string.sort_name
                        NowSort.SIGNATURES -> R.string.sort_signatures
                    },
                ),
                small = true,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SotreusTheme.colors.surfaceRaised) {
            listOf(
                NowSort.STRONGEST to R.string.sort_menu_strongest,
                NowSort.NEWEST to R.string.sort_menu_newest,
                NowSort.NAME to R.string.sort_menu_name,
                NowSort.SIGNATURES to R.string.sort_menu_signatures,
            ).forEach { (s, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label), style = SotreusTheme.typography.body, color = if (s == sort) SotreusTheme.colors.accent else SotreusTheme.colors.text) },
                    onClick = { onSort(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onPermissions: () -> Unit) {
    CaveatBox(stringResource(R.string.now_perm_kicker), stringResource(R.string.now_perm_body))
    GhostButton(stringResource(R.string.now_perm_action), onPermissions, strong = true)
}

@Preview(widthDp = 390, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun NowBandsPreview() {
    SotreusTheme { NowContent(NowUiState(snapshot = FakeSotreusData.snapshot), {}, {}, {}, {}, {}, {}, {}) }
}

@Preview(widthDp = 390, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun NowListPreview() {
    SotreusTheme { NowContent(NowUiState(snapshot = FakeSotreusData.snapshot, view = NowView.LIST), {}, {}, {}, {}, {}, {}, {}) }
}
