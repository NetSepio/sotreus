package app.sotreus.feature.mesh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.ConfirmDialog
import app.sotreus.core.ui.DestructiveTextButton
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.NavRow
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.RadioGroupRows
import app.sotreus.core.ui.RadioOption
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SegmentedToggle
import app.sotreus.core.ui.SotreusCard
import app.sotreus.core.ui.SwitchRow
import app.sotreus.core.ui.TextInputDialog
import app.sotreus.core.ui.WarningBanner
import app.sotreus.edge.protocol.mesh.MeshChannel
import app.sotreus.edge.protocol.mesh.MeshNames
import app.sotreus.edge.protocol.mesh.MeshPrecision
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
internal fun ChannelsScreen(onBack: () -> Unit, vm: MeshSettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var replace by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { pending = it } }
    ChannelsContent(
        state, onBack,
        onSave = vm::saveChannels,
        onShare = vm::readLink,
        onScan = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false)) },
        onPaste = { pending = it },
        replace = replace, onReplace = { replace = it },
        onClearMessage = vm::clearMessage,
    )
    pending?.let { url ->
        // Replacing every channel also replaces the radio settings: say so first.
        ConfirmDialog(
            stringResource(if (replace) R.string.mesh_import_replace_title else R.string.mesh_import_add_title),
            stringResource(if (replace) R.string.mesh_import_replace_body else R.string.mesh_import_add_body),
            stringResource(R.string.mesh_import), stringResource(R.string.mesh_cancel),
            onConfirm = { vm.importLink(url, replace); pending = null }, onDismiss = { pending = null }, destructive = replace,
        )
    }
}

