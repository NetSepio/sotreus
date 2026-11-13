package app.sotreus.feature.mesh

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.mesh.MeshHub
import app.sotreus.core.data.mesh.MeshLive
import app.sotreus.core.data.mesh.MeshRows
import app.sotreus.core.database.dao.MeshDao
import app.sotreus.core.database.entity.MeshMessageEntity
import app.sotreus.core.database.entity.MeshNodeEntity
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.navigation.MeshConversationRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.QuietButton
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.WarningBanner
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.dayLabel
import app.sotreus.edge.protocol.mesh.MeshNodeId
import app.sotreus.edge.protocol.mesh.MeshText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class ConversationUi(
    val conversation: String = "",
    val live: MeshLive = MeshLive(),
    val messages: List<MeshMessageEntity> = emptyList(),
    val nodes: Map<Long, MeshNodeEntity> = emptyMap(),
    val muted: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class ConversationViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val mesh: MeshHub,
    private val dao: MeshDao,
) : ViewModel() {
    val conversation: String = handle.toRoute<MeshConversationRoute>().conversation
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<ConversationUi> = mesh.state.map { it.deviceId }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(Triple(emptyList<MeshMessageEntity>(), emptyList<MeshNodeEntity>(), emptyList<String>()))
        else combine(dao.observeConversation(id, conversation), dao.observeNodes(id), dao.observeMuted(id), ::Triple)
    }.let { data ->
        combine(mesh.state, data, error) { live, (msgs, nodes, muted), err ->
            ConversationUi(conversation, live, msgs, nodes.associateBy { it.num }, conversation in muted, System.currentTimeMillis(), err)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUi(conversation))

    init {
        // Read while open: new messages here raise no notification and count as read.
        viewModelScope.launch { state.map { it.messages.size }.distinctUntilChanged().collect { mesh.markRead(conversation) } }
    }

    fun opened(open: Boolean) {
        mesh.openConversation = if (open) conversation else mesh.openConversation.takeIf { it != conversation }
    }

    fun send(text: String, replyId: Long?) = viewModelScope.launch {
        mesh.send(conversation, text, replyId).onFailure { error.value = it.message }
    }

    fun react(target: MeshMessageEntity, emoji: String) = viewModelScope.launch {
        mesh.send(conversation, emoji, replyId = target.packetId, reaction = true).onFailure { error.value = it.message }
    }

    fun quick(index: Int, text: String) = viewModelScope.launch {
        mesh.sendQuick(conversation, index, text).onFailure { error.value = it.message }
    }

    fun setMuted(muted: Boolean) = viewModelScope.launch { mesh.setMuted(conversation, muted) }

    fun clearError() { error.value = null }
}

