package app.sotreus.feature.mesh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.ConfirmDialog
import app.sotreus.core.ui.DestructiveTextButton
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.KeyValueTable
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.NavRow
import app.sotreus.core.ui.RadioGroupRows
import app.sotreus.core.ui.RadioOption
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SwitchRow
import app.sotreus.core.ui.TextInputDialog
import app.sotreus.core.ui.WarningBanner
import app.sotreus.edge.protocol.bool
import app.sotreus.edge.protocol.long
import app.sotreus.edge.protocol.mesh.MeshConfig
import app.sotreus.edge.protocol.mesh.MeshNames
import app.sotreus.edge.protocol.mesh.MeshNodeId
import app.sotreus.edge.protocol.mesh.MeshPositionMode
import app.sotreus.edge.protocol.obj
import app.sotreus.edge.protocol.str

/** A field being edited: a choice from a list, or typed text. */
private sealed interface Editing {
    data class Choice(val title: String, val options: List<Pair<String, String>>, val current: String, val apply: (String) -> Unit) : Editing
    data class Typed(val title: String, val current: String, val apply: (String) -> Unit) : Editing
}

@Composable
internal fun MeshSettingsScreen(onBack: () -> Unit, onChannels: () -> Unit, onPickPosition: () -> Unit, vm: MeshSettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true && Build.VERSION.SDK_INT >= 29) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val allTheTime = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    MeshSettingsContent(
        state, onBack, onChannels,
        locationAllowed = vm.hasLocationPermission(), allTheTime = allTheTime,
        onPosition = { mode ->
            when (mode) {
                MeshPositionMode.FIXED -> onPickPosition()
                MeshPositionMode.CLIENT -> {
                    vm.choosePosition(mode)
                    foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                MeshPositionMode.OFF -> vm.choosePosition(mode)
            }
        },
        onAllowAllTime = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onUseEdgePosition = { state.edgePosition?.let { (lat, lon, _) -> vm.choosePosition(MeshPositionMode.FIXED, lat to lon) } },
        onSet = vm::set, onModule = vm::setModule, onNewIdentity = vm::newIdentity, onTakChat = vm::takChat, onTakPosition = vm::takPosition,
        onClearMessage = vm::clearMessage,
    )
}

