package app.sotreus.feature.proofs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.crypto.Base58
import app.sotreus.core.data.export.ExportService
import app.sotreus.core.data.repository.ProfileRepository
import app.sotreus.core.data.repository.ProofRepository
import app.sotreus.core.data.repository.SessionRepository
import app.sotreus.core.data.repository.SolanaGateway
import app.sotreus.core.data.repository.WalletException
import app.sotreus.core.database.entity.LinkedIdentityEntity
import app.sotreus.core.database.entity.ProofBatchEntity
import app.sotreus.core.database.entity.SessionEntity
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.ProofState
import app.sotreus.core.model.SessionKind
import app.sotreus.core.navigation.ReceiptRoute
import app.sotreus.core.navigation.StampRoute
import app.sotreus.core.navigation.WalletRoute
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.DevnetBanner
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.KeyValueTable
import app.sotreus.core.ui.LightButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.PrimaryButton
import app.sotreus.core.ui.QuietButton
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.Stat
import app.sotreus.core.ui.StatGrid
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.WarningBanner
import app.sotreus.core.ui.dateTime
import app.sotreus.core.ui.durationLabel
import app.sotreus.core.ui.shareFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

// --- S4 Stamp confirmation ----------------------------------------------------------------

data class StampUiState(val loading: Boolean = true, val batch: ProofBatchEntity? = null, val session: SessionEntity? = null, val wallet: LinkedIdentityEntity? = null)

sealed interface StampStatus {
    data object Idle : StampStatus
    data object Working : StampStatus
    data class Failed(val message: String) : StampStatus
}

