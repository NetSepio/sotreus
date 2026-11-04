package app.sotreus.feature.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.sotreus.core.testing.FakeSotreusData
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.field.CameraList
import app.sotreus.core.data.field.KnownCamerasRepository
import app.sotreus.core.data.field.LivePathRepository
import app.sotreus.core.data.field.OfflineMapRepository
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.model.MapBackground
import app.sotreus.intelligence.fieldwatch.CameraRow
import app.sotreus.intelligence.fieldwatch.FlockCamera
import app.sotreus.intelligence.fieldwatch.GpsSample
import app.sotreus.intelligence.fieldwatch.PathCameras
import app.sotreus.intelligence.fieldwatch.SensorKind
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.navigation.RouteMapRoute
import app.sotreus.core.ui.*
import app.sotreus.core.database.entity.SessionEventEntity
import app.sotreus.core.model.SessionEventKind
import app.sotreus.intelligence.PathMarks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class RouteMapState(
    val points: List<Pair<Double, Double>> = emptyList(),
    val background: MapBackground = MapBackground.NONE,
    val saved: Boolean = false,
    val marks: List<PathMarks.Placed<SessionEventEntity>> = emptyList(),
    val approximate: Boolean = false,
    /** True while the path is the in-memory live path; its newest end is "now". */
    val live: Boolean = false,
    /** Downloaded area was chosen but none is saved yet, so the plain grid shows. */
    val offlineMissing: Boolean = false,
)