/** Mesh settings (mock M7). Written to the Edge at once; nothing here is stored on this phone. */
@Composable
internal fun MeshSettingsContent(
    state: MeshSettingsUi,
    onBack: () -> Unit,
    onChannels: () -> Unit,
    locationAllowed: Boolean = true,
    allTheTime: Boolean = true,
    onPosition: (MeshPositionMode) -> Unit = {},
    onAllowAllTime: () -> Unit = {},
    onUseEdgePosition: () -> Unit = {},
    onSet: (String, Any) -> Unit = { _, _ -> },
    onModule: (String, String, Any) -> Unit = { _, _, _ -> },
    onNewIdentity: () -> Unit = {},
    onTakChat: (String) -> Unit = {},
    onTakPosition: () -> Unit = {},
    onClearMessage: () -> Unit = {},
) {
    val c = SotreusTheme.colors
    val live = state.live
    val cfg = live.config
    val st = live.status
    val can = live.connected && cfg != null
    var editing by remember { mutableStateOf<Editing?>(null) }
    var confirmIdentity by remember { mutableStateOf(false) }
    var confirmLicensed by remember { mutableStateOf(false) }
    var chatting by remember { mutableStateOf(false) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack, title = stringResource(R.string.mesh_title))
        ScreenTitle(stringResource(R.string.mesh_settings))
        Text(stringResource(R.string.mesh_settings_lead, cfg?.shortName.orEmpty().ifBlank { "—" }), style = SotreusTheme.typography.caption, color = c.textMuted)
        state.message?.let { WarningBanner(it); GhostButton(stringResource(R.string.mesh_ok), onClearMessage) }
        if (!live.connected) WarningBanner(stringResource(R.string.mesh_settings_away))
        if (cfg == null) return@ScreenColumn

        // Your position on the mesh (HANDOFF_V3_MESH.md §3.1).
        MonoLabel(stringResource(R.string.mesh_position_header))
        val mode = cfg.position.mode
        RadioGroupRows(
            listOf(
                RadioOption(MeshPositionMode.CLIENT, stringResource(R.string.mesh_position_phone), enabled = can),
                RadioOption(MeshPositionMode.FIXED, stringResource(R.string.mesh_position_map), enabled = can),
                RadioOption(MeshPositionMode.OFF, stringResource(R.string.mesh_position_none), enabled = can),
            ),
            mode, onPosition,
        )
        when (mode) {
            MeshPositionMode.CLIENT -> {
                val sent = live.lastSharedMs?.let { stringResource(R.string.mesh_position_sent, ago(state.nowMs, it)) } ?: stringResource(R.string.mesh_position_not_sent)
                Text(sent, style = SotreusTheme.typography.caption, color = c.textDim)
                when {
                    !locationAllowed -> {
                        WarningBanner(stringResource(R.string.mesh_position_no_permission))
                        GhostButton(stringResource(R.string.mesh_position_allow), { onPosition(MeshPositionMode.CLIENT) })
                    }
                    !allTheTime -> {
                        WarningBanner(stringResource(R.string.mesh_position_on_screen_only))
                        GhostButton(stringResource(R.string.mesh_position_allow_all_time), onAllowAllTime)
                    }
                }
            }
            MeshPositionMode.FIXED -> if (cfg.position.lat != null && cfg.position.lon != null) {
                Text("%.5f, %.5f".format(java.util.Locale.ROOT, cfg.position.lat, cfg.position.lon), style = SotreusTheme.typography.monoValue, color = c.textSoft)
            }
            MeshPositionMode.OFF -> Unit
        }
        // A stationary Edge's set position is where it really is (HANDOFF_V3_MESH.md §3.1).
        state.edgePosition?.let { (lat, lon, acc) ->
            val atEdge = mode == MeshPositionMode.FIXED && cfg.position.lat?.let { kotlin.math.abs(it - lat) < 1e-5 } == true && cfg.position.lon?.let { kotlin.math.abs(it - lon) < 1e-5 } == true
            if (atEdge) Text(stringResource(R.string.mesh_position_is_edge), style = SotreusTheme.typography.caption, color = c.textDim)
            else if (can) GhostButton(stringResource(R.string.mesh_position_use_edge, "%.0f".format(acc)), onUseEdgePosition)
        }
        KeyValueTable(
            cfg.channels.mapIndexed { i, ch -> channelName(cfg, i) to precisionLabel(ch.positionPrecision.takeIf { it > 0 }).let { if (ch.positionPrecision == 0) stringResource(R.string.mesh_precision_none) else it } } +
                (stringResource(R.string.mesh_position_interval) to stringResource(R.string.mesh_minutes, (cfg.position.intervalS / 60).toInt())),
        )

        // Owner.
        MonoLabel(stringResource(R.string.mesh_owner))
        val longTitle = stringResource(R.string.mesh_long_name)
        val shortTitle = stringResource(R.string.mesh_short_name)
        Column {
            NavRow(longTitle, { if (can) editing = Editing.Typed(longTitle, cfg.longName) { onSet("long_name", it) } }, value = cfg.longName)
            NavRow(shortTitle, { if (can) editing = Editing.Typed(shortTitle, cfg.shortName) { onSet("short_name", it.take(4)) } }, value = cfg.shortName)
            NavRow(stringResource(R.string.mesh_node), {}, value = live.selfNum?.let(MeshNodeId::format) ?: cfg.nodeId.orEmpty(), showDivider = false)
        }

        // Radio.
        MonoLabel(stringResource(R.string.mesh_radio))
        st?.presetTooWide?.let { wide -> WarningBanner(stringResource(R.string.mesh_preset_too_wide, MeshNames.title(wide), MeshNames.title(cfg.region), MeshNames.title(st.preset ?: "LONG_FAST"))) }
        val regions = live.regions?.regions ?: listOf(cfg.region)
        val presets = live.regions?.presets ?: listOf(cfg.preset)
        val regionTitle = stringResource(R.string.mesh_region)
        val presetTitle = stringResource(R.string.mesh_preset)
        val roleTitle = stringResource(R.string.mesh_role)
        val hopTitle = stringResource(R.string.mesh_hop_limit)
        val rebroadcastTitle = stringResource(R.string.mesh_rebroadcast)
        val powerTitle = stringResource(R.string.mesh_tx_power)
        val regionDefault = stringResource(R.string.mesh_region_default)
        Column {
            NavRow(regionTitle, { if (can) editing = Editing.Choice(regionTitle, regions.map { it to MeshNames.title(it) }, cfg.region) { onSet("region", it) } },
                value = if (cfg.regionSet) regionName(cfg.region) else stringResource(R.string.mesh_region_unset_short), valueAccent = !cfg.regionSet)
            NavRow(presetTitle, { if (can) editing = Editing.Choice(presetTitle, presets.map { it to MeshNames.title(it) }, cfg.preset) { onSet("preset", it) } }, value = MeshNames.title(cfg.preset))
            NavRow(roleTitle, { if (can) editing = Editing.Choice(roleTitle, (MeshNames.ROLES + cfg.role).distinct().map { it to MeshNames.title(it) }, cfg.role) { onSet("role", it) } }, value = roleLabel(cfg.role))
            NavRow(hopTitle, { if (can) editing = Editing.Choice(hopTitle, (1..7).map { "$it" to "$it" }, "${cfg.hopLimit}") { onSet("hop_limit", it.toInt()) } }, value = "${cfg.hopLimit}")
            NavRow(rebroadcastTitle, { if (can) editing = Editing.Choice(rebroadcastTitle, MeshNames.REBROADCAST.map { it to MeshNames.title(it.uppercase()) }, cfg.rebroadcast) { onSet("rebroadcast", it) } }, value = MeshNames.title(cfg.rebroadcast.uppercase()))
            NavRow(powerTitle, { if (can) editing = Editing.Choice(powerTitle, listOf("0" to regionDefault) + (10..30 step 2).map { "$it" to "$it dBm" }, "${cfg.txPower}") { onSet("tx_power", it.toInt()) } },
                value = if (cfg.txPower == 0) regionDefault else "${cfg.txPower} dBm", showDivider = false)
        }
        SwitchRow(stringResource(R.string.mesh_licensed), cfg.licensed, { on -> if (on) confirmLicensed = true else onSet("licensed", false) }, subtitle = stringResource(R.string.mesh_licensed_note), enabled = can)

        MonoLabel(stringResource(R.string.mesh_channels))
        NavRow(stringResource(R.string.mesh_channels), onChannels, value = cfg.channels.mapIndexed { i, _ -> channelName(cfg, i) }.joinToString(", "), showDivider = false)

        Modules(cfg, can, onSet, onModule) { editing = it }
        Tak(state, can, onModule, onTakPosition, { chatting = true }) { editing = it }
        Status(state)

        MonoLabel(stringResource(R.string.mesh_identity))
        Text(stringResource(R.string.mesh_identity_note), style = SotreusTheme.typography.caption, color = c.textDim)
        if (can) DestructiveTextButton(stringResource(R.string.mesh_identity_new), { confirmIdentity = true })
    }
    when (val e = editing) {
        is Editing.Typed -> TextInputDialog(e.title, e.current, e.title, stringResource(R.string.mesh_save), stringResource(R.string.mesh_cancel),
            onConfirm = { v -> if (v.isNotBlank()) e.apply(v.trim()); editing = null }, onDismiss = { editing = null })
        is Editing.Choice -> ChoiceDialog(e.title, e.options, e.current, { v -> e.apply(v); editing = null }, { editing = null })
        null -> Unit
    }
    if (confirmIdentity) ConfirmDialog(
        stringResource(R.string.mesh_identity_new), stringResource(R.string.mesh_identity_note), stringResource(R.string.mesh_identity_new), stringResource(R.string.mesh_cancel),
        onConfirm = { onNewIdentity(); confirmIdentity = false }, onDismiss = { confirmIdentity = false },
    )
    if (confirmLicensed) ConfirmDialog(
        stringResource(R.string.mesh_licensed), stringResource(R.string.mesh_licensed_confirm), stringResource(R.string.mesh_licensed_on), stringResource(R.string.mesh_cancel),
        onConfirm = { onSet("licensed", true); confirmLicensed = false }, onDismiss = { confirmLicensed = false },
    )
    if (chatting) TextInputDialog(stringResource(R.string.mesh_tak_chat), "", stringResource(R.string.mesh_tak_chat_hint), stringResource(R.string.mesh_send), stringResource(R.string.mesh_cancel),
        onConfirm = { t -> if (t.isNotBlank()) onTakChat(app.sotreus.edge.protocol.mesh.MeshText.clip(t.trim())); chatting = false }, onDismiss = { chatting = false })
}

