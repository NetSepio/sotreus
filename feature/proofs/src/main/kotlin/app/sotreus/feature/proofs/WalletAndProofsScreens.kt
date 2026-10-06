package app.sotreus.feature.proofs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.crypto.Base58
import app.sotreus.core.data.repository.ProfileRepository
import app.sotreus.core.data.repository.ProofRepository
import app.sotreus.core.data.repository.SolanaGateway
import app.sotreus.core.data.repository.WalletException
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.entity.LinkedIdentityEntity
import app.sotreus.core.database.entity.ProofBatchEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.ProofState
import app.sotreus.core.model.SolanaCluster
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.model.StampingMode
import app.sotreus.core.navigation.ReceiptRoute
import app.sotreus.core.navigation.StampRoute
import app.sotreus.core.navigation.WalletRoute
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.InlineLink
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.PublicPrivateSplit
import app.sotreus.core.ui.QuietButton
import app.sotreus.core.ui.RadioGroupRows
import app.sotreus.core.ui.RadioOption
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.WarningBanner
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.dayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// --- S2 Connect wallet --------------------------------------------------------------------

sealed interface WalletStatus {
    data object Idle : WalletStatus
    data object Working : WalletStatus
    data object NoWallet : WalletStatus
    data object Cancelled : WalletStatus
    data object Unverified : WalletStatus
    data class Failed(val message: String) : WalletStatus
}

@HiltViewModel
class WalletViewModel @Inject constructor(private val gateway: SolanaGateway, private val profiles: ProfileRepository) : ViewModel() {
    val status = MutableStateFlow<WalletStatus>(WalletStatus.Idle)
    val preview: String = gateway.signInPreview(null, SolanaCluster.DEVNET)

    fun signIn(onLinked: () -> Unit) = viewModelScope.launch {
        status.value = WalletStatus.Working
        status.value = try {
            val result = gateway.signIn(SolanaCluster.DEVNET)
            if (!result.signatureVerified) {
                WalletStatus.Unverified
            } else {
                profiles.linkWallet(result.publicKey, SolanaCluster.DEVNET, result.walletLabel)
                onLinked()
                WalletStatus.Idle
            }
        } catch (e: WalletException) {
            when {
                e.noWallet -> WalletStatus.NoWallet
                e.userCancelled -> WalletStatus.Cancelled
                else -> WalletStatus.Failed(e.message ?: "")
            }
        } catch (e: Exception) {
            WalletStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}

/** Screen S2. A Sign In With Solana message — never a transaction — to link the wallet. */
@Composable
internal fun WalletScreen(onLinked: () -> Unit, onBack: () -> Unit, vm: WalletViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val c = SotreusTheme.colors
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack) { StateChip(stringResource(R.string.devnet), ChipTone.DASHED, small = false) }
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            MonoLabel(stringResource(R.string.wallet_kicker))
            Text(stringResource(R.string.wallet_title), style = SotreusTheme.typography.title, color = c.text)
            Text(stringResource(R.string.wallet_lead), style = SotreusTheme.typography.body, color = c.textMuted)
        }
        Column {
            RowDivider(strong = true)
            listOf(R.string.wallet_step1, R.string.wallet_step2, R.string.wallet_step3).forEachIndexed { i, step ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xxl)) {
                    Text("%02d".format(i + 1), style = SotreusTheme.typography.monoValue, color = c.accent, modifier = Modifier.padding(top = 2.dp))
                    Text(stringResource(step), style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f), color = c.textSoft)
                }
                RowDivider(strong = true)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            MonoLabel(stringResource(R.string.wallet_sign_kicker))
            Text(
                vm.preview,
                style = SotreusTheme.typography.monoValue.copy(lineHeight = SotreusTheme.typography.monoValue.fontSize * 1.7f),
                color = c.textSoft,
                modifier = Modifier.fillMaxWidth().background(c.surface, SotreusTheme.shapes.panel).border(1.dp, c.line, SotreusTheme.shapes.panel).padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        when (val s = status) {
            WalletStatus.NoWallet -> WarningBanner(stringResource(R.string.wallet_no_wallet))
            WalletStatus.Cancelled -> WarningBanner(stringResource(R.string.wallet_cancelled))
            WalletStatus.Unverified -> WarningBanner(stringResource(R.string.wallet_unverified))
            is WalletStatus.Failed -> WarningBanner(stringResource(R.string.wallet_failed, s.message))
            else -> Unit
        }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            PrimaryButton(stringResource(if (status == WalletStatus.Working) R.string.wallet_working else R.string.wallet_open), { vm.signIn(onLinked) }, enabled = status != WalletStatus.Working)
            QuietButton(stringResource(R.string.wallet_local_only), onBack)
        }
    }
}

// --- S3 Proofs & Tracking -------------------------------------------------------------------

data class ProofsUiState(
    val wallet: LinkedIdentityEntity? = null,
    val settings: SotreusSettings = SotreusSettings(),
    val batches: List<ProofBatchEntity> = emptyList(),
    val balanceLamports: Long? = null,
)

@HiltViewModel
class ProofsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    proofs: ProofRepository,
    private val gateway: SolanaGateway,
) : ViewModel() {
    private val balance = MutableStateFlow<Long?>(null)
    val message = MutableStateFlow<Int?>(null)
    val state: StateFlow<ProofsUiState> = combine(profiles.wallet, settings.settings, proofs.observeBatches(), balance) { w, s, b, bal -> ProofsUiState(w, s, b, bal) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProofsUiState())

    init {
        viewModelScope.launch {
            profiles.wallet.collect { w -> balance.value = w?.let { gateway.balanceLamports(it.publicKey, SolanaCluster.DEVNET) } }
        }
    }

    fun mode(m: StampingMode) = viewModelScope.launch { settings.setStampingMode(m) }
    fun disconnect() = viewModelScope.launch {
        runCatching { gateway.disconnect() }
        profiles.unlinkWallet()
    }

    fun airdrop() = viewModelScope.launch {
        val w = state.value.wallet ?: return@launch
        message.value = runCatching { gateway.requestDevnetAirdrop(w.publicKey) }.fold({ R.string.airdrop_requested }, { R.string.airdrop_failed })
    }
}

