package app.sotreus.feature.edge

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sotreus.core.database.entity.EdgeDeviceEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.EdgeDeployment
import app.sotreus.core.model.SensingSource
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.ConfirmDialog
import app.sotreus.core.ui.DestructiveTextButton
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.KeyValueTable
import app.sotreus.core.ui.NavRow
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.SegmentedToggle
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.SwitchRow
import app.sotreus.core.ui.WarningBanner
import app.sotreus.core.ui.ageShort
import app.sotreus.core.ui.clockTime
import app.sotreus.edge.android.EdgePairing
import app.sotreus.edge.link.EdgeLinkState
import app.sotreus.edge.protocol.EdgeMode

/** Settings › Sotreus Edge: pairing when none is paired, otherwise the device. */
@Composable
internal fun EdgeScreen(onBack: () -> Unit, onPickOnMap: () -> Unit = {}, vm: EdgeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onPicked(if (r.resultCode == Activity.RESULT_OK) r.data else null)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it } && activity != null) vm.startPairing(activity) { picker.launch(IntentSenderRequest.Builder(it).build()) }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.startAveraging() }
    // Android 12+: the system picker. Android 8–11: the app's own scan (the platform picker crashes in crowded places).
    val onPair = { if (vm.useSystemPicker) permission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) else vm.startScanning() }
    val device = state.device
    // Pairing again keeps the device (and its records) and runs the same steps.
    if (device == null || state.pairing !is PairStep.Intro) {
        EdgePairContent(state, onBack, onPair, vm::pick, { d, t -> vm.finishSetup(d, t) })
    } else {
        EdgeDeviceContent(
            state, device, onBack, vm, onPair,
            PositionActions(
                setFromPhone = vm::startAveraging,
                save = vm::savePosition,
                pickOnMap = onPickOnMap,
                typed = vm::setTypedPosition,
                allowLocation = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
            ),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ---- Pairing -----------------------------------------------------------------------------------

@Composable
internal fun EdgePairContent(
    state: EdgeUiState,
    onBack: () -> Unit,
    onPair: () -> Unit,
    onPick: (EdgePairing.FoundEdge) -> Unit,
    onFinish: (EdgeDeployment, Boolean) -> Unit,
) {
    val c = SotreusTheme.colors
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack, title = stringResource(R.string.edge_title))
        when (val step = state.pairing) {
            PairStep.Intro, PairStep.Picking -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { EdgeArt(Modifier.width(160.dp)) }
                Text(stringResource(R.string.edge_intro), style = SotreusTheme.typography.body, color = c.textSoft)
                PrimaryButton(stringResource(R.string.edge_pair), onPair, enabled = step == PairStep.Intro)
            }
            is PairStep.Scanning -> {
                Text(stringResource(R.string.edge_scan_title), style = SotreusTheme.typography.rowTitle, color = c.text)
                Column { step.found.forEach { f -> NavRow(f.name, { onPick(f) }, value = stringResource(R.string.edge_scan_signal, f.rssi).replace("-", "−")) } }
                when {
                    !step.done && step.found.isEmpty() -> Text(stringResource(R.string.edge_scan_searching), style = SotreusTheme.typography.caption, color = c.textDim)
                    step.done && step.found.isEmpty() -> {
                        Text(stringResource(R.string.edge_scan_none), style = SotreusTheme.typography.caption, color = c.textDim)
                        PrimaryButton(stringResource(R.string.edge_try_again), onPair)
                    }
                }
            }
            PairStep.Bonding -> {
                Text(stringResource(R.string.edge_code_title), style = SotreusTheme.typography.rowTitle, color = c.text)
                Text(stringResource(R.string.edge_code_body), style = SotreusTheme.typography.body, color = c.textSoft)
            }
            is PairStep.Failed -> {
                WarningBanner(step.reason)
                PrimaryButton(stringResource(R.string.edge_try_again), onPair)
            }
            is PairStep.Setup -> SetupStep(onFinish)
        }
    }
}