/** One conversation (mock M2): bubbles, delivery, replies, reactions, quick messages, compose. */
@Composable
internal fun ConversationScreen(onBack: () -> Unit, onNode: (Long) -> Unit, vm: ConversationViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        vm.opened(true)
        onDispose { vm.opened(false) }
    }
    ConversationContent(
        state, onBack, onNode,
        onSend = vm::send, onReact = vm::react, onQuick = vm::quick, onMute = vm::setMuted, onClearError = vm::clearError,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationContent(
    state: ConversationUi,
    onBack: () -> Unit,
    onNode: (Long) -> Unit,
    onSend: (String, Long?) -> Unit = { _, _ -> },
    onReact: (MeshMessageEntity, String) -> Unit = { _, _ -> },
    onQuick: (Int, String) -> Unit = { _, _ -> },
    onMute: (Boolean) -> Unit = {},
    onClearError: () -> Unit = {},
) {
    val c = SotreusTheme.colors
    val cfg = state.live.config
    val channel = MeshRows.channelOf(state.conversation)
    val peer = MeshRows.peerOf(state.conversation)
    var replyTo by remember { mutableStateOf<MeshMessageEntity?>(null) }
    val byPacket = state.messages.associateBy { it.packetId }
    val reactions = state.messages.filter { it.reaction && it.replyId != null }.groupBy { it.replyId!! }
    val shown = state.messages.filter { !it.reaction }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.padding(horizontal = SotreusTheme.spacing.screenH)) {
            BackTopBar(onBack = onBack, trailing = {
                QuietButton(stringResource(if (state.muted) R.string.mesh_unmute else R.string.mesh_mute), { onMute(!state.muted) })
            })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                Column(Modifier.weight(1f).then(if (peer != null) Modifier.combinedClickable(onClick = { onNode(peer) }) else Modifier), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(conversationTitle(state.conversation, cfg, state.nodes), style = SotreusTheme.typography.titleS, color = c.text)
                    val sub = when {
                        channel != null -> listOfNotNull(
                            if (channel == 0) stringResource(R.string.mesh_primary) else null,
                            keyLabel(cfg?.channels?.getOrNull(channel)),
                        ).joinToString(" · ")
                        peer != null && state.nodes[peer]?.hasKey == true -> stringResource(R.string.mesh_encrypted)
                        else -> stringResource(R.string.mesh_key_unknown)
                    }
                    Text(sub, style = SotreusTheme.typography.caption, color = c.textDim)
                }
                StateChip(stringResource(R.string.mesh_network), ChipTone.NETWORK)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val list = shown.asReversed()
            items(list.size, key = { i -> "${list[i].fromNode}:${list[i].packetId}" }) { i ->
                val m = list[i]
                val older = list.getOrNull(i + 1)
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (older == null || !sameDay(older.atMs, m.atMs)) {
                        Text(dayLabel(m.atMs, state.nowMs).uppercase(), style = SotreusTheme.typography.monoLabelS, color = c.textDim, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    Bubble(
                        m, state, channel != null, m.replyId?.let { byPacket[it] }, reactions[m.packetId].orEmpty(),
                        onReply = { replyTo = m }, onReact = { e -> onReact(m, e) },
                        onRetry = { onSend(m.text, m.replyId) },
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let {
                WarningBanner(it)
                QuietButton(stringResource(R.string.mesh_ok), onClearError)
            }
            if (!state.live.canSend) {
                WarningBanner(stringResource(if (state.live.connected) R.string.mesh_cannot_send_radio else R.string.mesh_connect_to_send))
            } else {
                Composer(state, replyTo, { replyTo = null }, onSend, onQuick)
            }
        }
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val z = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(a).atZone(z).toLocalDate() == java.time.Instant.ofEpochMilli(b).atZone(z).toLocalDate()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: MeshMessageEntity,
    state: ConversationUi,
    showSender: Boolean,
    replied: MeshMessageEntity?,
    reactions: List<MeshMessageEntity>,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val c = SotreusTheme.colors
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val failed = m.outgoing && deliveryFailed(m.delivery)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showSender && !m.outgoing) {
                val n = state.nodes[m.fromNode]
                Text("${shortTag(n, m.fromNode)} · ${n?.title() ?: MeshNodeId.format(m.fromNode)}", style = SotreusTheme.typography.monoLabelS, color = c.textMuted)
            }
            val shape = if (m.outgoing) SotreusTheme.shapes.panel else SotreusTheme.shapes.panel
            Column(
                Modifier
                    .background(if (m.outgoing) c.surfaceBanner else c.surfaceHigh, shape)
                    .then(if (m.outgoing) Modifier.border(1.dp, if (failed) c.attentionLine else c.lineStrong, shape) else Modifier)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                replied?.let { r ->
                    val who = if (r.outgoing) stringResource(R.string.mesh_you) else shortTag(state.nodes[r.fromNode], r.fromNode)
                    Text(
                        "$who · ${r.text}", style = SotreusTheme.typography.caption, color = c.textMuted, maxLines = 2,
                        modifier = Modifier.padding(bottom = 6.dp).background(c.ink, SotreusTheme.shapes.glyphWell).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Text(m.text, style = SotreusTheme.typography.body, color = c.text)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val meta = buildList {
                    add(clockTime(m.atMs))
                    if (!m.outgoing) {
                        hopsLabel(m.hops)?.let(::add)
                        snr(m.snr)?.let { add(stringResource(R.string.mesh_snr, it)) }
                    }
                }.joinToString(" · ")
                Text(meta, style = SotreusTheme.typography.monoLabelS, color = c.textDim)
                if (m.outgoing) {
                    Text("·", style = SotreusTheme.typography.monoLabelS, color = c.textDim)
                    Text(deliveryLabel(m.delivery, m.reason).orEmpty(), style = SotreusTheme.typography.monoLabelS, color = if (failed) c.destructive else c.textSoft)
                    if (failed) QuietButton(stringResource(R.string.mesh_try_again), onRetry)
                }
            }
            if (reactions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    reactions.groupBy { it.text }.forEach { (emoji, list) ->
                        Text("$emoji ${list.size}", style = SotreusTheme.typography.caption, color = c.textSoft,
                            modifier = Modifier.border(1.dp, c.lineMid, SotreusTheme.shapes.pill).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (state.live.canSend) {
                DropdownMenuItem(text = { Text(stringResource(R.string.mesh_reply)) }, onClick = { menu = false; onReply() })
                DropdownMenuItem(text = { Text(stringResource(R.string.mesh_react)) }, onClick = { menu = false; picking = true })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.mesh_copy)) }, onClick = { menu = false; clipboard.setText(AnnotatedString(m.text)) })
        }
        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                REACTIONS.forEach { e ->
                    IconButton(onClick = { picking = false; onReact(e) }, modifier = Modifier.size(SotreusTheme.sizes.minTouch)) { Text(e, style = SotreusTheme.typography.titleS) }
                }
            }
        }
    }
}

private val REACTIONS = listOf("👍", "❤️", "😂", "😮", "🙏", "✅")

@Composable
private fun Composer(state: ConversationUi, replyTo: MeshMessageEntity?, onCancelReply: () -> Unit, onSend: (String, Long?) -> Unit, onQuick: (Int, String) -> Unit) {
    val c = SotreusTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    val canned = state.live.config?.canned.orEmpty()
    if (canned.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            canned.forEachIndexed { i, q ->
                Text(
                    q, style = SotreusTheme.typography.bodyS, color = c.textSoft,
                    modifier = Modifier.heightIn(min = SotreusTheme.sizes.minTouch).border(1.dp, c.lineStrong, SotreusTheme.shapes.pill)
                        .combinedClickableCompat { onQuick(i, q) }.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
    replyTo?.let { r ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mesh_replying_to, r.text), style = SotreusTheme.typography.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            QuietButton(stringResource(R.string.mesh_cancel), onCancelReply)
        }
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.weight(1f).border(1.dp, c.lineMid, SotreusTheme.shapes.featureCard).background(c.surfaceHigh, SotreusTheme.shapes.featureCard)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val hint = stringResource(R.string.mesh_compose_hint, conversationTitle(state.conversation, state.live.config, state.nodes))
            BasicTextField(
                value = text,
                onValueChange = { text = MeshText.clip(it) },
                textStyle = SotreusTheme.typography.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
                decorationBox = { inner -> if (text.isEmpty()) Text(hint, style = SotreusTheme.typography.body, color = c.textDim); inner() },
            )
            Text(stringResource(R.string.mesh_bytes, MeshText.bytes(text), MeshText.MAX_BYTES), style = SotreusTheme.typography.monoLabelS, color = c.textDim, modifier = Modifier.align(Alignment.End))
        }
        IconButton(
            onClick = { if (MeshText.fits(text.trim())) { onSend(text.trim(), replyTo?.packetId); text = ""; onCancelReply() } },
            enabled = MeshText.fits(text.trim()),
            modifier = Modifier.size(48.dp).background(if (MeshText.fits(text.trim())) c.accent else c.surfaceHigh, SotreusTheme.shapes.pill),
        ) { Icon(SotreusIcons.Send, contentDescription = stringResource(R.string.mesh_send), tint = if (MeshText.fits(text.trim())) c.ink else c.textDim) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.then(Modifier.combinedClickable(onClick = onClick))

// ---- Previews (FakeSotreusData) -----------------------------------------------------------------

@Preview(widthDp = 390, heightDp = 1180, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun ConversationPreview() {
    SotreusTheme {
        ConversationContent(
            ConversationUi("ch:0", FakeSotreusData.meshLive, FakeSotreusData.meshChannelMessages, FakeSotreusData.meshNodes.associateBy { it.num }, nowMs = FakeSotreusData.NOW),
            {}, {},
        )
    }
}

@Preview(widthDp = 390, heightDp = 800, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun ConversationAwayPreview() {
    SotreusTheme {
        ConversationContent(
            ConversationUi("dm:${FakeSotreusData.MESH_BASE}", FakeSotreusData.meshLive.copy(connected = false), FakeSotreusData.meshChannelMessages.take(3),
                FakeSotreusData.meshNodes.associateBy { it.num }, nowMs = FakeSotreusData.NOW),
            {}, {},
        )
    }
}
