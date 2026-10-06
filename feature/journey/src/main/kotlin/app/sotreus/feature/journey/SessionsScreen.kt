package app.sotreus.feature.journey

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.repository.SessionListItem
import app.sotreus.core.data.repository.SessionRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.GeotagMode
import app.sotreus.core.model.SessionKind
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.navigation.CompareRoute
import app.sotreus.core.navigation.PermissionsRoute
import app.sotreus.core.navigation.SessionLiveRoute
import app.sotreus.core.navigation.SessionRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.CardStyle
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.InlineLink
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SectionHeader
import app.sotreus.core.ui.SotreusCard
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.SwitchRow
import app.sotreus.core.ui.dayLabel
import app.sotreus.core.ui.durationLabel
import app.sotreus.sensing.RadioAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class SessionsUiState(
    val sessions: List<SessionListItem> = emptyList(),
    val settings: SotreusSettings = SotreusSettings(),
    val access: RadioAccess? = null,
    val now: Long = System.currentTimeMillis(),
) {
    val geotagOn: Boolean get() = when (settings.geotagMode) {
        GeotagMode.ALWAYS -> true
        GeotagMode.NEVER -> false
        GeotagMode.ASK_EACH_TIME -> settings.geotagNextSession
    }
    val canStart: Boolean get() = settings.simulatedRadios || access?.canScan != false
}

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    pipeline: ObservationPipeline,
) : ViewModel() {
    val state: StateFlow<SessionsUiState> = combine(sessions.observeList(), settings.settings, pipeline.snapshot) { list, s, snap ->
        SessionsUiState(list, s, snap.access, snap.atMs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsUiState())

    fun setGeotag(on: Boolean) = viewModelScope.launch { settings.setGeotagNextSession(on) }

    fun start(kind: SessionKind, geotag: Boolean, label: String, labelNoPlace: String, onStarted: (Long) -> Unit) = viewModelScope.launch {
        onStarted(sessions.start(kind, geotag, label, labelNoPlace))
    }
}

@Composable
internal fun SessionsScreen(navigate: (Any) -> Unit, vm: SessionsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val labelJourney = stringResource(R.string.name_journey)
    val labelSit = stringResource(R.string.name_sit)
    val partOfDay = partOfDay()
    fun hasLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.setGeotag(granted) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun start(kind: SessionKind) {
        if (!state.canStart) {
            navigate(PermissionsRoute)
            return
        }
        // The session notice is optional; ask for it in context, once.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val label = if (kind == SessionKind.JOURNEY) "$labelJourney · $partOfDay" else partOfDay
        val labelNoPlace = if (kind == SessionKind.JOURNEY) "$labelJourney · $partOfDay" else "$labelSit · $partOfDay"
        vm.start(kind, state.geotagOn && hasLocation(), label, labelNoPlace) { navigate(SessionLiveRoute(it)) }
    }

    SessionsContent(
        state = state,
        onStartJourney = { start(SessionKind.JOURNEY) },
        onStartSit = { start(SessionKind.SIT) },
        onGeotag = { on -> if (on && !hasLocation()) locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) else vm.setGeotag(on) },
        onCompare = { navigate(CompareRoute()) },
        onSession = { item -> navigate(if (item.session.endedAtMs == null) SessionLiveRoute(item.session.id) else SessionRoute(item.session.id)) },
    )
}

@Composable
private fun partOfDay(): String {
    val h = Instant.now().atZone(ZoneId.systemDefault()).hour
    return stringResource(
        when (h) {
            in 5..11 -> R.string.morning
            in 12..16 -> R.string.afternoon
            in 17..21 -> R.string.evening
            else -> R.string.night
        },
    )
}

