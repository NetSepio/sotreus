package app.sotreus.feature.mesh

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sotreus.core.data.mesh.MeshRows
import app.sotreus.core.database.dao.MeshConversationRow
import app.sotreus.core.database.entity.MeshNodeEntity
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.FilterChipRow
import app.sotreus.core.ui.FilterOption
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.MeshMap
import app.sotreus.core.ui.MeshMapPin
import app.sotreus.core.ui.MeshMapWaypoint
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.RadioGroupRows
import app.sotreus.core.ui.RadioOption
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.StatusPill
import app.sotreus.core.ui.TextInputDialog
import app.sotreus.core.ui.UnderlineTabs
import app.sotreus.core.ui.WarningBanner
import app.sotreus.edge.link.EdgeLinkState
import app.sotreus.edge.protocol.mesh.MeshNodeId
import app.sotreus.edge.protocol.mesh.MeshPositionMode

/** The Mesh tab (mocks M1, M3, M5; M8 the first time): Messages · Nodes · Map, and Mesh settings. */
@Composable
internal fun MeshScreen(
    onConversation: (String) -> Unit,
    onNode: (Long) -> Unit,
    onSettings: () -> Unit,
    onPickPosition: () -> Unit,
    vm: MeshViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Android asks for "Allow all the time" separately, after location while in use (HANDOFF_V3_MESH.md §3.1).
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true && Build.VERSION.SDK_INT >= 29) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    if (state.askPosition) {
        PositionDisclosure(state) { choice ->
            when (choice) {
                PositionChoice.EDGE -> state.edgePosition?.let { (lat, lon, _) -> vm.choosePosition(MeshPositionMode.FIXED, lat to lon) }
                PositionChoice.PHONE -> {
                    vm.choosePosition(MeshPositionMode.CLIENT)
                    foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                PositionChoice.MAP -> onPickPosition()
                PositionChoice.OFF -> vm.choosePosition(MeshPositionMode.OFF)
            }
        }
        return
    }
    MeshContent(state, onConversation, onNode, onSettings, vm::missedMessages, vm::addWaypoint, vm::clearMessage, onDismissRegion = vm::dismissRegionNote)
}

