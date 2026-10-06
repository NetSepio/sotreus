package app.sotreus.feature.context

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.context.AircraftState
import app.sotreus.context.ContextRepository
import app.sotreus.context.NearbyAircraft
import app.sotreus.context.SatelliteDetail
import app.sotreus.context.remoteid.LiveRemoteId
import app.sotreus.core.database.entity.ContextEventEntity
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.Provenance
import app.sotreus.core.navigation.AircraftRoute
import app.sotreus.core.navigation.RemoteIdRoute
import app.sotreus.core.navigation.SatelliteRoute
import app.sotreus.core.ui.BackTopBar
import app.sotreus.core.ui.CaveatBox
import app.sotreus.core.ui.EmptyHint
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.KeyValueTable
import app.sotreus.core.ui.LightButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.ScreenColumn
import app.sotreus.core.ui.ScreenTitle
import app.sotreus.core.ui.SectionHeader
import app.sotreus.core.ui.ageShort
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.dateTime
import app.sotreus.core.ui.dayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// ---- Aircraft ----

data class AircraftUiState(val loaded: Boolean = false, val aircraft: NearbyAircraft? = null, val ready: AircraftState.Ready? = null)

@HiltViewModel
class AircraftViewModel @Inject constructor(repo: ContextRepository, handle: SavedStateHandle) : ViewModel() {
    private val icao = handle.toRoute<AircraftRoute>().icao24
    val state: StateFlow<AircraftUiState> = repo.aircraft().map { a ->
        val ready = a as? AircraftState.Ready
        AircraftUiState(loaded = a !is AircraftState.Loading, aircraft = ready?.aircraft?.firstOrNull { it.report.icao24 == icao }, ready = ready)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AircraftUiState())
}

@Composable
internal fun AircraftScreen(onBack: () -> Unit, vm: AircraftViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack) { ProvenanceChip(Provenance.NETWORK) }
        val na = state.aircraft
        val ready = state.ready
        if (na == null || ready == null) {
            ScreenTitle(stringResource(R.string.ctx_air_detail_gone_title))
            if (state.loaded) EmptyHint(stringResource(R.string.ctx_air_detail_gone_kicker), stringResource(R.string.ctx_air_detail_gone_body))
            return@ScreenColumn
        }
        val r = na.report
        ScreenTitle(r.callsign ?: r.icao24.uppercase(), lead = stringResource(R.string.ctx_air_detail_lead))
        val unknown = stringResource(R.string.ctx_unknown)
        KeyValueTable(
            listOfNotNull(
                stringResource(R.string.ctx_kv_icao) to r.icao24.uppercase(),
                stringResource(R.string.ctx_kv_callsign) to (r.callsign ?: unknown),
                stringResource(R.string.ctx_kv_country) to (r.originCountry ?: unknown),
                stringResource(R.string.ctx_kv_altitude) to (if (r.onGround) stringResource(R.string.ctx_air_on_ground) else altitude(r.altitudeM) ?: unknown),
                stringResource(R.string.ctx_kv_speed) to (speed(r.speedMps) ?: unknown),
                stringResource(R.string.ctx_kv_course) to (r.courseDeg?.let { "${degrees(it)} ${compass(it)}" } ?: unknown),
                r.verticalRateMps?.let { stringResource(R.string.ctx_kv_vertical) to "%+.1f m/s".format(it) },
                r.squawk?.let { stringResource(R.string.ctx_kv_squawk) to it },
                stringResource(R.string.ctx_kv_distance) to km(na.distanceKm),
                stringResource(R.string.ctx_kv_position_age) to (r.positionAtMs?.let { stringResource(R.string.ctx_ago, ageShort(now - it)) } ?: unknown),
                stringResource(R.string.ctx_kv_source) to ready.provider,
            ),
            title = stringResource(R.string.ctx_air_detail_table),
        )
        if (r.lat != null && r.lon != null) {
            LightButton(stringResource(R.string.ctx_open_maps), { openInMaps(context, r.lat!!, r.lon!!, r.callsign ?: r.icao24) }, Modifier.fillMaxWidth())
        }
        CaveatBox(stringResource(R.string.ctx_air_caveat_kicker), stringResource(R.string.ctx_air_caveat_body, ready.provider))
    }
}

