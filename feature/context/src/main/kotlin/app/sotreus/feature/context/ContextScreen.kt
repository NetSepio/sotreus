package app.sotreus.feature.context

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sotreus.context.AircraftState
import app.sotreus.context.ContextRepository
import app.sotreus.context.SatelliteState
import app.sotreus.context.remoteid.LiveRemoteId
import app.sotreus.feature.context.scene.ContextScene
import app.sotreus.feature.context.scene.SceneMode
import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.entity.ContextEventEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.AircraftMode
import app.sotreus.core.model.Provenance
import app.sotreus.core.model.SatelliteGroup
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.navigation.AircraftRoute
import app.sotreus.core.navigation.ContextSourcesRoute
import app.sotreus.core.navigation.RemoteIdRoute
import app.sotreus.core.navigation.SatelliteRoute
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.EmptyHint
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.InlineLink
import app.sotreus.core.ui.LightButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SectionHeader
import app.sotreus.core.ui.UnderlineTabs
import app.sotreus.core.ui.WarningBanner
import app.sotreus.core.ui.ageShort
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.dayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class ContextTab { AIRCRAFT, SATELLITES, REMOTE_ID }

data class ContextUiState(
    val tab: ContextTab = ContextTab.SATELLITES,
    val settings: SotreusSettings = SotreusSettings(),
    val aircraft: AircraftState = AircraftState.Off,
    val satellites: SatelliteState = SatelliteState.Loading,
    val liveRemoteId: List<LiveRemoteId> = emptyList(),
    val recentRemoteId: List<ContextEventEntity> = emptyList(),
    /** Every Remote ID record from the last 24 h, for drawing broadcast history. */
    val remoteIdHistory: List<ContextEventEntity> = emptyList(),
    val location: app.sotreus.context.ContextLocation? = null,
    val observing: Boolean = false,
    val now: Long = System.currentTimeMillis(),
)

@HiltViewModel
class ContextViewModel @Inject constructor(
    private val repo: ContextRepository,
    settings: SettingsRepository,
    pipeline: ObservationPipeline,
) : ViewModel() {
    private val tab = MutableStateFlow(ContextTab.SATELLITES)

    private val location = repo.locationUpdates().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<ContextUiState> = combine(
        combine(tab, settings.settings, location) { t, s, l -> Triple(t, s, l) },
        repo.aircraft(),
        repo.satellites(),
        combine(repo.remoteId.live, repo.recentRemoteId(System.currentTimeMillis() - DAY_MS)) { l, r -> l to r },
        pipeline.snapshot,
    ) { (t, s, l), a, sat, (live, recent), snap ->
        val loc = l ?: (a as? AircraftState.Ready)?.location ?: (sat as? SatelliteState.Ready)?.location
        ContextUiState(t, s, a, sat, live, recent.distinctBy { it.subjectId }, recent, loc, snap.observing, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContextUiState())

    fun tab(t: ContextTab) { tab.value = t }

    fun refreshAircraft() = repo.refreshAircraftNow()

    private companion object { const val DAY_MS = 24 * 3_600_000L }
}

@Composable
internal fun ContextScreen(navigate: (Any) -> Unit, vm: ContextViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ContextContent(state, vm::tab, vm::refreshAircraft, navigate)
}

@Composable
internal fun ContextContent(state: ContextUiState, onTab: (ContextTab) -> Unit, onRefreshAircraft: () -> Unit, navigate: (Any) -> Unit) {
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenTitle(stringResource(R.string.ctx_title)) }
            InlineLink(stringResource(R.string.ctx_sources_link), { navigate(ContextSourcesRoute) })
        }
        if (!state.settings.contextEnabled) {
            EmptyHint(stringResource(R.string.ctx_off_kicker), stringResource(R.string.ctx_off_body))
            LightButton(stringResource(R.string.ctx_open_sources), { navigate(ContextSourcesRoute) }, Modifier.fillMaxWidth())
            return@ScreenColumn
        }
        UnderlineTabs(
            listOf(
                ContextTab.SATELLITES to stringResource(R.string.ctx_tab_satellites),
                ContextTab.AIRCRAFT to stringResource(R.string.ctx_tab_aircraft),
                ContextTab.REMOTE_ID to stringResource(R.string.ctx_tab_remote_id),
            ),
            state.tab,
            onTab,
        )
        ContextScene(
            mode = when (state.tab) {
                ContextTab.SATELLITES -> SceneMode.GLOBE
                ContextTab.AIRCRAFT -> SceneMode.AIRCRAFT
                ContextTab.REMOTE_ID -> SceneMode.REMOTE_ID
            },
            location = state.location,
            satellites = state.satellites as? SatelliteState.Ready,
            aircraft = state.aircraft as? AircraftState.Ready,
            aircraftRadiusKm = state.settings.aircraftRadiusKm,
            drones = state.liveRemoteId,
            droneHistory = state.remoteIdHistory,
            navigate = navigate,
        )
        when (state.tab) {
            ContextTab.AIRCRAFT -> AircraftTab(state, onRefreshAircraft, navigate)
            ContextTab.SATELLITES -> SatellitesTab(state, navigate)
            ContextTab.REMOTE_ID -> RemoteIdTab(state, navigate)
        }
    }
}

