package app.sotreus.feature.entity

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.field.SignalMap
import app.sotreus.core.data.field.SignalMapRepository
import app.sotreus.core.data.pipeline.ObservationController
import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.repository.EntityRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.entity.EntityEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.ProximityCue
import app.sotreus.core.model.RadioKind
import app.sotreus.core.navigation.ProximityRoute
import app.sotreus.core.testing.FakeSotreusData
import app.sotreus.core.ui.AutoSizeText
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.CompactButton
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.HeatImage
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.RssiSparkline
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.SignalHeatMap
import app.sotreus.core.ui.SotreusCard
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.StatusPill
import app.sotreus.core.ui.SwitchRow
import app.sotreus.core.ui.entityTitle
import app.sotreus.intelligence.Proximity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProximityUiState(
    val entity: EntityEntity? = null,
    val cue: ProximityCue = ProximityCue.LISTENING,
    val samples: List<Pair<Long, Int>> = emptyList(),
    val now: Long = 0,
    val recentDbm: Int? = null,
    val earlierDbm: Int? = null,
)

@HiltViewModel
class ProximityViewModel @Inject constructor(
    handle: SavedStateHandle,
    entities: EntityRepository,
    private val pipeline: ObservationPipeline,
    private val controller: ObservationController,
    private val settings: SettingsRepository,
    private val signalMaps: SignalMapRepository,
) : ViewModel() {
    private val entityId = handle.toRoute<ProximityRoute>().entityId
    private val _state = MutableStateFlow(ProximityUiState())
    val state: StateFlow<ProximityUiState> = _state.asStateFlow()
    val tickSound: StateFlow<Boolean> = settings.settings.map { it.tickSound }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private val _mapping = MutableStateFlow(false)

    /** The signal map for this radio, kept until Reset or until another radio is mapped. */
    val map: StateFlow<SignalMapUi> = combine(signalMaps.state, _mapping, settings.settings) { m, on, prefs ->
        SignalMapUi(m.takeIf { it.entityId == entityId } ?: SignalMap(entityId), on, prefs.onlineRouteMaps)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SignalMapUi())
    private var mapJob: Job? = null
    private var tone: ToneGenerator? = null
    private var tickJob: Job? = null

    init {
        controller.setBoost(true)
        viewModelScope.launch {
            val entity = entities.get(entityId)
            while (isActive) {
                val now = System.currentTimeMillis()
                val samples = pipeline.trail(entityId)
                val last = pipeline.lastHeard(entityId)
                val gone = last == null || now - last > GONE_MS
                _state.value = ProximityUiState(
                    entity = entity,
                    cue = Proximity.cue(samples, now, last, gone),
                    samples = samples,
                    now = now,
                    recentDbm = Proximity.average(samples, now, 2_000, 0),
                    earlierDbm = Proximity.average(samples, now, 8_000, 3_500),
                )
                delay(250)
            }
        }
        tickJob = viewModelScope.launch {
            while (isActive) {
                val s = _state.value
                val interval = if (tickSound.value) Proximity.tickIntervalMs(s.samples.lastOrNull()?.second, s.cue) else null
                if (interval != null) {
                    val t = tone ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 50) }.getOrNull()?.also { tone = it }
                    t?.startTone(ToneGenerator.TONE_PROP_BEEP, 25)
                    delay(interval)
                } else {
                    delay(250)
                }
            }
        }
    }

    fun setTick(on: Boolean) = viewModelScope.launch { settings.setTickSound(on) }

    fun setMapping(on: Boolean) {
        mapJob?.cancel()
        mapJob = if (on) viewModelScope.launch { signalMaps.follow(entityId) } else null
        _mapping.value = on
    }

    fun resetMap() = signalMaps.reset(entityId)

    override fun onCleared() {
        controller.setBoost(false)
        tone?.release()
    }

    companion object {
        private const val GONE_MS = 30_000L
    }
}

data class SignalMapUi(val map: SignalMap = SignalMap(), val mapping: Boolean = false, val online: Boolean = false)

@Composable
internal fun ProximityScreen(onBack: () -> Unit, vm: ProximityViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tick by vm.tickSound.collectAsStateWithLifecycle()
    val map by vm.map.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Location only places the walk on a map; without it the map stays a local picture.
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setMapping(true) }
    val onMapping: (Boolean) -> Unit = { on ->
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (on && !granted) locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) else vm.setMapping(on)
    }
    ProximityContent(state, tick, vm::setTick, onBack, map, onMapping, vm::resetMap)
}