/** Screen 10 (Journey tab, titled "Sessions"). */
@Composable
internal fun SessionsContent(
    state: SessionsUiState,
    onStartJourney: () -> Unit,
    onStartSit: () -> Unit,
    onGeotag: (Boolean) -> Unit,
    onCompare: () -> Unit,
    onSession: (SessionListItem) -> Unit,
) {
    val c = SotreusTheme.colors
    ScreenColumn {
        ScreenTitle(stringResource(R.string.feature_journey_title))
        Text(stringResource(R.string.sessions_lead), style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f), color = c.textMuted)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            StartCard(SotreusIcons.Journey, stringResource(R.string.start_journey), stringResource(R.string.start_journey_sub), CardStyle.ATTENTION_LARGE, onStartJourney, Modifier.weight(1f))
            StartCard(SotreusIcons.Sit, stringResource(R.string.start_sit), stringResource(R.string.start_sit_sub), CardStyle.RAISED, onStartSit, Modifier.weight(1f))
        }
        if (!state.canStart) CaveatBox(stringResource(R.string.feature_journey_title), stringResource(R.string.session_needs_radios))
        SwitchRow(
            stringResource(R.string.geotag_next),
            state.geotagOn,
            onGeotag,
            subtitle = stringResource(if (state.settings.geotagMode == GeotagMode.NEVER) R.string.geotag_never else R.string.geotag_next_sub),
            enabled = state.settings.geotagMode == GeotagMode.ASK_EACH_TIME,
        )
        Column {
            SectionHeader(stringResource(R.string.previous, state.sessions.size)) { InlineLink(stringResource(R.string.compare_sits), onCompare) }
            if (state.sessions.isEmpty()) CaveatBox(stringResource(R.string.feature_journey_title), stringResource(R.string.sessions_empty))
            state.sessions.forEachIndexed { i, item -> SessionRow(item, state.now, onSession, showDivider = i < state.sessions.lastIndex) }
        }
    }
}

@Composable
private fun StartCard(icon: ImageVector, title: String, sub: String, style: CardStyle, onClick: () -> Unit, modifier: Modifier) {
    val c = SotreusTheme.colors
    SotreusCard(modifier.fillMaxHeight(), style = style, large = true, onClick = onClick, padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
            Icon(icon, contentDescription = null, tint = if (style == CardStyle.RAISED) c.textSoft else c.accent, modifier = Modifier.size(SotreusTheme.sizes.iconSize))
            Text(title, style = SotreusTheme.typography.bodyL.copy(fontWeight = FontWeight.Medium, fontSize = SotreusTheme.typography.bodyL.fontSize * 1.06f), color = c.text)
            Text(sub, style = SotreusTheme.typography.bodyS, color = c.textMuted)
        }
    }
}

@Composable
internal fun SessionRow(item: SessionListItem, now: Long, onClick: (SessionListItem) -> Unit, showDivider: Boolean = true) {
    val c = SotreusTheme.colors
    val s = item.session
    val journey = s.kind == SessionKind.JOURNEY
    val sub = if (s.endedAtMs == null) {
        stringResource(R.string.session_active)
    } else {
        stringResource(R.string.session_row_sub, dayLabel(s.startedAtMs, now), durationLabel(s.endedAtMs!! - s.startedAtMs), item.radios)
            .let { if (s.geotag) stringResource(R.string.session_row_geotagged, it) else it }
    }
    InfoRow(
        title = s.name,
        subtitle = sub,
        onClick = { onClick(item) },
        showDivider = showDivider,
        leading = {
            Box(
                Modifier.size(36.dp).background(if (journey) c.attentionSurface else c.surfaceHigh, SotreusTheme.shapes.glyphWell),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(if (journey) R.string.badge_journey else R.string.badge_sit), style = SotreusTheme.typography.monoLabelS, color = if (journey) c.accent else c.textSoft)
            }
        },
    ) {
        if (item.attention > 0) StateChip(item.attention.toString(), ChipTone.ACCENT_FILLED) else Text("0", style = SotreusTheme.typography.monoLabelS, color = c.textDim, modifier = Modifier.padding(end = 4.dp))
    }
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun SessionsPreview() {
    SotreusTheme { SessionsContent(SessionsUiState(FakeSotreusData.sessions, now = FakeSotreusData.NOW), {}, {}, {}, {}, {}) }
}