@Composable
internal fun MeshContent(
    state: MeshUi,
    onConversation: (String) -> Unit,
    onNode: (Long) -> Unit,
    onSettings: () -> Unit,
    onMissed: (Long?) -> Unit = {},
    onAddWaypoint: (Double, Double, String) -> Unit = { _, _, _ -> },
    onClearMessage: () -> Unit = {},
    initialTab: MeshTab = MeshTab.MESSAGES,
    onDismissRegion: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        MeshHeader(state)
        val cfg = state.live.config
        if (cfg != null && !cfg.regionSet) {
            WarningBanner(stringResource(R.string.mesh_region_unset))
            GhostButton(stringResource(R.string.mesh_set_region), onSettings)
        } else if (state.live.connected && state.live.status?.running == false) {
            WarningBanner(stringResource(R.string.mesh_not_running))
        }
        state.message?.let { WarningBanner(it); GhostButton(stringResource(R.string.mesh_ok), onClearMessage) }
        state.live.regionFromNetwork?.let { r ->
            app.sotreus.core.ui.CaveatBox(stringResource(R.string.mesh_region), stringResource(R.string.mesh_region_from_network, regionName(r)))
            app.sotreus.core.ui.QuietButton(stringResource(R.string.mesh_ok), onDismissRegion)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            UnderlineTabs(
                listOf(MeshTab.MESSAGES to stringResource(R.string.mesh_tab_messages), MeshTab.NODES to stringResource(R.string.mesh_tab_nodes), MeshTab.MAP to stringResource(R.string.mesh_tab_map)),
                tab, { tab = it }, Modifier.weight(1f),
            )
            IconButton(onClick = onSettings, modifier = Modifier.size(SotreusTheme.sizes.minTouch)) {
                Icon(SotreusIcons.Settings, contentDescription = stringResource(R.string.mesh_settings), tint = SotreusTheme.colors.textMuted, modifier = Modifier.size(SotreusTheme.sizes.iconSize))
            }
        }
        when (tab) {
            MeshTab.MESSAGES -> MessagesTab(state, onConversation, onMissed)
            MeshTab.NODES -> NodesTab(state, onNode)
            MeshTab.MAP -> MapTab(state, onNode, onConversation, onAddWaypoint)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeshHeader(state: MeshUi) {
    val c = SotreusTheme.colors
    val live = state.live
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xs)) {
            val self = live.selfNum
            if (self != null) MonoLabel(stringResource(R.string.mesh_kicker, live.config?.shortName.orEmpty().ifBlank { "—" }, MeshNodeId.format(self)), small = true)
            Text(stringResource(R.string.mesh_title), style = SotreusTheme.typography.displayM, color = c.text)
        }
        when (state.link) {
            is EdgeLinkState.Connected -> StatusPill(stringResource(R.string.mesh_pill_connected), live = true, filled = true)
            EdgeLinkState.Connecting, EdgeLinkState.Bonding -> StatusPill(stringResource(R.string.mesh_pill_connecting), live = false, filled = false)
            else -> StatusPill(stringResource(R.string.mesh_pill_away), live = false, filled = false)
        }
    }
    val st = live.status
    val cfg = live.config
    if (cfg != null || st != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xs)) {
            val region = st?.region ?: cfg?.region?.takeIf { cfg.regionSet }
            if (region != null) MonoLabel(stringResource(R.string.mesh_meta_radio, region, roleOrPreset(st?.preset ?: cfg?.preset)), small = true)
            MonoLabel(pluralStringResource(R.plurals.mesh_meta_nodes, state.data.nodes.size, state.data.nodes.size), small = true)
            st?.channelUtilPct?.let { MonoLabel(stringResource(R.string.mesh_meta_channel_use, "%.0f".format(it)), small = true) }
        }
    }
    if (live.sharing) {
        val channels = cfg?.channels?.count { it.positionPrecision > 0 } ?: 0
        StateChip(pluralStringResource(R.plurals.mesh_sharing, channels, channels), ChipTone.TEXT, small = false)
    }
}

private fun roleOrPreset(wire: String?): String = wire?.let(app.sotreus.edge.protocol.mesh.MeshNames::title).orEmpty()

/** "India (IN)" for the regions people meet most; otherwise as Meshtastic writes it. */
@Composable
internal fun regionName(wire: String): String = when (wire) {
    "IN" -> stringResource(R.string.mesh_region_in)
    "US" -> stringResource(R.string.mesh_region_us)
    "EU_868" -> stringResource(R.string.mesh_region_eu868)
    else -> app.sotreus.edge.protocol.mesh.MeshNames.title(wire)
}

// ---- Messages (M1) ------------------------------------------------------------------------------

@Composable
private fun MessagesTab(state: MeshUi, onConversation: (String) -> Unit, onMissed: (Long?) -> Unit) {
    val rows = state.data.conversations
    // Every channel the Edge has is listed, so its first message can be sent from here.
    val byConversation = rows.associateBy { it.conversation }
    val configured = state.live.config?.channels.orEmpty().indices.map { i -> byConversation["ch:$i"] ?: emptyChannel(i) }
    val channels = configured + rows.filter { it.conversation.startsWith("ch:") && configured.none { c -> c.conversation == it.conversation } }
    val direct = rows.filter { it.conversation.startsWith("dm:") }
    val nodes = state.nodesByNum
    if (channels.isEmpty() && direct.isEmpty()) {
        app.sotreus.core.ui.EmptyHint(stringResource(R.string.mesh_messages_empty_kicker), stringResource(R.string.mesh_messages_empty_body))
    }
    if (channels.isNotEmpty()) {
        SectionLabel(stringResource(R.string.mesh_channels_header, channels.size), network = true)
        Column { channels.forEach { r -> ConversationRow(r, state, nodes) { onConversation(r.conversation) } } }
    }
    if (direct.isNotEmpty()) {
        SectionLabel(stringResource(R.string.mesh_direct_header, direct.size))
        Column { direct.forEach { r -> ConversationRow(r, state, nodes) { onConversation(r.conversation) } } }
    }
    val router = state.live.status?.storeForwardRouters?.firstOrNull()
    if (router != null && state.live.canSend) {
        val name = nodes[router]?.label() ?: MeshNodeId.format(router)
        GhostButton(stringResource(R.string.mesh_missed, name), { onMissed(router) })
    }
}