@Composable
private fun SetupStep(onFinish: (EdgeDeployment, Boolean) -> Unit) {
    var deployment by remember { mutableStateOf(EdgeDeployment.TRAVEL) }
    var tagging by remember { mutableStateOf(true) }
    SegmentedToggle(
        listOf(EdgeDeployment.TRAVEL to stringResource(R.string.edge_travel), EdgeDeployment.STATIONARY to stringResource(R.string.edge_stationary)),
        deployment,
        { deployment = it },
    )
    if (deployment == EdgeDeployment.TRAVEL) SwitchRow(stringResource(R.string.edge_tagging), tagging, { tagging = it })
    PrimaryButton(stringResource(R.string.edge_done), { onFinish(deployment, tagging && deployment == EdgeDeployment.TRAVEL) })
}

// ---- Device ------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EdgeDeviceContent(
    state: EdgeUiState,
    device: EdgeDeviceEntity,
    onBack: () -> Unit,
    vm: EdgeViewModel? = null,
    onPairAgain: () -> Unit = {},
    position: PositionActions = PositionActions(),
) {
    val c = SotreusTheme.colors
    val live = state.live
    val st = live.status
    var confirmForget by remember { mutableStateOf(false) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack, title = stringResource(R.string.edge_title))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
            EdgeArt(Modifier.width(112.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
                Text(stringResource(R.string.edge_name, device.suffix), style = SotreusTheme.typography.rowTitle, color = c.text)
                Text(stringResource(R.string.edge_firmware, live.info?.firmware ?: device.firmware), style = SotreusTheme.typography.caption, color = c.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xs)) {
                    when {
                        live.pairingLost -> StateChip(stringResource(R.string.edge_chip_not_paired), ChipTone.NEUTRAL)
                        st?.mode == EdgeMode.MESH -> StateChip(stringResource(R.string.edge_chip_mesh), ChipTone.NEUTRAL)
                        live.link is EdgeLinkState.Connected -> StateChip(stringResource(R.string.edge_chip_connected), ChipTone.ACCENT)
                        live.link == EdgeLinkState.Connecting || live.link == EdgeLinkState.Bonding -> StateChip(stringResource(R.string.edge_chip_connecting), ChipTone.NEUTRAL)
                        else -> StateChip(stringResource(R.string.edge_chip_out_of_reach), ChipTone.NEUTRAL)
                    }
                    st?.battery?.percentApprox?.takeIf { st.battery?.present == true }?.let { StateChip(stringResource(R.string.edge_chip_battery, it), ChipTone.NEUTRAL) }
                }
            }
        }
        state.message?.let { WarningBanner(it) }
        if (live.pairingLost) {
            WarningBanner(stringResource(R.string.edge_pairing_lost))
            PrimaryButton(stringResource(R.string.edge_pair_again), onPairAgain)
        }
        KeyValueTable(
            listOfNotNull(
                live.linkRssi?.let { stringResource(R.string.edge_kv_link) to stringResource(R.string.edge_kv_link_value, it, stringResource(if (live.together) R.string.edge_together else R.string.edge_apart)).replace("-", "−") },
                stringResource(R.string.edge_kv_last_sync) to when {
                    live.syncing -> stringResource(R.string.edge_kv_syncing, "%,d".format(live.syncCursor), "%,d".format(live.syncHead))
                    device.lastSyncMs != null -> ageShort(state.nowMs - device.lastSyncMs!!)
                    else -> stringResource(R.string.edge_never)
                },
                st?.log?.let { stringResource(R.string.edge_kv_records) to "%,d".format(it.count) },
                (stringResource(R.string.edge_kv_untimed) to state.untimed.toString()).takeIf { state.untimed > 0 },
                (stringResource(R.string.edge_position) to (device.placeAccuracyM?.let { stringResource(R.string.edge_position_value, "%.0f".format(it), placeSourceLabel(device.placeSource)) } ?: stringResource(R.string.edge_position_none)))
                    .takeIf { device.deployment == EdgeDeployment.STATIONARY },
            ),
        )
        SegmentedToggle(
            listOf(EdgeDeployment.TRAVEL to stringResource(R.string.edge_travel), EdgeDeployment.STATIONARY to stringResource(R.string.edge_stationary)),
            device.deployment,
            { vm?.setDeployment(it) },
        )
        if (device.deployment == EdgeDeployment.TRAVEL) {
            SwitchRow(stringResource(R.string.edge_tagging), device.phoneTagging, { vm?.setTagging(it) })
        } else {
            device.placementHeldSinceMs?.let { held ->
                WarningBanner(stringResource(R.string.edge_moved, clockTime(held)))
                Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                    GhostButton(stringResource(R.string.edge_moved_still), { vm?.confirmStillHere() }, Modifier.weight(1f))
                    GhostButton(stringResource(R.string.edge_moved_moved), { vm?.markMoved() }, Modifier.weight(1f))
                }
            }
            StationaryPosition(state.position, position)
        }
        if (live.handOff && !live.syncing) Text(stringResource(R.string.edge_hand_off), style = SotreusTheme.typography.bodyS, color = c.textMuted)
        else PrimaryButton(stringResource(R.string.edge_sync_now), { vm?.syncNow() }, enabled = live.connected && !live.syncing)
        if (state.source == SensingSource.EDGE) GhostButton(stringResource(R.string.edge_use_phone), { vm?.usePhoneRadios() })
        else PrimaryButton(stringResource(R.string.edge_use), { vm?.useEdge() })
        DestructiveTextButton(stringResource(R.string.edge_forget), { confirmForget = true })
    }
    if (confirmForget) {
        ConfirmDialog(
            stringResource(R.string.edge_forget), stringResource(R.string.edge_forget_body),
            stringResource(R.string.edge_forget), stringResource(R.string.edge_cancel),
            onConfirm = { vm?.forget(); confirmForget = false }, onDismiss = { confirmForget = false },
        )
    }
}

