package app.sotreus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.crypto.Base58
import app.sotreus.core.data.device.DeviceProfileRepository
import app.sotreus.core.data.repository.FriendRepository
import app.sotreus.core.data.repository.ProfileRepository
import app.sotreus.core.data.repository.SolanaGateway
import app.sotreus.core.database.entity.LinkedIdentityEntity
import app.sotreus.core.database.entity.LocalProfileEntity
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.navigation.WalletRoute
import app.sotreus.core.ui.Avatar
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CardStyle
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.CompactButton
import app.sotreus.core.ui.ConfirmDialog
import app.sotreus.core.ui.DashedPanel
import app.sotreus.core.ui.DestructiveTextButton
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.PillField
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.SotreusCard
import app.sotreus.core.ui.StateChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(val profile: LocalProfileEntity? = null, val wallet: LinkedIdentityEntity? = null, val solana: Boolean = false)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val friends: FriendRepository,
    private val gateway: SolanaGateway,
    device: DeviceProfileRepository,
) : ViewModel() {
    val state: StateFlow<ProfileUiState> = combine(profiles.profile, profiles.wallet, device.profile) { p, w, d -> ProfileUiState(p, w, d.isSolanaMobile) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    init {
        viewModelScope.launch { profiles.ensureProfile() }
    }

    fun save(name: String, handle: String) = viewModelScope.launch { profiles.update(name, handle) }
    fun rotate() = viewModelScope.launch { friends.rotatePresenceIdentity() }
    fun disconnect() = viewModelScope.launch {
        runCatching { gateway.disconnect() }
        profiles.unlinkWallet()
    }
}

/**
 * Local profile (G3 adapted). This version has no account sign-in: the profile is local, a
 * presence key is app-generated, and on Solana Mobile devices a wallet can be linked.
 */
@Composable
internal fun ProfileScreen(navigate: (Any) -> Unit, onBack: () -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = SotreusTheme.colors
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(0) }
    LaunchedEffect(state.profile?.id) {
        name = state.profile?.displayName.orEmpty()
        handle = state.profile?.handle.orEmpty()
    }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xxl)) {
            Avatar(name.ifBlank { "·" }, size = 64.dp, serif = true)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name.ifBlank { stringResource(R.string.profile_local) }, style = SotreusTheme.typography.titleS, color = c.text)
                Text(
                    (if (handle.isNotBlank()) "@$handle · " else "") + stringResource(R.string.profile_real_name),
                    style = SotreusTheme.typography.bodyS,
                    color = c.textMuted,
                )
            }
        }
        PillField(name, { name = it; saved = false }, stringResource(R.string.profile_name_hint), stringResource(R.string.profile_name))
        PillField(handle, { handle = it.removePrefix("@"); saved = false }, stringResource(R.string.profile_handle_hint), stringResource(R.string.profile_handle))
        GhostButton(stringResource(if (saved) R.string.profile_saved else R.string.profile_save), { vm.save(name, handle); saved = true }, strong = true)

        if (state.solana) {
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
                MonoLabel(stringResource(R.string.profile_linked))
                val w = state.wallet
                if (w != null) {
                    SotreusCard(style = CardStyle.SURFACE, padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(w.walletLabel ?: stringResource(R.string.profile_wallet_card), style = SotreusTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = c.text)
                                Text(Base58.abbreviate(w.publicKey), style = SotreusTheme.typography.monoValue, color = c.textMuted)
                            }
                            StateChip(stringResource(R.string.profile_signed_in), ChipTone.NEUTRAL, small = false)
                        }
                    }
                } else {
                    PrimaryButton(stringResource(R.string.profile_connect_wallet), { navigate(WalletRoute) })
                }
            }
        }

        Column {
            MonoLabel(stringResource(R.string.profile_separate))
            InfoRow(stringResource(R.string.profile_presence_identity), subtitle = stringResource(R.string.profile_presence_sub), titleStyleBody = true) {
                CompactButton(stringResource(R.string.profile_rotate), { confirm = 1 })
            }
            InfoRow(stringResource(R.string.profile_devices), subtitle = stringResource(R.string.profile_this_phone), titleStyleBody = true, showDivider = false) {
                Text("1", style = SotreusTheme.typography.bodyS, color = c.textMuted)
            }
        }
        DashedPanel {
            Row(Modifier, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                Icon(SotreusIcons.Lock, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.profile_local_note), style = SotreusTheme.typography.bodyS, color = c.textSoft, modifier = Modifier)
            }
            Spacer(Modifier.size(8.dp))
        }
        Spacer(Modifier.weight(1f))
        if (state.solana && state.wallet != null) DestructiveTextButton(stringResource(R.string.profile_disconnect), { confirm = 2 })
    }
    if (confirm == 1) {
        ConfirmDialog(stringResource(R.string.profile_rotate_title), stringResource(R.string.profile_rotate_body), stringResource(R.string.profile_rotate), stringResource(R.string.cancel), { confirm = 0; vm.rotate() }, { confirm = 0 }, destructive = false)
    }
    if (confirm == 2) {
        ConfirmDialog(stringResource(R.string.profile_disconnect_title), stringResource(R.string.profile_disconnect_body), stringResource(R.string.confirm), stringResource(R.string.cancel), { confirm = 0; vm.disconnect() }, { confirm = 0 })
    }
}