private fun emptyChannel(i: Int) = MeshConversationRow("ch:$i", i, "", false, 0, MeshNodeId.BROADCAST, 0, null, false, 0)

@Composable
private fun SectionLabel(text: String, network: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = SotreusTheme.spacing.m), verticalAlignment = Alignment.CenterVertically) {
        MonoLabel(text, Modifier.weight(1f))
        if (network) StateChip(stringResource(R.string.mesh_network), ChipTone.NETWORK)
    }
}

@Composable
private fun ConversationRow(r: MeshConversationRow, state: MeshUi, nodes: Map<Long, MeshNodeEntity>, onClick: () -> Unit) {
    val c = SotreusTheme.colors
    val cfg = state.live.config
    val channel = MeshRows.channelOf(r.conversation)
    val peer = MeshRows.peerOf(r.conversation)
    val peerNode = peer?.let { nodes[it] }
    val title = conversationTitle(r.conversation, cfg, nodes)
    val empty = r.lastAtMs == 0L
    val who = if (r.lastOutgoing) stringResource(R.string.mesh_you) else nodes[r.lastFrom]?.label() ?: MeshNodeId.format(r.lastFrom)
    val meta = when {
        channel != null -> listOfNotNull(
            if (channel == 0) stringResource(R.string.mesh_primary) else null,
            keyLabel(cfg?.channels?.getOrNull(channel)),
            if (r.lastOutgoing) deliveryLabel(r.lastDelivery) else null,
        ).joinToString(" · ")
        else -> listOfNotNull(
            if (peerNode?.hasKey == true) stringResource(R.string.mesh_encrypted) else stringResource(R.string.mesh_key_unknown),
            if (r.lastOutgoing) deliveryLabel(r.lastDelivery) else hopsLabel(peerNode?.hopsAway),
        ).joinToString(" · ")
    }
    val stale = peerNode != null && state.nowMs - (peerNode.lastHeardMs ?: 0) > STALE_ROW_MS
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m),
    ) {
        Well(if (channel != null) "#" else shortTag(peerNode, peer ?: 0), network = channel != null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = SotreusTheme.typography.rowTitle, color = if (stale) c.textMuted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (peerNode?.hasKey == true) Icon(SotreusIcons.Lock, contentDescription = stringResource(R.string.mesh_encrypted), tint = c.textMuted, modifier = Modifier.size(12.dp))
            }
            Text(if (empty) stringResource(R.string.mesh_channel_empty) else "$who · ${r.lastText}", style = SotreusTheme.typography.bodyS, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = SotreusTheme.typography.caption, color = if (r.lastOutgoing && deliveryFailed(r.lastDelivery)) c.destructive else c.textDim, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!empty) Text(ago(state.nowMs, r.lastAtMs), style = SotreusTheme.typography.monoValue, color = c.textMuted)
            if (r.unread > 0) Unread(r.unread)
        }
    }
    RowDivider()
}

@Composable
internal fun Well(text: String, network: Boolean = false, own: Boolean = false, large: Boolean = false) {
    val c = SotreusTheme.colors
    Box(
        Modifier.size(if (large) 56.dp else 40.dp).background(if (own) c.attentionSurface else c.surfaceHigh, SotreusTheme.shapes.tile),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (network) SotreusTheme.typography.rowTitle else SotreusTheme.typography.monoLabelS,
            color = when { network -> c.reservedV11Network; own -> c.accent; else -> c.textSoft },
            maxLines = 1,
        )
    }
}