private fun JsonObjectOrNull(cfg: MeshConfig, module: String): kotlinx.serialization.json.JsonObject? = cfg.raw.obj(module)

@Composable
private fun Modules(cfg: MeshConfig, can: Boolean, onSet: (String, Any) -> Unit, onModule: (String, String, Any) -> Unit, onEdit: (Editing) -> Unit) {
    val c = SotreusTheme.colors
    MonoLabel(stringResource(R.string.mesh_modules))
    val mqtt = JsonObjectOrNull(cfg, "mqtt")
    val mqttOn = mqtt?.bool("enabled") == true
    SwitchRow(stringResource(R.string.mesh_mqtt), mqttOn, { onModule("mqtt", "enabled", it) }, subtitle = stringResource(R.string.mesh_mqtt_note), enabled = can)
    if (mqttOn && mqtt != null) {
        val addrTitle = stringResource(R.string.mesh_mqtt_address)
        val userTitle = stringResource(R.string.mesh_mqtt_user)
        val passTitle = stringResource(R.string.mesh_mqtt_password)
        Column {
            NavRow(addrTitle, { if (can) onEdit(Editing.Typed(addrTitle, mqtt.str("address").orEmpty()) { onModule("mqtt", "address", it) }) }, value = mqtt.str("address").orEmpty())
            NavRow(userTitle, { if (can) onEdit(Editing.Typed(userTitle, mqtt.str("username").orEmpty()) { onModule("mqtt", "username", it) }) }, value = mqtt.str("username").orEmpty())
            NavRow(passTitle, { if (can) onEdit(Editing.Typed(passTitle, "") { onModule("mqtt", "password", it) }) },
                value = stringResource(if (mqtt.bool("password_set") == true) R.string.mesh_mqtt_password_set else R.string.mesh_mqtt_password_none), showDivider = false)
        }
        SwitchRow(stringResource(R.string.mesh_mqtt_encryption), mqtt.bool("encryption") != false, { onModule("mqtt", "encryption", it) }, enabled = can)
        SwitchRow(stringResource(R.string.mesh_mqtt_tls), mqtt.bool("tls") == true, { onModule("mqtt", "tls", it) }, enabled = can)
        SwitchRow(stringResource(R.string.mesh_mqtt_json), mqtt.bool("json") == true, { onModule("mqtt", "json", it) }, subtitle = stringResource(R.string.mesh_mqtt_json_note), enabled = can)
        val map = mqtt.obj("map")
        SwitchRow(stringResource(R.string.mesh_mqtt_map), map?.bool("enabled") == true,
            { on -> onModule("mqtt", "map", kotlinx.serialization.json.JsonObject(mapOf("enabled" to kotlinx.serialization.json.JsonPrimitive(on)))) },
            subtitle = stringResource(R.string.mesh_mqtt_map_note), enabled = can)
    }
    val sf = JsonObjectOrNull(cfg, "store_forward")
    SwitchRow(stringResource(R.string.mesh_sf_server), sf?.bool("server") == true, { onModule("store_forward", "server", it) }, subtitle = stringResource(R.string.mesh_sf_server_note), enabled = can)
    val admin = JsonObjectOrNull(cfg, "admin")
    val adminTitle = stringResource(R.string.mesh_admin)
    val adminOpen = stringResource(R.string.mesh_admin_open)
    val adminKeys = stringResource(R.string.mesh_admin_keys)
    val adminOff = stringResource(R.string.mesh_admin_off)
    val adminValue = when { admin?.bool("enabled") == false -> "off"; admin?.str("mode") == "keys" -> "keys"; else -> "open" }
    NavRow(
        adminTitle,
        { if (can) onEdit(Editing.Choice(adminTitle, listOf("open" to adminOpen, "keys" to adminKeys, "off" to adminOff), adminValue) { v ->
            if (v == "off") onModule("admin", "enabled", false) else { onModule("admin", "enabled", true); onModule("admin", "mode", v) }
        }) },
        value = when (adminValue) { "off" -> adminOff; "keys" -> adminKeys; else -> adminOpen }, valueAccent = adminValue == "open",
    )
    val minutes = listOf(0L, 300L, 900L, 1800L, 3600L, 10800L)
    val off = stringResource(R.string.mesh_off)
    fun every(s: Long, label: (Int) -> String) = if (s == 0L) off else label((s / 60).toInt())
    val minLabel: (Int) -> String = { m -> if (m >= 60) "${m / 60} h" else "$m min" }
    val telemetryTitle = stringResource(R.string.mesh_telemetry)
    Column {
        NavRow(telemetryTitle, { if (can) onEdit(Editing.Choice(telemetryTitle, minutes.map { "$it" to every(it, minLabel) }, "${cfg.telemetryIntervalS}") { onSet("telemetry_interval_s", it.toLong()) }) },
            value = every(cfg.telemetryIntervalS, minLabel))
        val rangeTitle = stringResource(R.string.mesh_range_test)
        val range = cfg.raw.long("range_test_interval_s") ?: 0
        NavRow(rangeTitle, { if (can) onEdit(Editing.Choice(rangeTitle, listOf(0L, 30L, 60L, 300L).map { "$it" to if (it == 0L) off else if (it < 60) "$it s" else minLabel((it / 60).toInt()) }, "$range") { onSet("range_test_interval_s", it.toLong()) }) },
            value = if (range == 0L) off else if (range < 60) "$range s" else minLabel((range / 60).toInt()))
        val notifyTitle = stringResource(R.string.mesh_notify)
        val notify = JsonObjectOrNull(cfg, "notify")?.str("mode") ?: "off"
        val notifyLabels = mapOf("off" to off, "message" to stringResource(R.string.mesh_notify_message), "bell" to stringResource(R.string.mesh_notify_bell))
        NavRow(notifyTitle, { if (can) onEdit(Editing.Choice(notifyTitle, notifyLabels.toList(), notify) { onModule("notify", "mode", it) }) }, value = notifyLabels[notify] ?: notify, showDivider = false)
    }
    SwitchRow(stringResource(R.string.mesh_neighbour_info), JsonObjectOrNull(cfg, "neighbor_info")?.bool("enabled") == true, { onModule("neighbor_info", "enabled", it) }, enabled = can)
    SwitchRow(stringResource(R.string.mesh_paxcounter), JsonObjectOrNull(cfg, "paxcounter")?.bool("enabled") == true, { onModule("paxcounter", "enabled", it) }, subtitle = stringResource(R.string.mesh_paxcounter_note), enabled = can)
    SwitchRow(stringResource(R.string.mesh_detection), JsonObjectOrNull(cfg, "detection")?.bool("enabled") == true, { onModule("detection", "enabled", it) }, subtitle = stringResource(R.string.mesh_detection_note), enabled = can)
    SwitchRow(stringResource(R.string.mesh_serial), JsonObjectOrNull(cfg, "serial")?.bool("enabled") == true, { onModule("serial", "enabled", it) }, subtitle = stringResource(R.string.mesh_serial_note), enabled = can)
    SwitchRow(stringResource(R.string.mesh_remote_hardware), JsonObjectOrNull(cfg, "remote_hardware")?.bool("enabled") == true, { onModule("remote_hardware", "enabled", it) }, subtitle = stringResource(R.string.mesh_remote_hardware_note), enabled = can)
    Text(stringResource(R.string.mesh_modules_note), style = SotreusTheme.typography.caption, color = c.textDim)
}