@Composable
private fun AircraftTab(state: ContextUiState, onRefresh: () -> Unit, navigate: (Any) -> Unit) {
    when (val a = state.aircraft) {
        AircraftState.Off -> {
            EmptyHint(stringResource(R.string.ctx_air_off_kicker), stringResource(R.string.ctx_air_off_body))
            LightButton(stringResource(R.string.ctx_open_sources), { navigate(ContextSourcesRoute) }, Modifier.fillMaxWidth())
        }
        AircraftState.NoLocation -> EmptyHint(stringResource(R.string.ctx_no_location_kicker), stringResource(R.string.ctx_no_location_body))
        AircraftState.Loading -> MonoLabel(stringResource(R.string.ctx_air_loading), small = true)
        is AircraftState.Unavailable -> {
            EmptyHint(stringResource(R.string.ctx_air_unavailable_kicker), stringResource(problemText(a.problem)))
            LightButton(stringResource(R.string.ctx_air_retry), onRefresh, Modifier.fillMaxWidth())
        }
        is AircraftState.Ready -> {
            a.problem?.let { WarningBanner(stringResource(R.string.ctx_air_stale, ageShort(state.now - a.fetchedAtMs), stringResource(problemText(it)))) }
            MonoLabel(
                stringResource(
                    R.string.ctx_air_caption, a.provider, ageShort(state.now - a.providerTimeMs.takeIf { it > 0 }.let { it ?: a.fetchedAtMs }), a.radiusKm,
                    stringResource(if (a.mode == AircraftMode.EXACT_AREA) R.string.ctx_mode_exact else R.string.ctx_mode_coarse),
                ),
                small = true,
            )
            MonoLabel(locationSource(a.location), small = true)
            SectionHeader(stringResource(R.string.ctx_air_header, a.aircraft.size))
            if (a.aircraft.isEmpty()) {
                EmptyHint(stringResource(R.string.ctx_air_none_kicker), stringResource(R.string.ctx_air_none_body, a.radiusKm))
            } else {
                Column {
                    RowDivider(strong = true)
                    a.aircraft.forEach { (r, d) ->
                        val parts = listOfNotNull(
                            if (r.onGround) stringResource(R.string.ctx_air_on_ground) else altitude(r.altitudeM),
                            speed(r.speedMps),
                            r.courseDeg?.takeIf { !r.onGround || (r.speedMps ?: 0.0) > 1.5 }?.let { stringResource(R.string.ctx_course_short, compass(it)) },
                            km(d),
                        )
                        InfoRow(
                            title = r.callsign ?: r.icao24.uppercase(),
                            subtitle = parts.joinToString(" · "),
                            onClick = { navigate(AircraftRoute(r.icao24)) },
                        ) { ProvenanceChip(Provenance.NETWORK) }
                    }
                }
            }
            CaveatBox(stringResource(R.string.ctx_air_caveat_kicker), stringResource(R.string.ctx_air_caveat_body, a.provider))
        }
    }
}