@Composable
private fun Unread(n: Int) {
    val c = SotreusTheme.colors
    Box(Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).background(c.text, SotreusTheme.shapes.pill).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(n.toString(), style = SotreusTheme.typography.monoLabelS, color = c.ink)
    }
}

// ---- Nodes (M3) ---------------------------------------------------------------------------------

@Composable
private fun NodesTab(state: MeshUi, onNode: (Long) -> Unit) {
    var filter by rememberSaveable { mutableStateOf(NodeFilter.ALL) }
    val self = state.live.selfNum
    val all = state.data.nodes.filter { it.num != self }
    val hourAgo = state.nowMs - 60 * 60_000L
    val shown = when (filter) {
        NodeFilter.ALL -> all
        NodeFilter.FAVOURITES -> all.filter { it.favorite }
        NodeFilter.RECENT -> all.filter { (it.lastHeardMs ?: 0) >= hourAgo }
        NodeFilter.TAK -> all.filter { it.role?.startsWith("TAK") == true }
    }.sortedWith(compareByDescending<MeshNodeEntity> { !it.ignored }.thenByDescending { it.favorite }.thenByDescending { it.lastHeardMs ?: 0 })
    FilterChipRow(
        listOf(
            FilterOption(NodeFilter.ALL, stringResource(R.string.mesh_filter_all, all.size)),
            FilterOption(NodeFilter.FAVOURITES, stringResource(R.string.mesh_filter_favourites, all.count { it.favorite })),
            FilterOption(NodeFilter.RECENT, stringResource(R.string.mesh_filter_recent, all.count { (it.lastHeardMs ?: 0) >= hourAgo })),
            FilterOption(NodeFilter.TAK, stringResource(R.string.mesh_filter_tak, all.count { it.role?.startsWith("TAK") == true })),
        ),
        filter, { filter = it },
    )
    SectionLabel(stringResource(R.string.mesh_this_edge))
    SelfRow(state)
    SectionLabel(stringResource(R.string.mesh_heard_header, shown.size))
    Column { shown.forEach { n -> NodeRow(n, state) { onNode(n.num) } } }
    if (shown.any { it.lat != null }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
            Text(stringResource(R.string.mesh_distances_note), style = SotreusTheme.typography.caption, color = SotreusTheme.colors.textDim)
            StateChip(stringResource(R.string.mesh_network), ChipTone.NETWORK)
        }
    }
}

@Composable
private fun SelfRow(state: MeshUi) {
    val c = SotreusTheme.colors
    val live = state.live
    val cfg = live.config ?: return
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
        Well(cfg.shortName.ifBlank { "—" }, own = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(cfg.longName.ifBlank { stringResource(R.string.mesh_this_edge) }, style = SotreusTheme.typography.rowTitle, color = c.text)
            Text(listOf(roleLabel(cfg.role), pluralStringResource(R.plurals.mesh_hops_max, cfg.hopLimit, cfg.hopLimit)).joinToString(" · "), style = SotreusTheme.typography.bodyS, color = c.textMuted)
            val pos = when (cfg.position.mode) {
                MeshPositionMode.CLIENT -> live.lastSharedMs?.let { stringResource(R.string.mesh_position_from_phone, ago(state.nowMs, it)) } ?: stringResource(R.string.mesh_position_from_phone_waiting)
                MeshPositionMode.FIXED -> stringResource(R.string.mesh_position_fixed)
                MeshPositionMode.OFF -> stringResource(R.string.mesh_position_off)
            }
            Text(pos, style = SotreusTheme.typography.caption, color = c.textDim)
        }
    }
    RowDivider()
}