private val TAK_TEAMS = listOf("default", "White", "Yellow", "Orange", "Magenta", "Red", "Maroon", "Purple", "Dark Blue", "Blue", "Cyan", "Teal", "Green", "Dark Green", "Brown")
private val TAK_ROLES = listOf("default", "Team Member", "Team Lead", "HQ", "Sniper", "Medic", "Forward Observer", "RTO", "K9")

@Composable
private fun Tak(state: MeshSettingsUi, can: Boolean, onModule: (String, String, Any) -> Unit, onPosition: () -> Unit, onChat: () -> Unit, onEdit: (Editing) -> Unit) {
    val cfg = state.live.config ?: return
    val tak = JsonObjectOrNull(cfg, "tak")
    val team = tak?.str("team") ?: "default"
    val role = tak?.str("role") ?: "default"
    val dflt = stringResource(R.string.mesh_tak_default)
    MonoLabel(stringResource(R.string.mesh_tak))
    val teamTitle = stringResource(R.string.mesh_tak_team)
    val roleTitle = stringResource(R.string.mesh_tak_role)
    Column {
        NavRow(teamTitle, { if (can) onEdit(Editing.Choice(teamTitle, TAK_TEAMS.map { it to if (it == "default") dflt else it }, team) { onModule("tak", "team", it) }) }, value = if (team == "default") dflt else team)
        NavRow(roleTitle, { if (can) onEdit(Editing.Choice(roleTitle, TAK_ROLES.map { it to if (it == "default") dflt else it }, role) { onModule("tak", "role", it) }) }, value = if (role == "default") dflt else role, showDivider = false)
    }
    val users = state.live.tak
    if (users.isNotEmpty()) {
        KeyValueTable(users.map { u ->
            u.callsign.ifBlank { u.uid.orEmpty() } to listOfNotNull(u.team, u.role, u.agoS?.let { app.sotreus.core.ui.ageShort(it * 1000) }).joinToString(" · ")
        }, title = stringResource(R.string.mesh_tak_users, users.size))
    }
    if (can && state.live.canSend) {
        GhostButton(stringResource(R.string.mesh_tak_send_position), onPosition)
        GhostButton(stringResource(R.string.mesh_tak_chat), onChat)
    }
}