internal fun problemText(p: AircraftState.Problem) = when (p) {
    AircraftState.Problem.RATE_LIMITED -> R.string.ctx_air_rate_limited
    AircraftState.Problem.NETWORK -> R.string.ctx_air_network
}

@Composable
private fun SatellitesTab(state: ContextUiState, navigate: (Any) -> Unit) {
    when (val s = state.satellites) {
        SatelliteState.Off -> {
            EmptyHint(stringResource(R.string.ctx_sat_off_kicker), stringResource(R.string.ctx_sat_off_body))
            LightButton(stringResource(R.string.ctx_open_sources), { navigate(ContextSourcesRoute) }, Modifier.fillMaxWidth())
        }
        SatelliteState.Loading -> MonoLabel(stringResource(R.string.ctx_sat_loading), small = true)
        SatelliteState.NoCatalog -> EmptyHint(stringResource(R.string.ctx_sat_no_catalog_kicker), stringResource(R.string.ctx_sat_no_catalog_body))
        SatelliteState.NoLocation -> EmptyHint(stringResource(R.string.ctx_no_location_kicker), stringResource(R.string.ctx_no_location_body))
        is SatelliteState.Ready -> {
            if (s.refreshFailed) WarningBanner(stringResource(R.string.ctx_sat_refresh_failed))
            MonoLabel(
                stringResource(R.string.ctx_sat_caption, s.catalogCount, s.catalogFetchedAtMs?.let { ageShort(state.now - it) } ?: "—"),
                small = true,
            )
            MonoLabel(locationSource(s.location), small = true)
            SectionHeader(stringResource(R.string.ctx_sat_overhead_header, s.overhead.size))
            if (s.overhead.isEmpty()) {
                MonoLabel(stringResource(R.string.ctx_sat_overhead_none), small = true)
            } else {
                Column {
                    RowDivider(strong = true)
                    s.overhead.take(40).forEach { o ->
                        InfoRow(
                            title = o.elements.name,
                            subtitle = stringResource(
                                R.string.ctx_sat_now_row, degrees(o.look.elevationDeg)!!, compass(o.look.azimuthDeg), km(o.look.altitudeKm),
                                groupName(o.elements.group),
                            ),
                            onClick = { navigate(SatelliteRoute(o.elements.noradId)) },
                        ) { ProvenanceChip(Provenance.PREDICTED) }
                    }
                }
            }
            if (s.geosynchronous.isNotEmpty()) {
                MonoLabel(
                    stringResource(R.string.ctx_sat_geo, s.geosynchronous.size, s.geosynchronous.take(3).joinToString(", ") { it.elements.name }),
                    small = true,
                )
            }
            SectionHeader(stringResource(R.string.ctx_sat_passes_header))
            val passes = s.passes
            when {
                passes == null -> MonoLabel(stringResource(R.string.ctx_sat_computing), small = true)
                passes.isEmpty() -> MonoLabel(stringResource(R.string.ctx_sat_passes_none), small = true)
                else -> Column {
                    RowDivider(strong = true)
                    passes.take(60).forEach { p ->
                        InfoRow(
                            title = p.elements.name,
                            subtitle = stringResource(
                                R.string.ctx_sat_pass_row, dayLabel(p.startMs, state.now), clockTime(p.startMs), clockTime(p.endMs),
                                degrees(p.maxElevationDeg)!!, groupName(p.elements.group),
                            ),
                            onClick = { navigate(SatelliteRoute(p.elements.noradId)) },
                        ) { ProvenanceChip(Provenance.PREDICTED) }
                    }
                }
            }
            CaveatBox(stringResource(R.string.ctx_sat_caveat_kicker), stringResource(R.string.ctx_sat_caveat_body))
        }
    }
}

@Composable
internal fun groupName(g: SatelliteGroup) = stringResource(
    when (g) {
        SatelliteGroup.EARTH_OBSERVATION -> R.string.ctx_group_eo
        SatelliteGroup.WEATHER -> R.string.ctx_group_weather
        SatelliteGroup.SPACE_STATIONS -> R.string.ctx_group_stations
    },
)