@Composable
private fun NodeRow(n: MeshNodeEntity, state: MeshUi, onClick: () -> Unit) {
    val c = SotreusTheme.colors
    val stale = state.nowMs - (n.lastHeardMs ?: 0) > STALE_ROW_MS
    val dim = n.ignored || stale
    val line = listOfNotNull(
        roleLabel(n.role),
        hopsLabel(n.hopsAway),
        snr(n.snr)?.let { stringResource(R.string.mesh_snr, it) },
        when { (n.battery ?: 0) > 100 -> stringResource(R.string.mesh_on_power); n.battery != null -> stringResource(R.string.mesh_battery, n.battery!!); else -> null },
    ).joinToString(" · ")
    val meta = when {
        n.ignored -> stringResource(R.string.mesh_ignored_line)
        else -> listOfNotNull(
            if (n.hasKey) stringResource(R.string.mesh_encrypted_dms) else stringResource(R.string.mesh_key_unknown),
            n.positionMs?.let { stringResource(R.string.mesh_position_ago, ago(state.nowMs, it)) } ?: stringResource(R.string.mesh_no_position),
        ).joinToString(" · ")
    }
    val dist = between(state.phone, n, state.nowMs)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m),
    ) {
        Well(shortTag(n, n.num))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(n.title(), style = SotreusTheme.typography.rowTitle, color = if (dim) c.textMuted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.favorite) { val fav = stringResource(R.string.mesh_chip_favourite); Text("★", style = SotreusTheme.typography.caption, color = c.accent, modifier = Modifier.semantics { contentDescription = fav }) }
            }
            Text(line, style = SotreusTheme.typography.bodyS, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = SotreusTheme.typography.caption, color = c.textDim, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (dist != null && !dim) Text(distanceLabel(dist), style = SotreusTheme.typography.monoValue, color = c.text)
            Text(ago(state.nowMs, n.lastHeardMs), style = SotreusTheme.typography.monoValue, color = c.textMuted)
            when {
                n.ignored -> StateChip(stringResource(R.string.mesh_chip_ignored), ChipTone.NEUTRAL)
                stale -> StateChip(stringResource(R.string.mesh_chip_stale), ChipTone.DASHED)
            }
        }
    }
    RowDivider()
}

// ---- Map (M5) -----------------------------------------------------------------------------------

@Composable
private fun MapTab(state: MeshUi, onNode: (Long) -> Unit, onConversation: (String) -> Unit, onAddWaypoint: (Double, Double, String) -> Unit) {
    val c = SotreusTheme.colors
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var placing by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var recentre by remember { mutableIntStateOf(0) }
    val self = state.live.selfNum
    val nodes = state.data.nodes.filter { it.lat != null && it.lon != null && it.num != self && !it.ignored }
    Box(Modifier.fillMaxWidth().height(520.dp)) {
        MeshMap(
            phoneLat = state.phone?.lat, phoneLon = state.phone?.lon,
            pins = nodes.map { n -> MeshMapPin(n.num, n.lat!!, n.lon!!, app.sotreus.edge.protocol.mesh.MeshPrecision.metres(n.precisionBits ?: 32), isStale(n, state.nowMs), n.num == selected) },
            waypoints = state.data.waypoints.mapNotNull { w -> if (w.lat == null || w.lon == null) null else MeshMapWaypoint(w.id, w.lat!!, w.lon!!, w.fromNode == self) },
            background = state.mapBackground,
            contentDescription = stringResource(R.string.mesh_map_a11y),
            onPinTap = { selected = it },
            onLongPress = if (state.live.canSend) { lat, lon -> placing = lat to lon } else null,
            recentreKey = recentre,
            modifier = Modifier.fillMaxWidth().height(520.dp).background(c.surface, SotreusTheme.shapes.listCard),
        )
        IconButton(
            onClick = { recentre++ },
            modifier = Modifier.align(Alignment.TopEnd).padding(SotreusTheme.spacing.m).size(SotreusTheme.sizes.minTouch).background(c.surface, SotreusTheme.shapes.tile),
        ) { Icon(SotreusIcons.Centre, contentDescription = stringResource(R.string.mesh_map_centre), tint = c.text, modifier = Modifier.size(SotreusTheme.sizes.iconSize)) }
        selected?.let { num -> state.nodesByNum[num] }?.let { n ->
            SelectedNodeSheet(n, state, { onConversation("dm:${n.num}") }, { onNode(n.num) }, Modifier.align(Alignment.BottomCenter).padding(SotreusTheme.spacing.m))
        }
    }
    MapLegend()
    Text(stringResource(R.string.mesh_osm), style = SotreusTheme.typography.monoLabelS, color = c.textDim)
    if (state.live.canSend) Text(stringResource(R.string.mesh_map_hint), style = SotreusTheme.typography.caption, color = c.textDim)
    placing?.let { (lat, lon) ->
        TextInputDialog(
            title = stringResource(R.string.mesh_waypoint_title),
            initial = "",
            placeholder = stringResource(R.string.mesh_waypoint_placeholder),
            confirm = stringResource(R.string.mesh_waypoint_send),
            dismiss = stringResource(R.string.mesh_cancel),
            onConfirm = { name -> onAddWaypoint(lat, lon, name.ifBlank { "Waypoint" }); placing = null },
            onDismiss = { placing = null },
        )
    }
}