/** Downloaded camera lists around the path. Off when both layer switches are off. */
data class RouteCameras(
    val on: Boolean = false,
    /** A switched-on list that is not on the phone yet. */
    val missing: Boolean = false,
    val cameras: List<FlockCamera> = emptyList(),
    val tally: PathCameras.Tally? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RouteMapViewModel @Inject constructor(
    handle: SavedStateHandle,
    sessions: SessionDao,
    live: LivePathRepository,
    settings: SettingsRepository,
    known: KnownCamerasRepository,
    location: PhoneLocation,
    private val offline: OfflineMapRepository,
) : ViewModel() {
    init { offline.refresh() }
    private val id = handle.toRoute<RouteMapRoute>().sessionId
    private val session = if (id > 0) sessions.observe(id) else sessions.observeActive()
    private val stored = session.flatMapLatest { row -> if (row?.geotag == true) sessions.observeLocations(row.id) else flowOf(emptyList()) }
    private val events = session.flatMapLatest { row -> if (row != null) sessions.observeEvents(row.id) else flowOf(emptyList()) }
    private val background = combine(settings.settings, offline.state) { prefs, _ -> prefs.mapBackground to offline.effective(prefs.mapBackground) }
    val state = combine(session, stored, live.state, combine(settings.settings, background, ::Pair), events) { row, locations, path, (prefs, bg), heard ->
        // An empty live path (no GPS yet) falls back to the session's saved route instead of showing nothing.
        val memory = path.enabled && (id <= 0 || path.sessionId == id) && row?.endedAtMs == null && (path.points.isNotEmpty() || row?.geotag != true)
        val accurate = locations.filter { it.accuracyM?.let { a -> a in 0f..ACCURATE_M } == true }
        // Indoors only network fixes may arrive; show them rather than nothing, labelled as approximate.
        val approximate = !memory && accurate.isEmpty() && locations.isNotEmpty()
        val timed = if (memory) path.points.map { PathMarks.PathPoint(it.at, it.lat, it.lon) } else (if (approximate) locations else accurate)
            .map { PathMarks.PathPoint(it.atMs, it.lat, it.lon) }
        val step = (timed.size / 2_000).coerceAtLeast(1)
        // Heard radios sit on this phone's path at the time they were heard; never on the radio.
        val marks = PathMarks.place(heard.filter { it.kind in MARKED }.map { it.atMs to it }, timed)
        RouteMapState(
            timed.filterIndexed { index, _ -> index % step == 0 || index == timed.lastIndex }.map { it.lat to it.lon },
            bg.second, !memory && row?.geotag == true, marks, approximate, live = memory,
            offlineMissing = bg.first == MapBackground.OFFLINE && bg.second != MapBackground.OFFLINE,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteMapState())

    /** This phone's latest fix on a live path, while it is fresh. An older fix is never drawn as now. */
    val here = combine(location.latest, state.map { it.live }.distinctUntilChanged(), ticker()) { _, isLive, now ->
        if (!isLive) null else location.fresh(now, HERE_FRESH_MS)?.let { RouteHere(it.lat, it.lon, it.accuracyM) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Cameras from the downloaded lists near the path, and the ones it passed within 150 m. */
    val cameras = combine(
        state.map { it.points }.distinctUntilChanged(),
        settings.settings.map { it.cameraPlates to it.cameraOthers }.distinctUntilChanged(),
        known.state.map { lists -> lists.mapValues { it.value.count } }.distinctUntilChanged(),
    ) { points, layers, counts -> Triple(points, layers, counts) }
        .mapLatest { (points, layers, counts) ->
            val (plates, others) = layers
            if (!plates && !others) return@mapLatest RouteCameras()
            val missing = (plates && counts[CameraList.PLATES] == null) || (others && counts[CameraList.OTHERS] == null)
            if (points.isEmpty()) return@mapLatest RouteCameras(on = true, missing = missing)
            val south = points.minOf { it.first } - MARGIN_DEG
            val north = points.maxOf { it.first } + MARGIN_DEG
            val west = points.minOf { it.second } - MARGIN_DEG
            val east = points.maxOf { it.second } + MARGIN_DEG
            val near = known.inBox(plates, others, south, west, north, east, CAMERA_CAP)
            val tally = known.tally(plates, others, points.mapIndexed { i, (lat, lon) -> GpsSample(i.toLong(), lat, lon) })
            RouteCameras(on = true, missing = missing, cameras = near, tally = tally)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteCameras())
}

@Composable
internal fun RouteMapScreen(onBack: () -> Unit, onTools: () -> Unit, vm: RouteMapViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val known by vm.cameras.collectAsStateWithLifecycle()
    val here by vm.here.collectAsStateWithLifecycle()
    RouteMapContent(state, known, here, onBack, onTools)
}

/** Full-screen map; what the map shows and its caveats sit in a sheet so the map has the whole screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteMapContent(state: RouteMapState, known: RouteCameras, here: RouteHere?, onBack: () -> Unit, onTools: () -> Unit) {
    // A live path with no accurate fix yet still opens on this phone's latest fix and its accuracy ring. Once shown,
    // the map stays when that fix goes stale; the dot goes away instead of the whole map.
    var shown by rememberSaveable { mutableStateOf(false) }
    if (state.points.isNotEmpty() || here != null) shown = true
    if (!shown) {
        ScreenColumn(top = SotreusTheme.spacing.screenH) {
            BackTopBar(onBack = onBack)
            ScreenTitle(stringResource(R.string.route_title), lead = stringResource(R.string.route_lead))
            CaveatBox(stringResource(R.string.route_empty_title), stringResource(R.string.route_empty_body))
            GhostButton(stringResource(R.string.route_tools), onTools)
        }
        return
    }
    var details by rememberSaveable { mutableStateOf(false) }
    val s = SotreusTheme.spacing
    // Same wording as the session timeline, so a mark reads like its timeline row.
    val marks = state.marks.map { RouteMark(it.lat, it.lon, timelineItem(it.item).text, it.item.kind == SessionEventKind.ATTENTION) }
    val cameras = known.cameras.map { RouteCamera(it.lat, it.lon, cameraLabel(it)) }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(onBack = onBack, modifier = Modifier.padding(horizontal = s.screenH)) {
            Text(stringResource(R.string.route_title), style = SotreusTheme.typography.body, color = SotreusTheme.colors.text)
            HGap(s.m)
            CompactButton(stringResource(R.string.route_details), { details = true })
        }
        RouteMap(state.points, state.background, Modifier.fillMaxWidth().weight(1f), marks, cameras, state.approximate, here, state.live)
        Column(Modifier.fillMaxWidth().padding(horizontal = s.screenH, vertical = s.m), verticalArrangement = Arrangement.spacedBy(s.xs)) {
            MonoLabel(routeStatus(state), small = true)
            if (state.points.isEmpty()) MonoLabel(stringResource(R.string.route_waiting_fix), small = true)
            if (state.approximate) MonoLabel(stringResource(R.string.route_approximate), small = true)
        }
    }
    if (details) {
        ModalBottomSheet(
            onDismissRequest = { details = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SotreusTheme.colors.surfaceRaised,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = s.screenH)
                    .padding(bottom = s.screenBottom).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(s.section),
            ) {
                MonoLabel(stringResource(R.string.route_title))
                Text(stringResource(R.string.route_lead), style = SotreusTheme.typography.bodyS, color = SotreusTheme.colors.textMuted)
                KeyValueTable(listOf(
                    stringResource(R.string.route_key_start) to stringResource(R.string.route_key_start_value),
                    stringResource(R.string.route_key_tip) to stringResource(if (here != null) R.string.route_key_tip_live else R.string.route_key_tip_end),
                    stringResource(R.string.route_key_line) to stringResource(if (state.approximate) R.string.route_key_line_approx else R.string.route_key_line_value),
                    stringResource(R.string.route_key_map) to stringResource(when (state.background) {
                        MapBackground.NONE -> if (state.offlineMissing) R.string.route_map_offline_missing else R.string.route_map_none
                        MapBackground.OFFLINE -> R.string.route_map_offline
                        MapBackground.ONLINE -> R.string.route_map_online
                    }),
                ))
                if (state.marks.isNotEmpty()) MonoLabel(pluralStringResource(R.plurals.route_marks, state.marks.size, state.marks.size))
                if (known.on) KnownCamerasSection(known)
                GhostButton(stringResource(R.string.route_tools), { details = false; onTools() })
            }
        }
    }
}

@Composable
private fun routeStatus(state: RouteMapState): String {
    val source = stringResource(if (state.saved) R.string.route_saved else R.string.route_memory)
    val map = stringResource(when (state.background) {
        MapBackground.NONE -> if (state.offlineMissing) R.string.route_status_offline_missing else R.string.route_status_none
        MapBackground.OFFLINE -> R.string.route_status_offline
        MapBackground.ONLINE -> R.string.route_status_online
    })
    return "$source · $map"
}

@Composable
private fun KnownCamerasSection(known: RouteCameras) {
    SectionHeader(stringResource(R.string.route_cameras_header)) { StateChip(stringResource(R.string.route_cameras_chip), ChipTone.NEUTRAL) }
    val counts = known.tally?.counts.orEmpty()
    when {
        counts.isNotEmpty() -> KeyValueTable(counts.map { stringResource(rowName(it.row)) to stringResource(R.string.route_cameras_row, it.facing, it.near) })
        known.tally?.pathReady == true -> MonoLabel(stringResource(R.string.route_cameras_none), small = true)
    }
    if (known.missing) MonoLabel(stringResource(R.string.route_cameras_missing), small = true)
    MonoLabel(stringResource(R.string.route_cameras_credit), small = true)
}

@Composable
private fun cameraLabel(cam: FlockCamera): String {
    val kind = stringResource(
        when (cam.kind) {
            SensorKind.PLATE -> if (cam.flock) R.string.camera_flock else R.string.camera_plate
            SensorKind.SPEED -> R.string.camera_speed
            SensorKind.RED -> R.string.camera_red
            SensorKind.GUNSHOT -> R.string.camera_gunshot
            SensorKind.CAMERA -> R.string.camera_other
        },
    )
    val parts = listOfNotNull(
        kind,
        cam.brand.takeIf { it.isNotBlank() && !(cam.flock && it.contains("flock", ignoreCase = true)) },
        cam.facings.takeIf { it.isNotEmpty() }?.let { f -> stringResource(R.string.camera_faces, f.map(::compassPoint).distinct().joinToString(", ")) },
    )
    return stringResource(R.string.camera_label, parts.joinToString(" · "))
}

private fun rowName(row: CameraRow) = when (row) {
    CameraRow.FLOCK -> R.string.camera_flock
    CameraRow.PLATE -> R.string.camera_plate
    CameraRow.SPEED -> R.string.camera_speed
    CameraRow.RED -> R.string.camera_red
    CameraRow.CAMERA -> R.string.camera_other
}

/** Which way a mapped camera points, as compass letters. It is the camera's facing, not a radio's direction. */
private fun compassPoint(deg: Int): String {
    val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val turned = ((deg % 360) + 360) % 360
    return names[((turned + 22) / 45) % 8]
}

/** About 1 km around the path, so cameras just off it are on the map too. */
private const val MARGIN_DEG = 0.01
private const val CAMERA_CAP = 3_000

private val MARKED = setOf(SessionEventKind.NEW_FINGERPRINT, SessionEventKind.TAGGED_REENCOUNTER, SessionEventKind.FAMILY_SIGNATURE, SessionEventKind.ATTENTION)

/** A fix older than this is not drawn as this phone's position now. */
private const val HERE_FRESH_MS = 30_000L

/** Re-checks the latest fix's age even when no new fix arrives, so "now" goes away when it goes stale. */
private fun ticker() = flow { while (true) { emit(System.currentTimeMillis()); kotlinx.coroutines.delay(5_000) } }

/** Fixes this tight draw the route; looser ones are used only when nothing tighter was saved. */
private const val ACCURATE_M = 50f

@Preview(widthDp = 390, heightDp = 844, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun RouteMapPreview() {
    val route = FakeSotreusData.ROUTE
    SotreusTheme {
        RouteMapContent(
            RouteMapState(route, MapBackground.NONE, saved = false, live = true),
            RouteCameras(), RouteHere(route.last().first, route.last().second, 12f), {}, {},
        )
    }
}