// ---- Satellite ----

data class SatelliteUiState(val loaded: Boolean = false, val detail: SatelliteDetail? = null)

@HiltViewModel
class SatelliteViewModel @Inject constructor(repo: ContextRepository, handle: SavedStateHandle) : ViewModel() {
    private val norad = handle.toRoute<SatelliteRoute>().noradId
    val state: StateFlow<SatelliteUiState> = flow {
        while (true) {
            emit(SatelliteUiState(true, repo.satellite(norad)))
            delay(10_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SatelliteUiState())
}

@Composable
internal fun SatelliteScreen(onBack: () -> Unit, vm: SatelliteViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack) { ProvenanceChip(Provenance.PREDICTED) }
        val d = state.detail
        if (d == null) {
            ScreenTitle(stringResource(R.string.ctx_sat_detail_missing_title))
            if (state.loaded) EmptyHint(stringResource(R.string.ctx_sat_detail_missing_kicker), stringResource(R.string.ctx_sat_detail_missing_body))
            return@ScreenColumn
        }
        val e = d.elements
        val l = d.look
        ScreenTitle(e.name, lead = groupName(e.group))
        MonoLabel(stringResource(R.string.ctx_sat_detail_now, if (l.aboveHorizon) stringResource(R.string.ctx_sat_above) else stringResource(R.string.ctx_sat_below)), small = true)
        KeyValueTable(
            listOf(
                stringResource(R.string.ctx_kv_elevation) to "%.1f°".format(l.elevationDeg),
                stringResource(R.string.ctx_kv_azimuth) to "${degrees(l.azimuthDeg)} ${compass(l.azimuthDeg)}",
                stringResource(R.string.ctx_kv_range) to km(l.rangeKm),
                stringResource(R.string.ctx_kv_orbit_alt) to km(l.altitudeKm),
                stringResource(R.string.ctx_kv_subpoint) to "%.2f, %.2f".format(l.subLat, l.subLon),
                stringResource(R.string.ctx_kv_norad) to e.noradId.toString(),
                stringResource(R.string.ctx_kv_epoch) to stringResource(R.string.ctx_ago, ageShort(d.atMs - e.epochMs)),
            ),
            title = stringResource(R.string.ctx_sat_detail_table),
        )
        MonoLabel(locationSource(d.location), small = true)
        SectionHeader(stringResource(R.string.ctx_sat_detail_passes, d.passes.size))
        if (d.passes.isEmpty()) {
            MonoLabel(stringResource(R.string.ctx_sat_detail_no_passes), small = true)
        } else {
            Column {
                RowDivider(strong = true)
                d.passes.forEach { p ->
                    InfoRow(
                        title = stringResource(R.string.ctx_sat_detail_pass_title, dayLabel(p.startMs, d.atMs), clockTime(p.startMs), clockTime(p.endMs)),
                        subtitle = stringResource(
                            R.string.ctx_sat_detail_pass_sub, degrees(p.maxElevationDeg)!!,
                            compass(p.riseAzimuthDeg.toDouble()), compass(p.setAzimuthDeg.toDouble()),
                        ),
                    )
                }
            }
        }
        CaveatBox(stringResource(R.string.ctx_sat_caveat_kicker), stringResource(R.string.ctx_sat_caveat_body))
    }
}

// ---- Remote ID ----

data class RemoteIdUiState(val live: LiveRemoteId? = null, val track: List<ContextEventEntity> = emptyList())

@HiltViewModel
class RemoteIdViewModel @Inject constructor(repo: ContextRepository, handle: SavedStateHandle) : ViewModel() {
    private val subject = handle.toRoute<RemoteIdRoute>().subjectId
    val state: StateFlow<RemoteIdUiState> = combine(repo.remoteId.live, repo.remoteIdTrack(subject)) { live, track ->
        RemoteIdUiState(live.firstOrNull { it.subjectId == subject }, track)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteIdUiState())
}