@Composable
private fun SelectedNodeSheet(n: MeshNodeEntity, state: MeshUi, onMessage: () -> Unit, onDetails: () -> Unit, modifier: Modifier) {
    val c = SotreusTheme.colors
    Column(
        modifier.fillMaxWidth().background(c.surfaceRaised, SotreusTheme.shapes.featureCard).padding(SotreusTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${shortTag(n, n.num)} · ${n.title()}", style = SotreusTheme.typography.rowTitle, color = c.text)
                Text(stringResource(R.string.mesh_reported_by, ago(state.nowMs, n.positionMs), precisionLabel(n.precisionBits)), style = SotreusTheme.typography.caption, color = c.textMuted)
            }
            StateChip(stringResource(R.string.mesh_network), ChipTone.NETWORK)
        }
        val b = between(state.phone, n, state.nowMs)
        when {
            b != null -> Text(stringResource(R.string.mesh_from_this_phone, distanceLabel(b)), style = SotreusTheme.typography.rowTitle, color = c.text)
            isStale(n, state.nowMs) -> StateChip(stringResource(R.string.mesh_chip_stale), ChipTone.DASHED)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
            GhostButton(stringResource(R.string.mesh_action_message), onMessage, Modifier.weight(1f))
            GhostButton(stringResource(R.string.mesh_details), onDetails, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MapLegend() {
    val c = SotreusTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendDot(c.text); MonoLabel(stringResource(R.string.mesh_legend_phone), small = true)
        LegendDot(c.reservedV11Network); MonoLabel(stringResource(R.string.mesh_legend_node), small = true)
        LegendDot(c.reservedV11Network, hollow = true); MonoLabel(stringResource(R.string.mesh_legend_waypoint), small = true)
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, hollow: Boolean = false) {
    Box(
        Modifier.size(8.dp).then(
            if (hollow) Modifier.background(SotreusTheme.colors.ink, SotreusTheme.shapes.pill).padding(1.dp).background(color, SotreusTheme.shapes.pill).padding(1.5.dp).background(SotreusTheme.colors.ink, SotreusTheme.shapes.pill)
            else Modifier.background(color, SotreusTheme.shapes.pill),
        ),
    )
}

// ---- Position disclosure (M8) -------------------------------------------------------------------

internal enum class PositionChoice { EDGE, PHONE, MAP, OFF }

@Composable
internal fun PositionDisclosure(state: MeshUi, onChoose: (PositionChoice) -> Unit) {
    val c = SotreusTheme.colors
    // A stationary Edge with a set position sends that; otherwise this phone's location (§3.1).
    val edgePos = state.edgePosition
    var choice by rememberSaveable { mutableStateOf(if (edgePos != null) PositionChoice.EDGE else PositionChoice.PHONE) }
    val cfg = state.live.config
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        MonoLabel(stringResource(R.string.mesh_disclosure_kicker, state.live.config?.shortName.orEmpty()), small = true)
        Text(stringResource(R.string.mesh_disclosure_title), style = SotreusTheme.typography.displayM, color = c.text)
        Text(stringResource(if (edgePos != null) R.string.mesh_disclosure_lead_stationary else R.string.mesh_disclosure_lead), style = SotreusTheme.typography.body, color = c.textSoft)
        val channels = cfg?.channels.orEmpty().mapIndexed { i, ch -> i to ch }.filter { it.second.positionPrecision > 0 }
        app.sotreus.core.ui.KeyValueTable(
            buildList {
                add(stringResource(R.string.mesh_disclosure_who) to stringResource(R.string.mesh_disclosure_who_value))
                channels.forEach { (i, ch) -> add("${channelName(cfg, i)} · ${keyLabel(ch)}" to precisionLabel(ch.positionPrecision)) }
                add(stringResource(R.string.mesh_disclosure_often) to stringResource(R.string.mesh_disclosure_often_value, ((cfg?.position?.intervalS ?: 900) / 60).toInt()))
                add(stringResource(R.string.mesh_disclosure_when) to stringResource(R.string.mesh_disclosure_when_value))
                add(stringResource(R.string.mesh_disclosure_android) to stringResource(R.string.mesh_disclosure_android_value))
                add(stringResource(R.string.mesh_disclosure_change) to stringResource(R.string.mesh_disclosure_change_value))
            },
        )
        RadioGroupRows(
            listOfNotNull(
                edgePos?.let { (_, _, acc) -> RadioOption(PositionChoice.EDGE, stringResource(R.string.mesh_disclosure_edge, "%.0f".format(acc))) },
                RadioOption(PositionChoice.PHONE, stringResource(R.string.mesh_disclosure_share)),
                RadioOption(PositionChoice.MAP, stringResource(R.string.mesh_disclosure_map)),
                RadioOption(PositionChoice.OFF, stringResource(R.string.mesh_disclosure_off)),
            ),
            choice, { choice = it },
        )
        PrimaryButton(stringResource(R.string.mesh_continue), { onChoose(choice) })
    }
}

internal const val STALE_ROW_MS = 2 * 60 * 60 * 1000L

// ---- Previews (FakeSotreusData) -----------------------------------------------------------------

private fun previewState(link: EdgeLinkState = EdgeLinkState.Connected(517)) = MeshUi(
    live = FakeSotreusData.meshLive, link = link,
    data = MeshData(FakeSotreusData.meshConversations, FakeSotreusData.meshNodes + listOf(), FakeSotreusData.meshWaypoints),
    phone = app.sotreus.sensing.LocationSource.Fix(FakeSotreusData.NOW, FakeSotreusData.PHONE_LAT, FakeSotreusData.PHONE_LON, 8f),
    nowMs = FakeSotreusData.NOW,
)

@Preview(widthDp = 390, heightDp = 1040, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun MeshMessagesPreview() {
    SotreusTheme { MeshContent(previewState(), {}, {}, {}) }
}

@Preview(widthDp = 390, heightDp = 1120, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun MeshNodesPreview() {
    SotreusTheme { MeshContent(previewState(), {}, {}, {}, initialTab = MeshTab.NODES) }
}

@Preview(widthDp = 390, heightDp = 980, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun MeshMapPreview() {
    SotreusTheme { MeshContent(previewState(), {}, {}, {}, initialTab = MeshTab.MAP) }
}

/** Out of reach: what is stored stays readable; sending is off. */
@Preview(widthDp = 390, heightDp = 900, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun MeshAwayPreview() {
    SotreusTheme { MeshContent(previewState(EdgeLinkState.Lost(FakeSotreusData.NOW - 600_000, null)).let { it.copy(live = it.live.copy(connected = false, sharing = false)) }, {}, {}, {}) }
}

@Preview(widthDp = 390, heightDp = 1180, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun MeshDisclosurePreview() {
    SotreusTheme { PositionDisclosure(previewState().let { it.copy(live = it.live.copy(positionChoice = null, sharing = false)) }) {} }
}

/** A stationary Edge with a set position: that position is offered first. */
@Preview(widthDp = 390, heightDp = 1240, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun MeshDisclosureStationaryPreview() {
    SotreusTheme {
        PositionDisclosure(previewState().let { it.copy(live = it.live.copy(positionChoice = null, sharing = false), edgePosition = Triple(25.58095, 85.13768, 9f)) }) {}
    }
}