@Composable
private fun Status(state: MeshSettingsUi) {
    val st = state.live.status ?: return
    val c = SotreusTheme.colors
    MonoLabel(stringResource(R.string.mesh_status))
    KeyValueTable(
        listOfNotNull(
            stringResource(R.string.mesh_status_radio) to stringResource(if (st.running) R.string.mesh_status_on else R.string.mesh_status_off),
            st.channelUtilPct?.let { stringResource(R.string.mesh_channel_use) to "%.1f%%".format(java.util.Locale.ROOT, it) },
            st.airUtilTxPct?.let { stringResource(R.string.mesh_own_airtime) to "%.1f%%".format(java.util.Locale.ROOT, it) },
            stringResource(R.string.mesh_polite_hold) to stringResource(if (st.politeHold) R.string.mesh_status_on else R.string.mesh_off),
            stringResource(R.string.mesh_packets) to "%,d / %,d".format(st.rx, st.tx),
            st.storeForwardRouters.firstOrNull()?.let { r -> stringResource(R.string.mesh_sf_routers) to (state.nodes[r]?.label() ?: MeshNodeId.format(r)) },
            stringResource(R.string.mesh_admin_requests) to if (st.adminRefused > 0) stringResource(R.string.mesh_admin_requests_refused, st.adminRequests, st.adminRefused) else "${st.adminRequests}",
        ),
        title = state.live.statusAtMs?.let { stringResource(R.string.mesh_updated, ago(state.nowMs, it)) },
    )
    Text(stringResource(R.string.mesh_status_note), style = SotreusTheme.typography.caption, color = c.textDim)
}

