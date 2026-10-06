package app.sotreus.feature.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.QuietButton
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.WarningBanner
import app.sotreus.sensing.PermissionGroup
import app.sotreus.sensing.RadioAccess
import app.sotreus.sensing.RadioPermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(val permissions: RadioPermissions) : ViewModel() {
    val access = permissions.access
}

/**
 * Screen 02, shown in context when Now needs scanning. Handles granted, denied, permanently
 * denied (deep link to app settings) and radios switched off (system panels).
 */
@Composable
internal fun PermissionsScreen(onDone: () -> Unit, vm: PermissionsViewModel = hiltViewModel()) {
    val access by vm.access.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var deniedForever by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.permissions.refresh()
        val activity = context.findActivity()
        deniedForever = activity != null && result.any { (perm, granted) ->
            !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm) && askedOnce
        }
        askedOnce = true
        if (vm.permissions.refresh().canScan && vm.permissions.refresh().radiosOff.isEmpty()) onDone()
    }
    PermissionsContent(
        access = access,
        deniedForever = deniedForever,
        onBack = onDone,
        onGrant = { launcher.launch(vm.permissions.scanRequestSet().toTypedArray()); askedOnce = true },
        onOpenSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        },
        onBluetooth = { enableBluetooth(context) },
        onWifi = { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) },
        onLocation = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        onNotNow = onDone,
    )
}

@Composable
internal fun PermissionsContent(
    access: RadioAccess,
    deniedForever: Boolean,
    onBack: () -> Unit,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onBluetooth: () -> Unit,
    onWifi: () -> Unit,
    onLocation: () -> Unit,
    onNotNow: () -> Unit,
) {
    val c = SotreusTheme.colors
    ScreenColumn(top = SotreusTheme.spacing.screenTop) {
        BackTopBar(onBack = onBack) { MonoLabel(stringResource(R.string.perm_step)) }
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
            Text(stringResource(R.string.perm_title), style = SotreusTheme.typography.title, color = c.text)
            Text(stringResource(R.string.perm_lead), style = SotreusTheme.typography.body, color = c.textMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            PermissionCard(SotreusIcons.Bluetooth, stringResource(R.string.perm_nearby), null, stringResource(R.string.perm_nearby_body), PermissionGroup.NEARBY_DEVICES in access.granted, accent = true)
            PermissionCard(SotreusIcons.Wifi, stringResource(R.string.perm_wifi), null, stringResource(R.string.perm_wifi_body), PermissionGroup.NEARBY_WIFI in access.granted, accent = true)
            PermissionCard(SotreusIcons.Location, stringResource(R.string.perm_location), stringResource(R.string.perm_location_suffix), stringResource(R.string.perm_location_body), PermissionGroup.LOCATION in access.granted, accent = true)
            PermissionCard(SotreusIcons.Bell, stringResource(R.string.perm_notifications), stringResource(R.string.perm_optional_suffix), stringResource(R.string.perm_notifications_body), PermissionGroup.NOTIFICATIONS in access.granted, accent = false)
        }
        if (access.canScan && access.radiosOff.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                MonoLabel(stringResource(R.string.perm_radios_off_title))
                if (!access.bluetoothOn) { WarningBanner(stringResource(R.string.perm_bluetooth_off)); GhostButton(stringResource(R.string.perm_turn_on_bluetooth), onBluetooth) }
                if (!access.wifiOn) { WarningBanner(stringResource(R.string.perm_wifi_off)); GhostButton(stringResource(R.string.perm_turn_on_wifi), onWifi) }
                if (!access.locationOn) { WarningBanner(stringResource(R.string.perm_location_off)); GhostButton(stringResource(R.string.perm_turn_on_location), onLocation) }
            }
        }
        CaveatBox(stringResource(R.string.perm_not_asked), stringResource(R.string.perm_not_asked_body))
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            if (deniedForever) {
                WarningBanner(stringResource(R.string.perm_denied_forever))
                PrimaryButton(stringResource(R.string.perm_open_settings), onOpenSettings)
            } else if (access.canScan) {
                PrimaryButton(stringResource(R.string.perm_continue), onNotNow)
            } else {
                PrimaryButton(stringResource(R.string.perm_grant), onGrant)
            }
            QuietButton(stringResource(R.string.perm_not_now), onNotNow)
        }
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: String, suffix: String?, body: String, granted: Boolean, accent: Boolean) {
    val c = SotreusTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.surfaceRaised, SotreusTheme.shapes.panel).border(1.dp, c.line, SotreusTheme.shapes.panel).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(36.dp).background(if (accent) c.attentionSurface else c.surfaceHigh, SotreusTheme.shapes.glyphWell)
                .border(1.dp, if (accent) c.attentionLine else c.lineMid, SotreusTheme.shapes.glyphWell),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = if (accent) c.accent else c.textMuted, modifier = Modifier.size(18.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(title) }
                    suffix?.let { withStyle(SpanStyle(color = c.textDim)) { append(" $it") } }
                },
                style = SotreusTheme.typography.body,
                color = c.text,
            )
            Text(body, style = SotreusTheme.typography.bodyS, color = c.textMuted)
        }
        if (granted) StateChip(stringResource(R.string.perm_granted), ChipTone.ACCENT_FILLED)
    }
}

/** System "turn on Bluetooth" prompt. Needs BLUETOOTH_CONNECT on API 31+; otherwise open settings. */
@SuppressLint("MissingPermission") // Checked just above the request.
private fun enableBluetooth(context: Context) {
    val canAsk = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val intent = if (canAsk) Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    runCatching { context.startActivity(intent) }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(widthDp = 390, heightDp = 900, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun PermissionsPreview() {
    SotreusTheme {
        PermissionsContent(RadioAccess(emptySet(), true, true, true, true), false, {}, {}, {}, {}, {}, {}, {})
    }
}