@HiltViewModel
class StampViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val proofs: ProofRepository,
    sessions: SessionRepository,
    profiles: ProfileRepository,
    private val gateway: SolanaGateway,
) : ViewModel() {
    val batchId = handle.toRoute<StampRoute>().batchId
    val status = MutableStateFlow<StampStatus>(StampStatus.Idle)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<StampUiState> = combine(
        proofs.observeBatch(batchId),
        proofs.observeBatch(batchId).flatMapLatest { b -> b?.sessionId?.let { sessions.observeSession(it) } ?: flowOf(null) },
        profiles.wallet,
    ) { b, s, w -> StampUiState(false, b, s, w) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StampUiState())

    /** Hands only the 32-byte commitment to the wallet. Nothing is ever sent in the background. */
    fun approve(onSubmitted: () -> Unit) = viewModelScope.launch {
        val b = state.value.batch ?: return@launch
        val w = state.value.wallet ?: return@launch
        status.value = StampStatus.Working
        try {
            val signature = gateway.stampCommitment(b.commitmentHex, w.publicKey, b.cluster)
            proofs.markSubmitted(b.id, signature, w.publicKey)
            status.value = StampStatus.Idle
            onSubmitted()
        } catch (e: WalletException) {
            status.value = StampStatus.Failed(e.message ?: "")
        } catch (e: Exception) {
            status.value = StampStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}

/** Screen S4. The devnet banner is always visible; wording follows handoff §15. */
@Composable
internal fun StampScreen(navigate: (Any) -> Unit, replace: (Any) -> Unit, onBack: () -> Unit, vm: StampViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val c = SotreusTheme.colors
    val b = state.batch
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DevnetBanner(stringResource(R.string.devnet_banner))
        Column(
            Modifier.padding(start = SotreusTheme.spacing.screenH, end = SotreusTheme.spacing.screenH, top = 8.dp, bottom = SotreusTheme.spacing.screenBottom),
            verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.section),
        ) {
            if (b == null) {
                if (!state.loading) CaveatBox(stringResource(R.string.stamp_public_kicker), stringResource(R.string.stamp_missing))
                return@Column
            }
            val s = state.session
            val journey = s?.kind == SessionKind.JOURNEY
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                val duration = s?.let { durationLabel((it.endedAtMs ?: it.startedAtMs) - it.startedAtMs) } ?: ""
                MonoLabel(
                    when {
                        s == null -> stringResource(R.string.stamp_kicker_session, b.title)
                        journey -> stringResource(R.string.stamp_kicker_journey, duration)
                        else -> stringResource(R.string.stamp_kicker_sit, duration)
                    },
                )
                Text(stringResource(if (journey) R.string.stamp_title_journey else R.string.stamp_title_sit), style = SotreusTheme.typography.title, color = c.text)
                // Required wording (handoff §15): "commits to the integrity of N locally stored records".
                Text(stringResource(R.string.stamp_body, b.recordCount, b.title), style = SotreusTheme.typography.body, color = c.textSoft)
            }
            StatGrid(
                listOf(Stat(b.observationCount.toString(), stringResource(R.string.stamp_observations)), Stat(b.attentionCount.toString(), stringResource(R.string.stamp_attention))),
                columns = 2,
            )
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                MonoLabel(stringResource(R.string.stamp_public_kicker))
                Column(
                    Modifier.fillMaxWidth().background(c.surface, SotreusTheme.shapes.panel).border(1.dp, c.lineStrong, SotreusTheme.shapes.panel).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s),
                ) {
                    Text(stringResource(R.string.stamp_commitment), style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f), color = c.text)
                    Text(groupedHex(b.commitmentHex), style = SotreusTheme.typography.monoValue, color = c.textMuted)
                    Text(stringResource(R.string.stamp_commitment_note), style = SotreusTheme.typography.bodyS, color = c.textDim)
                }
            }
            Column(
                Modifier.fillMaxWidth().background(c.attentionSurfaceLarge, SotreusTheme.shapes.panel).border(1.dp, c.attentionLine, SotreusTheme.shapes.panel).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s),
            ) {
                Text(stringResource(R.string.stamp_stays_kicker).uppercase(), style = SotreusTheme.typography.monoLabelS, color = c.accent)
                Text(stringResource(R.string.stamp_stays_body), style = SotreusTheme.typography.bodyS, color = c.textSoft)
            }
            Column {
                RowDivider(strong = true)
                Bullet(
                    buildAnnotatedString {
                        append(stringResource(R.string.stamp_limit_1_a)); append(" ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = c.text)) { append(stringResource(R.string.stamp_limit_1_not)) }
                        append(" "); append(stringResource(R.string.stamp_limit_1_b))
                    },
                )
                RowDivider(strong = true)
                Bullet(buildAnnotatedString { append(stringResource(R.string.stamp_limit_2)) })
                RowDivider(strong = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.stamp_fee), style = SotreusTheme.typography.monoValue, color = c.textDim)
                Text(stringResource(R.string.stamp_fee_value), style = SotreusTheme.typography.monoValue, color = c.text)
            }
            (status as? StampStatus.Failed)?.let { WarningBanner(stringResource(R.string.stamp_failed, it.message)) }
            if (state.wallet == null) WarningBanner(stringResource(R.string.stamp_need_wallet))
            Spacer(Modifier.heightIn(min = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                if (state.wallet == null) {
                    PrimaryButton(stringResource(R.string.connect_first), { navigate(WalletRoute) })
                } else {
                    PrimaryButton(
                        stringResource(if (status == StampStatus.Working) R.string.stamp_working else R.string.stamp_approve),
                        { vm.approve { replace(ReceiptRoute(vm.batchId)) } },
                        enabled = status != StampStatus.Working && b.state == ProofState.PENDING,
                    )
                }
                QuietButton(stringResource(R.string.stamp_not_now), onBack)
            }
        }
    }
}

@Composable
private fun Bullet(text: androidx.compose.ui.text.AnnotatedString) {
    val c = SotreusTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl)) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).background(c.textSoft, SotreusTheme.shapes.pill))
        Text(text, style = SotreusTheme.typography.bodyS.copy(fontSize = SotreusTheme.typography.bodyS.fontSize * 1.04f), color = c.textSoft)
    }
}

private fun groupedHex(hex: String): String = hex.chunked(4).let { g -> (g.take(3) + "…" + g.takeLast(2)).joinToString(" ") }

// --- S5 Proof receipt ---------------------------------------------------------------------

@HiltViewModel
class ReceiptViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val proofs: ProofRepository,
    private val gateway: SolanaGateway,
    private val exports: ExportService,
) : ViewModel() {
    val batchId = handle.toRoute<ReceiptRoute>().batchId
    val batch: StateFlow<ProofBatchEntity?> = proofs.observeBatch(batchId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Poll the chain until the stamp is finalized (or fails). Read-only RPC, by signature.
        viewModelScope.launch {
            var tries = 0
            while (isActive && tries < 60) {
                val b = proofs.batch(batchId) ?: break
                if (b.state != ProofState.SUBMITTED || b.txSignature == null) break
                val conf = runCatching { gateway.confirmation(b.txSignature!!, b.cluster) }.getOrNull()
                when {
                    conf?.failed == true -> { proofs.markFailed(batchId, "Transaction failed on-chain"); break }
                    conf?.finalized == true -> { proofs.markFinalized(batchId, conf.blockTimeMs); break }
                }
                tries++
                delay(3_000)
            }
        }
    }

    fun export(onReady: (Uri) -> Unit) = viewModelScope.launch {
        proofs.exportRecordProof(batchId)?.let { onReady(exports.writeText("sotreus-proof-$batchId.json", it)) }
    }
}