@Composable
private fun RemoteIdTab(state: ContextUiState, navigate: (Any) -> Unit) {
    if (!state.settings.remoteIdEnabled) {
        EmptyHint(stringResource(R.string.ctx_rid_off_kicker), stringResource(R.string.ctx_rid_off_body))
        LightButton(stringResource(R.string.ctx_open_sources), { navigate(ContextSourcesRoute) }, Modifier.fillMaxWidth())
        return
    }
    if (!state.observing) WarningBanner(stringResource(R.string.ctx_rid_not_observing))
    SectionHeader(stringResource(R.string.ctx_rid_live_header, state.liveRemoteId.size), accent = state.liveRemoteId.isNotEmpty())
    if (state.liveRemoteId.isEmpty()) {
        MonoLabel(stringResource(R.string.ctx_rid_none), small = true)
    } else {
        Column {
            RowDivider(strong = true)
            state.liveRemoteId.forEach { d ->
                InfoRow(
                    title = remoteIdTitle(d.maker, d.selfId, d.uasId),
                    subtitle = listOfNotNull(
                        stringResource(if (d.radio == app.sotreus.intelligence.fieldwatch.RadioKind.WIFI) R.string.ctx_rid_wifi else R.string.ctx_rid_ble),
                        stringResource(R.string.ctx_rssi, d.rssi),
                        altitude(d.altM),
                        stringResource(R.string.ctx_heard_ago, ageShort(state.now - d.lastSeenMs)),
                    ).joinToString(" · "),
                    onClick = { navigate(RemoteIdRoute(d.subjectId)) },
                ) { ProvenanceChip(Provenance.SENSED) }
            }
        }
    }
    val earlier = state.recentRemoteId.filter { r -> state.liveRemoteId.none { it.subjectId == r.subjectId } }
    if (earlier.isNotEmpty()) {
        SectionHeader(stringResource(R.string.ctx_rid_recent_header, earlier.size))
        Column {
            RowDivider(strong = true)
            earlier.forEach { r ->
                InfoRow(
                    title = remoteIdTitle(r.title.ifBlank { null }, null, r.subjectId),
                    subtitle = stringResource(R.string.ctx_rid_recent_row, r.source, dayLabel(r.atMs, state.now), clockTime(r.endMs ?: r.atMs)),
                    onClick = { navigate(RemoteIdRoute(r.subjectId)) },
                ) { ProvenanceChip(Provenance.SENSED) }
            }
        }
    }
    CaveatBox(stringResource(R.string.ctx_rid_caveat_kicker), stringResource(R.string.ctx_rid_caveat_body))
}

@Composable
internal fun remoteIdTitle(maker: String?, selfId: String?, uasId: String?): String =
    maker ?: selfId ?: uasId ?: stringResource(R.string.ctx_rid_unnamed)

@Composable
internal fun Muted(text: String) = Text(text, style = SotreusTheme.typography.caption, color = SotreusTheme.colors.textMuted)


@androidx.compose.ui.tooling.preview.Preview(widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun ContextAircraftPreview() {
    val now = app.sotreus.core.testing.FakeSotreusData.NOW
    val here = app.sotreus.context.ContextLocation(app.sotreus.context.GeoPoint(1.2834, 103.8607), app.sotreus.context.ContextLocation.Source.PLACE, "Office")
    fun report(id: String, call: String, alt: Double, d: Double) = app.sotreus.context.NearbyAircraft(
        app.sotreus.context.aircraft.AircraftReport(id, call, "Singapore", now - 4_000, now - 2_000, 1.3, 103.9, alt, false, 140.0, 46.0, 0.0, null),
        d,
    )
    val aircraft = AircraftState.Ready(
        listOf(report("76cc65", "SIA842", 1_920.0, 3.2), report("8a0aad", "TNU541", 12_367.0, 13.0)),
        now - 9_000, now - 10_000, AircraftMode.COARSE_AREA, 25, "OpenSky Network", here,
    )
    SotreusTheme { ContextContent(ContextUiState(tab = ContextTab.AIRCRAFT, aircraft = aircraft, now = now), {}, {}, {}) }
}
