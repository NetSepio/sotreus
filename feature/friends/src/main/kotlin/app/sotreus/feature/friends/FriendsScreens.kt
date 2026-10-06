package app.sotreus.feature.friends

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.repository.FriendRepository
import app.sotreus.core.data.repository.ProfileRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.entity.FriendEntity
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.navigation.QrShowRoute
import app.sotreus.core.ui.Avatar
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.CompactButton
import app.sotreus.core.ui.ConfirmDialog
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SectionHeader
import app.sotreus.core.ui.SotreusSwitch
import app.sotreus.core.ui.ageShort
import app.sotreus.core.ui.dateShort
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FriendsUiState(val friends: List<FriendEntity> = emptyList(), val presence: Boolean = false, val displayName: String? = null)

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val friends: FriendRepository,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    val state: StateFlow<FriendsUiState> = combine(friends.all, settings.settings, profiles.profile) { f, s, p -> FriendsUiState(f, s.nearbyPresence, p?.displayName) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FriendsUiState())

    val pendingInvite = friends.pendingInvite

    fun setPresence(on: Boolean) = viewModelScope.launch { settings.setNearbyPresence(on) }
    fun remove(id: Long) = viewModelScope.launch { friends.remove(id) }
    fun add(text: String, onResult: (FriendRepository.AddResult) -> Unit) = viewModelScope.launch { onResult(friends.addFromInvite(text)) }
    fun clearInvite() { friends.pendingInvite.value = null }

    suspend fun invite(fallbackName: String): String {
        profiles.ensureProfile()
        return friends.myInvite(profiles.profile.first()?.displayName ?: fallbackName)
    }
}