/** A small flat drawing of the Edge (not a photo). */
@Composable
private fun EdgeArt(modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.edge_device_art),
        contentDescription = stringResource(R.string.edge_photo_a11y),
        contentScale = ContentScale.Fit,
        modifier = modifier.aspectRatio(220f / 160f),
    )
}

@Preview(widthDp = 390, heightDp = 900, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun EdgePairPreview() {
    SotreusTheme { EdgePairContent(EdgeUiState(), {}, {}, {}, { _, _ -> }) }
}

@Preview(widthDp = 390, heightDp = 700, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun EdgeCodePreview() {
    SotreusTheme { EdgePairContent(EdgeUiState(pairing = PairStep.Bonding), {}, {}, {}, { _, _ -> }) }
}

@Preview(widthDp = 390, heightDp = 1300, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun EdgeDevicePreview() {
    SotreusTheme {
        EdgeDeviceContent(
            EdgeUiState(device = FakeSotreusData.edgeDevice, live = FakeSotreusData.edgeLive, source = SensingSource.EDGE, nowMs = FakeSotreusData.NOW),
            FakeSotreusData.edgeDevice, {},
        )
    }
}

@Preview(widthDp = 390, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun EdgeStationaryPreview() {
    SotreusTheme {
        EdgeDeviceContent(
            EdgeUiState(
                device = FakeSotreusData.edgeStationaryDevice, live = FakeSotreusData.edgeLive.copy(handOff = true), source = SensingSource.EDGE,
                position = PositionUi(PositionStep.Estimated(FakeSotreusData.edgeWideEstimate)), nowMs = FakeSotreusData.NOW,
            ),
            FakeSotreusData.edgeStationaryDevice, {},
        )
    }
}