/** Channels (mock M6): list, edit, share as a link and QR, import. */
@Composable
internal fun ChannelsContent(
    state: MeshSettingsUi,
    onBack: () -> Unit,
    onSave: (List<MeshChannel>) -> Unit = {},
    onShare: () -> Unit = {},
    onScan: () -> Unit = {},
    onPaste: (String) -> Unit = {},
    replace: Boolean = false,
    onReplace: (Boolean) -> Unit = {},
    onClearMessage: () -> Unit = {},
    initialEdit: Int? = null,
) {
    val c = SotreusTheme.colors
    val context = LocalContext.current
    val cfg = state.live.config
    val can = state.live.connected && cfg != null
    var editing by remember { mutableStateOf(initialEdit) }
    var draft by remember(cfg, editing) { mutableStateOf(editing?.let { cfg?.channels?.getOrNull(it) } ?: newChannel()) }
    var pasting by remember { mutableStateOf(false) }
    var showKey by remember(editing) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack, title = stringResource(R.string.mesh_settings))
        ScreenTitle(stringResource(R.string.mesh_channels))
        if (cfg == null) { WarningBanner(stringResource(R.string.mesh_settings_away)); return@ScreenColumn }
        Text(
            listOf(stringResource(R.string.mesh_channels_count, cfg.channels.size, MeshChannel.MAX), MeshNames.title(cfg.region), MeshNames.title(cfg.preset)).joinToString(" · "),
            style = SotreusTheme.typography.caption, color = c.textMuted,
        )
        state.message?.let { WarningBanner(it); GhostButton(stringResource(R.string.mesh_ok), onClearMessage) }
        if (!state.live.connected) WarningBanner(stringResource(R.string.mesh_settings_away))
        Column {
            cfg.channels.forEachIndexed { i, ch ->
                NavRow(
                    "$i · ${channelName(cfg, i)}",
                    { if (can) editing = if (editing == i) null else i },
                    value = listOf(
                        if (i == 0) stringResource(R.string.mesh_primary) else null,
                        keyLabel(ch),
                        if (ch.positionPrecision == 0) stringResource(R.string.mesh_precision_none) else precisionLabel(ch.positionPrecision),
                    ).filterNotNull().joinToString(" · "),
                    showDivider = i < cfg.channels.lastIndex,
                )
            }
        }
        val idx = editing
        if (can && idx != null) {
            SotreusCard {
                Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                    MonoLabel(stringResource(R.string.mesh_channel_edit, if (idx >= cfg.channels.size) stringResource(R.string.mesh_channel_new) else channelName(cfg, idx)), small = true)
                    app.sotreus.core.ui.PillField(
                        draft.name, { draft = draft.copy(name = it.take(MeshChannel.NAME_MAX_BYTES)) },
                        if (idx == 0) MeshNames.channelForPreset(cfg.preset) else stringResource(R.string.mesh_channel_name_hint), stringResource(R.string.mesh_channel_name),
                    )
                    MonoLabel(stringResource(R.string.mesh_channel_key), small = true)
                    Text(if (showKey) draft.psk else keyLabel(draft), style = SotreusTheme.typography.monoValue, color = c.textSoft)
                    Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                        GhostButton(stringResource(if (showKey) R.string.mesh_key_hide else R.string.mesh_key_show), { showKey = !showKey }, Modifier.weight(1f))
                        GhostButton(stringResource(R.string.mesh_key_new), { draft = draft.copy(psk = randomKey()) }, Modifier.weight(1f))
                    }
                    MonoLabel(stringResource(R.string.mesh_channel_position), small = true)
                    RadioGroupRows(
                        listOf(0, 13, 16, 19, 32).map { bits -> RadioOption(bits, if (bits == 0) stringResource(R.string.mesh_precision_none) else precisionLabel(bits)) },
                        draft.positionPrecision.let { p -> listOf(0, 13, 16, 19, 32).minByOrNull { kotlin.math.abs(it - p) } ?: p },
                        { draft = draft.copy(positionPrecision = it) },
                    )
                    SwitchRow(stringResource(R.string.mesh_uplink), draft.uplink, { draft = draft.copy(uplink = it) }, subtitle = stringResource(R.string.mesh_uplink_note))
                    SwitchRow(stringResource(R.string.mesh_downlink), draft.downlink, { draft = draft.copy(downlink = it) }, subtitle = stringResource(R.string.mesh_downlink_note))
                    PrimaryButton(stringResource(R.string.mesh_channel_save), {
                        val list = cfg.channels.toMutableList()
                        if (idx >= list.size) list += draft else list[idx] = draft
                        onSave(list); editing = null
                    }, enabled = !state.busy)
                    if (idx in 1 until cfg.channels.size) DestructiveTextButton(stringResource(R.string.mesh_channel_delete), { confirmDelete = true })
                }
            }
        }
        if (can && editing == null && cfg.channels.size < MeshChannel.MAX) GhostButton(stringResource(R.string.mesh_channel_add), { editing = cfg.channels.size })

        // Share (§1): a Meshtastic link and its QR code.
        SotreusCard {
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                MonoLabel(stringResource(R.string.mesh_share), small = true)
                val link = state.link
                if (link == null) {
                    GhostButton(stringResource(R.string.mesh_share_show), onShare, enabled = can)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        val bmp = remember(link) { runCatching { qr(link, 360) }.getOrNull() }
                        bmp?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.mesh_share_qr_a11y), modifier = Modifier.size(120.dp).background(c.text, SotreusTheme.shapes.tile).padding(6.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
                            Text(cfg.channels.mapIndexed { i, _ -> channelName(cfg, i) }.joinToString(", "), style = SotreusTheme.typography.rowTitle, color = c.text)
                            Text(link.removePrefix("https://").take(40) + "…", style = SotreusTheme.typography.monoLabelS, color = c.textMuted)
                            Text(stringResource(R.string.mesh_share_note), style = SotreusTheme.typography.caption, color = c.textDim)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                        GhostButton(stringResource(R.string.mesh_copy_link), {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Meshtastic channels", link))
                        }, Modifier.weight(1f))
                        GhostButton(stringResource(R.string.mesh_share_link), {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }, Modifier.weight(1f))
                    }
                }
            }
        }

        // Import.
        SotreusCard {
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                MonoLabel(stringResource(R.string.mesh_import), small = true)
                Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                    GhostButton(stringResource(R.string.mesh_import_scan), onScan, Modifier.weight(1f), enabled = can)
                    GhostButton(stringResource(R.string.mesh_import_paste), { pasting = true }, Modifier.weight(1f), enabled = can)
                }
                SegmentedToggle(listOf(false to stringResource(R.string.mesh_import_add), true to stringResource(R.string.mesh_import_replace_all)), replace, onReplace)
                Text(stringResource(R.string.mesh_import_note), style = SotreusTheme.typography.caption, color = c.textDim)
            }
        }
    }
    if (pasting) TextInputDialog(
        stringResource(R.string.mesh_import_paste), "", "https://meshtastic.org/e/#…", stringResource(R.string.mesh_import), stringResource(R.string.mesh_cancel),
        onConfirm = { t -> if (t.isNotBlank()) onPaste(t.trim()); pasting = false }, onDismiss = { pasting = false },
    )
    val idx = editing
    if (confirmDelete && cfg != null && idx != null) ConfirmDialog(
        stringResource(R.string.mesh_channel_delete), stringResource(R.string.mesh_channel_delete_body, channelName(cfg, idx)),
        stringResource(R.string.mesh_channel_delete), stringResource(R.string.mesh_cancel),
        onConfirm = { onSave(cfg.channels.filterIndexed { i, _ -> i != idx }); editing = null; confirmDelete = false }, onDismiss = { confirmDelete = false },
    )
}

private fun newChannel() = MeshChannel(name = "", psk = randomKey(), positionPrecision = MeshPrecision.NONE)

/** A new private key: 32 random bytes, as the Edge takes them (`base64:`). */
private fun randomKey(): String {
    val b = ByteArray(32)
    java.security.SecureRandom().nextBytes(b)
    return "base64:" + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
}

private fun qr(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF0B0E13.toInt() else 0xFFE9E6DF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

// ---- Previews (FakeSotreusData) -----------------------------------------------------------------

@Preview(widthDp = 390, heightDp = 1760, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun ChannelsPreview() {
    SotreusTheme { ChannelsContent(MeshSettingsUi(FakeSotreusData.meshLive, emptyMap(), FakeSotreusData.NOW), {}, initialEdit = 1) }
}