/** Screen 15. Presence is off by default and turning it on asks for Bluetooth advertise in context. */
@Composable
internal fun FriendsScreen(navigate: (Any) -> Unit, onBack: () -> Unit, vm: FriendsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val invite by vm.pendingInvite.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = SotreusTheme.colors
    var message by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<FriendEntity?>(null) }
    val fallbackName = stringResource(R.string.qr_default_name)
    val msgAdded = stringResource(R.string.add_result_added, "%s")
    val msgAlready = stringResource(R.string.add_result_already)
    val msgInvalid = stringResource(R.string.add_result_invalid)
    val msgSelf = stringResource(R.string.add_result_self)
    val scanPrompt = stringResource(R.string.scan_prompt)
    val shareTitle = stringResource(R.string.invite_share_title)
    val inviteTemplate = stringResource(R.string.invite_text, "%s")
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun onResult(r: FriendRepository.AddResult) {
        message = when (r) {
            is FriendRepository.AddResult.Added -> msgAdded.format(r.name)
            FriendRepository.AddResult.AlreadyFriends -> msgAlready
            FriendRepository.AddResult.NotAnInvite -> msgInvalid
            FriendRepository.AddResult.Yourself -> msgSelf
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { vm.add(it, ::onResult) } }
    val advertise = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.setPresence(true) }

    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.friends_title))
        Column(
            Modifier.fillMaxWidth().background(c.surface, SotreusTheme.shapes.featureCard).border(1.dp, c.lineStrong, SotreusTheme.shapes.featureCard).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xxl)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MonoLabel(stringResource(if (state.presence) R.string.presence_kicker_on else R.string.presence_kicker_off), small = true)
                    Text(stringResource(R.string.presence_title), style = SotreusTheme.typography.bodyL.copy(fontWeight = FontWeight.Medium), color = c.text)
                }
                SotreusSwitch(state.presence, enabled = state.friends.isNotEmpty()) { on ->
                    if (!on) {
                        vm.setPresence(false)
                    } else if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                        advertise.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
                    } else {
                        vm.setPresence(true)
                    }
                }
            }
            Text(stringResource(R.string.presence_body), style = SotreusTheme.typography.bodyS.copy(fontSize = SotreusTheme.typography.bodyS.fontSize * 1.04f), color = c.textSoft)
            Row(
                Modifier.fillMaxWidth().background(c.attentionSurface, SotreusTheme.shapes.tile).border(1.dp, c.attentionLine, SotreusTheme.shapes.tile).padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l),
            ) {
                Icon(SotreusIcons.Broadcast, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                // Required copy (HANDOFF_V1_UI.md §8): "This transmits."
                Text(stringResource(R.string.presence_transmits), style = SotreusTheme.typography.caption, color = c.textSoft)
            }
            if (state.friends.isEmpty()) Text(stringResource(R.string.presence_needs_friend), style = SotreusTheme.typography.caption, color = c.textDim)
        }
        Column {
            SectionHeader(stringResource(R.string.approved, state.friends.size))
            if (state.friends.isEmpty()) CaveatBox(stringResource(R.string.friends_title), stringResource(R.string.friends_empty))
            state.friends.forEachIndexed { i, f ->
                val now = System.currentTimeMillis()
                InfoRow(
                    title = f.displayName,
                    subtitle = when {
                        f.lastNearbyMs != null && f.lastNearbyPlace != null -> stringResource(R.string.friend_nearby, ageShort(now - f.lastNearbyMs!!) + " ago", f.lastNearbyPlace!!)
                        f.lastNearbyMs != null -> stringResource(R.string.friend_nearby_no_place, ageShort(now - f.lastNearbyMs!!) + " ago")
                        else -> stringResource(R.string.friend_added_not_seen, dateShort(f.addedAtMs))
                    },
                    leading = { Avatar(f.displayName) },
                    showDivider = i < state.friends.lastIndex,
                ) { CompactButton(stringResource(R.string.friend_remove), { removing = f }) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            MonoLabel(stringResource(R.string.add_friend))
            Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                TileButton(stringResource(R.string.my_qr), Modifier.weight(1f)) { navigate(QrShowRoute) }
                TileButton(stringResource(R.string.scan_qr), Modifier.weight(1f)) {
                    scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(scanPrompt).setBeepEnabled(false).setOrientationLocked(false))
                }
                TileButton(stringResource(R.string.invite_link), Modifier.weight(1f)) {
                    scope.launch {
                        val link = vm.invite(fallbackName)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteTemplate.format(link))
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    }
                }
            }
        }
        Text(stringResource(R.string.friends_note), style = SotreusTheme.typography.bodyS, color = c.textDim)
    }
    message?.let { m ->
        AlertDialog(
            onDismissRequest = { message = null },
            containerColor = c.surfaceRaised,
            text = { Text(m, style = SotreusTheme.typography.body, color = c.text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.ok), color = c.accent) } },
        )
    }
    removing?.let { f ->
        ConfirmDialog(
            stringResource(R.string.friend_remove_title, f.displayName), stringResource(R.string.friend_remove_body, f.displayName),
            stringResource(R.string.friend_remove), stringResource(R.string.cancel), { vm.remove(f.id); removing = null }, { removing = null },
        )
    }
    invite?.let { link ->
        val name = runCatching { android.net.Uri.parse(link).getQueryParameter("n") }.getOrNull().orEmpty()
        ConfirmDialog(
            stringResource(R.string.invite_confirm_title, name), stringResource(R.string.invite_confirm_body),
            stringResource(R.string.add), stringResource(R.string.cancel),
            { vm.clearInvite(); vm.add(link, ::onResult) }, { vm.clearInvite() }, destructive = false,
        )
    }
}

@Composable
private fun TileButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = SotreusTheme.shapes.listCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, SotreusTheme.colors.lineStrong),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) { Text(label, style = SotreusTheme.typography.bodyS.copy(fontWeight = FontWeight.Medium), color = SotreusTheme.colors.text) }
}

/** QR show (not designed): the invite as a QR code, composed in a panel. */
@Composable
internal fun QrShowScreen(onBack: () -> Unit, vm: FriendsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val fallback = stringResource(R.string.qr_default_name)
    val bitmap by produceState<Bitmap?>(null, state.displayName) { value = qr(vm.invite(fallback), 768) }
    val c = SotreusTheme.colors
    val name = state.displayName ?: fallback
    val qrDescription = stringResource(R.string.qr_a11y, name)
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.qr_title), lead = stringResource(R.string.qr_lead))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(c.text, SotreusTheme.shapes.hero).padding(20.dp), contentAlignment = Alignment.Center) {
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = qrDescription, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            }
        }
        Text(name, style = SotreusTheme.typography.titleS, color = c.text)
        if (state.displayName == null) CaveatBox(stringResource(R.string.qr_title), stringResource(R.string.qr_name_missing))
        CaveatBox(stringResource(R.string.qr_title), stringResource(R.string.qr_contains))
    }
}

private fun qr(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF0B0E13.toInt() else 0xFFE9E6DF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