/** Screen S5. */
@Composable
internal fun ReceiptScreen(navigate: (Any) -> Unit, onClose: () -> Unit, vm: ReceiptViewModel = hiltViewModel()) {
    val b by vm.batch.collectAsStateWithLifecycle()
    val c = SotreusTheme.colors
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.receipt_share)
    val dash = stringResource(R.string.kv_dash)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = SotreusTheme.spacing.screenH, end = SotreusTheme.spacing.screenH, top = SotreusTheme.spacing.screenH, bottom = SotreusTheme.spacing.screenBottom),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(SotreusTheme.sizes.minTouch)) {
                Icon(SotreusIcons.Close, contentDescription = stringResource(app.sotreus.core.ui.R.string.core_ui_close), tint = c.text, modifier = Modifier.size(SotreusTheme.sizes.iconSize))
            }
            Spacer(Modifier.weight(1f))
            StateChip(stringResource(R.string.devnet), ChipTone.DASHED, small = false)
        }
        val batch = b ?: return@Column
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).border(1.5.dp, c.text, SotreusTheme.shapes.pill), contentAlignment = Alignment.Center) {
                Icon(if (batch.state == ProofState.FINALIZED) SotreusIcons.Check else SotreusIcons.Clock, contentDescription = null, tint = c.text, modifier = Modifier.size(SotreusTheme.sizes.iconSize))
            }
            Text(
                stringResource(
                    when (batch.state) {
                        ProofState.FINALIZED -> R.string.receipt_title_final
                        ProofState.SUBMITTED -> R.string.receipt_title_waiting
                        ProofState.PENDING -> R.string.receipt_title_pending
                        ProofState.FAILED -> R.string.receipt_title_failed
                    },
                ),
                style = SotreusTheme.typography.title,
                color = c.text,
            )
            Text(stringResource(R.string.stamp_body, batch.recordCount, batch.title), style = SotreusTheme.typography.body, color = c.textSoft)
        }
        KeyValueTable(
            listOf(
                stringResource(R.string.kv_status) to stringResource(
                    when (batch.state) {
                        ProofState.FINALIZED -> R.string.state_finalized
                        ProofState.SUBMITTED -> R.string.state_submitted
                        ProofState.PENDING -> R.string.state_pending
                        ProofState.FAILED -> R.string.state_failed
                    },
                ),
                stringResource(R.string.kv_block_time) to (batch.blockTimeMs?.let(::dateTime) ?: dash),
                stringResource(R.string.kv_signature) to (batch.txSignature?.let(Base58::abbreviate) ?: dash),
                stringResource(R.string.kv_commitment) to "${batch.commitmentHex.take(4)}…${batch.commitmentHex.takeLast(4)}",
                stringResource(R.string.kv_root) to "${batch.merkleRootHex.take(4)}…${batch.merkleRootHex.takeLast(4)}",
                stringResource(R.string.kv_records) to batch.recordCount.toString(),
                stringResource(R.string.kv_schema) to batch.schema,
            ),
        )
        CaveatBox(stringResource(R.string.receipt_caveat_kicker), stringResource(R.string.receipt_caveat_body, (batch.recordCount - 1).coerceAtLeast(0)))
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            if (batch.state == ProofState.PENDING || batch.state == ProofState.FAILED) {
                PrimaryButton(stringResource(R.string.receipt_stamp_now), { navigate(StampRoute(batch.id)) })
            }
            LightButton(stringResource(R.string.receipt_done), onClose)
            Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                GhostButton(
                    stringResource(R.string.receipt_explorer),
                    {
                        batch.txSignature?.let { sig ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://explorer.solana.com/tx/$sig?cluster=devnet")))
                        }
                    },
                    Modifier.weight(1f),
                    enabled = batch.txSignature != null,
                )
                GhostButton(stringResource(R.string.receipt_export), { vm.export { shareFile(context, it, shareTitle) } }, Modifier.weight(1f))
            }
        }
    }
}