/** Screen S3. Devnet only; mainnet is shown as unavailable. */
@Composable
internal fun ProofsScreen(navigate: (Any) -> Unit, onBack: () -> Unit, vm: ProofsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val c = SotreusTheme.colors
    var mainnet by remember { mutableStateOf(false) }
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.proofs_title))
        val w = state.wallet
        if (w == null) {
            PrimaryButton(stringResource(R.string.connect_first), { navigate(WalletRoute) })
        } else {
            Column(
                Modifier.fillMaxWidth().background(c.surface, SotreusTheme.shapes.featureCard).border(1.dp, c.lineStrong, SotreusTheme.shapes.featureCard).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        MonoLabel(stringResource(R.string.wallet_signed_in), small = true)
                        Text(Base58.abbreviate(w.publicKey), style = SotreusTheme.typography.monoValue.copy(fontSize = SotreusTheme.typography.body.fontSize), color = c.text)
                    }
                    StateChip(stringResource(R.string.devnet), ChipTone.DASHED, small = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                    GhostButton(stringResource(R.string.switch_network), { mainnet = true }, Modifier.weight(1f), minHeight = 44.dp)
                    GhostButton(stringResource(R.string.disconnect), vm::disconnect, Modifier.weight(1f), minHeight = 44.dp)
                }
                Text(stringResource(R.string.disconnect_note), style = SotreusTheme.typography.caption, color = c.textDim)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.balance) + " · " + (state.balanceLamports?.let { stringResource(R.string.balance_value, "%.4f".format(it / 1e9)) } ?: "—"),
                        style = SotreusTheme.typography.monoValue,
                        color = c.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    InlineLink(stringResource(R.string.airdrop), vm::airdrop)
                }
            }
        }
        Column {
            MonoLabel(stringResource(R.string.stamping_kicker))
            RadioGroupRows(
                listOf(
                    RadioOption(StampingMode.OFF, stringResource(R.string.proofs_stamping_off)),
                    RadioOption(StampingMode.MANUAL_ONLY, stringResource(R.string.proofs_stamping_manual)),
                    RadioOption(StampingMode.ASK_END_JOURNEY, stringResource(R.string.proofs_stamping_journey)),
                    RadioOption(StampingMode.ASK_END_SIT, stringResource(R.string.proofs_stamping_sit)),
                ),
                state.settings.stampingMode,
                vm::mode,
            )
            Text(stringResource(R.string.stamping_note), style = SotreusTheme.typography.caption, color = c.textDim, modifier = Modifier.padding(top = 4.dp))
        }
        Column {
            MonoLabel(stringResource(R.string.history_kicker))
            if (state.batches.isEmpty()) Text(stringResource(R.string.history_empty), style = SotreusTheme.typography.bodyS, color = c.textMuted)
            state.batches.forEachIndexed { i, b ->
                InfoRow(
                    title = b.title,
                    subtitle = if (b.state == ProofState.PENDING) stringResource(R.string.history_not_submitted, b.recordCount)
                    else stringResource(R.string.history_row, b.recordCount, "${dayLabel(b.blockTimeMs ?: b.createdAtMs)} ${clockTime(b.blockTimeMs ?: b.createdAtMs)}"),
                    onClick = { navigate(if (b.state == ProofState.PENDING) StampRoute(b.id) else ReceiptRoute(b.id)) },
                    showDivider = i < state.batches.lastIndex,
                    titleStyleBody = true,
                ) { ProofStateChip(b.state) }
            }
        }
        PublicPrivateSplit(
            stringResource(R.string.onchain_kicker), stringResource(R.string.onchain_body).split("\n"),
            stringResource(R.string.never_kicker), stringResource(R.string.never_body).split("\n"),
        )
    }
    if (mainnet) {
        AlertDialog(
            onDismissRequest = { mainnet = false },
            containerColor = c.surfaceRaised,
            title = { Text(stringResource(R.string.mainnet_title), style = SotreusTheme.typography.titleS.copy(fontSize = SotreusTheme.typography.titleS.fontSize * 0.8f), color = c.text) },
            text = { Text(stringResource(R.string.mainnet_body), style = SotreusTheme.typography.bodyS, color = c.textSoft) },
            confirmButton = { TextButton(onClick = { mainnet = false }) { Text(stringResource(R.string.ok), color = c.accent) } },
        )
    }
    message?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.message.value = null },
            containerColor = c.surfaceRaised,
            text = { Text(stringResource(m), style = SotreusTheme.typography.body, color = c.text) },
            confirmButton = { TextButton(onClick = { vm.message.value = null }) { Text(stringResource(R.string.ok), color = c.accent) } },
        )
    }
}

@Composable
internal fun ProofStateChip(state: ProofState) = when (state) {
    ProofState.FINALIZED -> StateChip(stringResource(R.string.state_finalized), ChipTone.NEUTRAL)
    ProofState.SUBMITTED -> StateChip(stringResource(R.string.state_submitted), ChipTone.ACCENT)
    ProofState.PENDING -> StateChip(stringResource(R.string.state_pending), ChipTone.DASHED)
    ProofState.FAILED -> StateChip(stringResource(R.string.state_failed), ChipTone.DASHED)
}