/** A choice from a list, composed from the shared dialog and radio rows. */
@Composable
internal fun ChoiceDialog(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = SotreusTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        titleContentColor = c.text,
        textContentColor = c.textSoft,
        shape = SotreusTheme.shapes.featureCard,
        title = { Text(title, style = SotreusTheme.typography.titleS) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                RadioGroupRows(options.map { RadioOption(it.first, it.second) }, current, onPick)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = SotreusTheme.sizes.minTouch)) {
                Text(stringResource(R.string.mesh_cancel), style = SotreusTheme.typography.button, color = c.textMuted)
            }
        },
    )
}

// ---- Previews (FakeSotreusData) -----------------------------------------------------------------

@Preview(widthDp = 390, heightDp = 2600, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun MeshSettingsPreview() {
    SotreusTheme { MeshSettingsContent(MeshSettingsUi(FakeSotreusData.meshLive, FakeSotreusData.meshNodes.associateBy { it.num }, FakeSotreusData.NOW), {}, {}) }
}

/** Sharing on, but Android lets location run only while Sotreus is on screen. */
@Preview(widthDp = 390, heightDp = 900, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun MeshSettingsOnScreenOnlyPreview() {
    SotreusTheme { MeshSettingsContent(MeshSettingsUi(FakeSotreusData.meshLive, emptyMap(), FakeSotreusData.NOW), {}, {}, allTheTime = false) }
}
