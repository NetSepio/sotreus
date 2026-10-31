package app.sotreus.feature.journey

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sotreus.core.data.field.CameraList
import app.sotreus.core.data.field.KnownCamerasRepository
import app.sotreus.core.data.field.LivePathRepository
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

data class RouteMapState(val points: List<Pair<Double, Double>> = emptyList(), val online: Boolean = false, val saved: Boolean = false, val marks: List<PathMarks.Placed<SessionEventEntity>> = emptyList(), val approximate: Boolean = false)

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
) : ViewModel() {
    private val id = handle.toRoute<RouteMapRoute>().sessionId
    private val session = if (id > 0) sessions.observe(id) else sessions.observeActive()
    private val stored = session.flatMapLatest { row -> if (row?.geotag == true) sessions.observeLocations(row.id) else flowOf(emptyList()) }
    private val events = session.flatMapLatest { row -> if (row != null) sessions.observeEvents(row.id) else flowOf(emptyList()) }
    val state = combine(session, stored, live.state, settings.settings, events) { row, locations, path, prefs, heard ->
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
            prefs.onlineRouteMaps, !memory && row?.geotag == true, marks, approximate,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteMapState())

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
    ScreenColumn(top = SotreusTheme.spacing.screenH) {
        BackTopBar(onBack = onBack)
        ScreenTitle(stringResource(R.string.route_title), lead = stringResource(R.string.route_lead))
        if (state.points.isEmpty()) {
            CaveatBox(stringResource(R.string.route_empty_title), stringResource(R.string.route_empty_body))
        } else {
            // Same wording as the session timeline, so a mark reads like its timeline row.
            val marks = state.marks.map { RouteMark(it.lat, it.lon, timelineItem(it.item).text, it.item.kind == SessionEventKind.ATTENTION) }
            val cameras = known.cameras.map { RouteCamera(it.lat, it.lon, cameraLabel(it)) }
            RouteMap(state.points, state.online, Modifier.fillMaxWidth().height(420.dp), marks, cameras)
            MonoLabel(stringResource(if (state.saved) R.string.route_saved else R.string.route_memory))
            if (state.approximate) MonoLabel(stringResource(R.string.route_approximate))
            if (state.marks.isNotEmpty()) MonoLabel(pluralStringResource(R.plurals.route_marks, state.marks.size, state.marks.size))
            if (known.on) KnownCamerasSection(known)
        }
        GhostButton(stringResource(R.string.route_tools), onTools)
    }
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

/** Fixes this tight draw the route; looser ones are used only when nothing tighter was saved. */
private const val ACCURATE_M = 50f