@Composable
internal fun RemoteIdScreen(onBack: () -> Unit, vm: RemoteIdViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val live = state.live
    val last = state.track.firstOrNull()
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack) { ProvenanceChip(Provenance.SENSED) }
        if (live == null && last == null) {
            ScreenTitle(stringResource(R.string.ctx_rid_unnamed))
            EmptyHint(stringResource(R.string.ctx_rid_detail_gone_kicker), stringResource(R.string.ctx_rid_detail_gone_body))
            return@ScreenColumn
        }
        val title = remoteIdTitle(live?.maker ?: last?.title?.ifBlank { null }, live?.selfId, live?.uasId ?: last?.subjectId)
        ScreenTitle(title, lead = stringResource(if (live != null) R.string.ctx_rid_detail_lead_live else R.string.ctx_rid_detail_lead_earlier))
        val unknown = stringResource(R.string.ctx_unknown)
        val lat = live?.lat ?: last?.lat
        val lon = live?.lon ?: last?.lon
        val opLat = live?.operatorLat ?: last?.operatorLat
        val opLon = live?.operatorLon ?: last?.operatorLon
        KeyValueTable(
            listOfNotNull(
                stringResource(R.string.ctx_kv_uas_id) to (live?.uasId ?: last?.subjectId ?: unknown),
                live?.maker?.let { stringResource(R.string.ctx_kv_maker) to it },
                live?.selfId?.let { stringResource(R.string.ctx_kv_self_id) to it },
                stringResource(R.string.ctx_kv_radio) to (live?.let { stringResource(if (it.radio == app.sotreus.intelligence.fieldwatch.RadioKind.WIFI) R.string.ctx_rid_wifi else R.string.ctx_rid_ble) } ?: last?.source ?: unknown),
                live?.let { stringResource(R.string.ctx_kv_signal) to stringResource(R.string.ctx_rssi, it.rssi) },
                live?.let { stringResource(R.string.ctx_kv_address) to it.address },
                stringResource(R.string.ctx_kv_altitude) to (altitude(live?.altM ?: last?.altM) ?: unknown),
                stringResource(R.string.ctx_kv_speed) to (speed(live?.speedMps ?: last?.speedMps) ?: unknown),
                stringResource(R.string.ctx_kv_course) to ((live?.courseDeg ?: last?.courseDeg)?.let { "${degrees(it)} ${compass(it)}" } ?: unknown),
                stringResource(R.string.ctx_kv_position) to (if (lat != null && lon != null) "%.5f, %.5f".format(lat, lon) else unknown),
                stringResource(R.string.ctx_kv_operator) to (if (opLat != null && opLon != null) "%.5f, %.5f".format(opLat, opLon) else unknown),
                stringResource(R.string.ctx_kv_last_heard) to stringResource(R.string.ctx_ago, ageShort(now - (live?.lastSeenMs ?: last?.endMs ?: last?.atMs ?: now))),
                live?.let { stringResource(R.string.ctx_kv_first_heard) to dateTime(it.firstSeenMs) },
            ),
            title = stringResource(R.string.ctx_rid_detail_table),
        )
        if (lat != null && lon != null) LightButton(stringResource(R.string.ctx_open_maps_aircraft), { openInMaps(context, lat, lon, title) }, Modifier.fillMaxWidth())
        if (opLat != null && opLon != null) LightButton(stringResource(R.string.ctx_open_maps_operator), { openInMaps(context, opLat, opLon, title) }, Modifier.fillMaxWidth())
        if (state.track.isNotEmpty()) {
            SectionHeader(stringResource(R.string.ctx_rid_track_header, state.track.size))
            Column {
                RowDivider(strong = true)
                state.track.take(50).forEach { t ->
                    InfoRow(
                        title = clockTime(t.atMs) + (t.endMs?.takeIf { it - t.atMs >= 60_000 }?.let { "–" + clockTime(it) } ?: ""),
                        subtitle = listOfNotNull(
                            if (t.lat != null && t.lon != null) "%.5f, %.5f".format(t.lat, t.lon) else null,
                            altitude(t.altM),
                            t.source,
                        ).joinToString(" · "),
                    )
                }
            }
        }
        CaveatBox(stringResource(R.string.ctx_rid_caveat_kicker), stringResource(R.string.ctx_rid_caveat_body))
    }
}

/** Hands coordinates to the user's own maps app (geo: URI). Nothing is sent by Sotreus. */
private fun openInMaps(context: Context, lat: Double, lon: Double, label: String) {
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), null)) }
}