@Composable
internal fun ProximityContent(
    state: ProximityUiState,
    tick: Boolean,
    onTick: (Boolean) -> Unit,
    onStop: () -> Unit,
    map: SignalMapUi = SignalMapUi(),
    onMapping: (Boolean) -> Unit = {},
    onResetMap: () -> Unit = {},
) {
    val c = SotreusTheme.colors
    val e = state.entity
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onStop) { StatusPill(stringResource(R.string.prox_listening), filled = false) }
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
            MonoLabel(stringResource(R.string.prox_kicker))
            AutoSizeText(e?.let { entityTitle(it.userName, it.advertisedName, it.radio, it.family) } ?: "", SotreusTheme.typography.titleS, c.text)
        }
        if (e != null && e.radio == RadioKind.WIFI) {
            CaveatBox(stringResource(R.string.prox_kicker), stringResource(R.string.prox_wifi_slow))
        }
        val dash = stringResource(R.string.prox_dash)
        Column(
            Modifier.fillMaxWidth().background(c.attentionSurfaceLarge, SotreusTheme.shapes.hero).border(1.dp, c.attentionLine, SotreusTheme.shapes.hero)
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s),
        ) {
            MonoLabel(stringResource(R.string.prox_compared), small = true)
            Text(
                cueLabel(state.cue),
                style = SotreusTheme.typography.displayXL,
                color = if (state.cue == ProximityCue.QUIET || state.cue == ProximityCue.GONE || state.cue == ProximityCue.LISTENING) c.textMuted else c.accent,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                stringResource(R.string.prox_now_earlier, state.recentDbm?.toString() ?: dash, state.earlierDbm?.toString() ?: dash).replace("-", "−"),
                style = SotreusTheme.typography.monoValue,
                color = c.textSoft,
            )
        }
        RssiSparkline(
            samples = state.samples,
            now = state.now,
            topLabel = stringResource(R.string.prox_spark_top),
            bottomLabel = stringResource(R.string.prox_spark_bottom),
            contentDescription = stringResource(R.string.prox_spark_a11y, state.samples.lastOrNull()?.second?.toString() ?: dash),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
            listOf(ProximityCue.VERY_STRONG, ProximityCue.LOUDER, ProximityCue.SAME, ProximityCue.QUIETER, ProximityCue.QUIET, ProximityCue.GONE).forEach { cue ->
                StateChip(cueLabel(cue), if (cue == state.cue) ChipTone.ACCENT_FILLED else ChipTone.NEUTRAL, small = false)
            }
        }
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.prox_guidance_lead))
                append(" ")
                // Required copy (HANDOFF_V1_UI.md §8).
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = c.text)) { append(stringResource(R.string.prox_guidance_strong)) }
            },
            style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f),
            color = c.textMuted,
        )
        SignalMapCard(map, onMapping, onResetMap)
        SwitchRow(stringResource(R.string.prox_tick), tick, onTick, subtitle = stringResource(R.string.prox_tick_sub))
        Spacer(Modifier.weight(1f))
        GhostButton(stringResource(R.string.prox_stop), onStop, minHeight = SotreusTheme.sizes.buttonPrimary, strong = true)
    }
}

/** Walk-and-paint map (Fieldwatch Hunt). Opt-in per check; colors where this phone was, never the other radio. */
@Composable
private fun SignalMapCard(ui: SignalMapUi, onMapping: (Boolean) -> Unit, onReset: () -> Unit) {
    val c = SotreusTheme.colors
    val m = ui.map
    SotreusCard {
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            MonoLabel(stringResource(R.string.heatmap_kicker))
            Text(stringResource(R.string.heatmap_lead), style = SotreusTheme.typography.body, color = c.textMuted)
            if (ui.mapping || m.marks.isNotEmpty()) {
                val f = m.field
                SignalHeatMap(
                    heat = f?.let { HeatImage(it.pixels, it.cols, it.rows, it.north, it.west, it.south, it.east) },
                    hereLat = m.hereLat.takeIf { ui.mapping },
                    hereLon = m.hereLon.takeIf { ui.mapping },
                    uncertaintyM = if (ui.mapping) m.uncertaintyM else 0.0,
                    hotLat = f?.hotLat,
                    hotLon = f?.hotLon,
                    onGlobe = m.onGlobe,
                    online = ui.online,
                    holdFlat = m.holdFlat,
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                )
                val status = when {
                    !m.sensors -> R.string.heatmap_no_sensors
                    m.holdFlat -> R.string.heatmap_hold_flat
                    !m.onGlobe -> R.string.heatmap_no_gps
                    else -> null
                }
                status?.let { Text(stringResource(it), style = SotreusTheme.typography.body, color = c.textSoft) }
                // Required copy: the loudest patch is not the radio's position.
                Text(stringResource(R.string.heatmap_caveat), style = SotreusTheme.typography.body, color = c.textMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
                CompactButton(stringResource(if (ui.mapping) R.string.heatmap_pause else R.string.heatmap_start), { onMapping(!ui.mapping) })
                if (m.marks.isNotEmpty()) CompactButton(stringResource(R.string.heatmap_reset), onReset)
            }
        }
    }
}

@Composable
private fun cueLabel(cue: ProximityCue) = stringResource(
    when (cue) {
        ProximityCue.VERY_STRONG -> R.string.cue_very_strong
        ProximityCue.LOUDER -> R.string.cue_louder
        ProximityCue.SAME -> R.string.cue_same
        ProximityCue.QUIETER -> R.string.cue_quieter
        ProximityCue.QUIET -> R.string.cue_quiet
        ProximityCue.GONE -> R.string.cue_gone
        ProximityCue.LISTENING -> R.string.cue_listening
    },
)

@Preview(widthDp = 390, heightDp = 900, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun ProximityPreview() {
    val now = FakeSotreusData.NOW
    val samples = (0 until 240).map { i -> now - 60_000 + i * 250L to (-70 + i / 14) }
    SotreusTheme {
        ProximityContent(ProximityUiState(FakeSotreusData.greyTag, ProximityCue.LOUDER, samples, now, -52, -61), tick = true, onTick = {}, onStop = {})
    }
}

@Preview(widthDp = 390, heightDp = 1400, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun ProximitySignalMapPreview() {
    val now = FakeSotreusData.NOW
    val samples = (0 until 240).map { i -> now - 60_000 + i * 250L to (-70 + i / 14) }
    val marks = (0 until 20).map { i -> app.sotreus.intelligence.fieldwatch.GpsSample(now - 20_000 + i * 1_000L, 1.0, 1.0 + i * 1.5e-5, -90 + i * 2) }
    val map = SignalMapUi(SignalMap(FakeSotreusData.greyTag.id, marks, 1.0, 1.0 + 19 * 1.5e-5, onGlobe = false), mapping = true)
    SotreusTheme {
        ProximityContent(ProximityUiState(FakeSotreusData.greyTag, ProximityCue.LOUDER, samples, now, -52, -61), tick = true, onTick = {}, onStop = {}, map = map)
    }
}
