package app.sotreus.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.MapBackground
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Where this phone was when it heard a radio during the session. The mark is on this phone's path,
 * never on the other radio.
 */
data class RouteMark(val lat: Double, val lon: Double, val label: String, val attention: Boolean = false)

/** A camera from a downloaded list (OpenStreetMap data). Mapped by people, not sensed by this phone. */
data class RouteCamera(val lat: Double, val lon: Double, val label: String)

/** This phone's latest fix, drawn as "now" on a live path. [accuracyM] draws the ring around it. */
data class RouteHere(val lat: Double, val lon: Double, val accuracyM: Float?)

/** Close view follows the newest part of the path; Whole path fits all of it. */
enum class RouteView { CLOSE, WHOLE }

/**
 * North-up phone path with heard-radio marks. With [MapBackground.NONE] a blank local style makes no network requests
 * and a faint grid the size of the scale bar stands in for streets; [MapBackground.OFFLINE] reads a downloaded area only. The start is a hollow dot, the newest end is solid,
 * and older parts of the path fade. An approximate (network-fix) path is dashed.
 */
@Composable
fun RouteMap(
    points: List<Pair<Double, Double>>,
    background: MapBackground,
    modifier: Modifier = Modifier,
    marks: List<RouteMark> = emptyList(),
    cameras: List<RouteCamera> = emptyList(),
    approximate: Boolean = false,
    here: RouteHere? = null,
    live: Boolean = false,
) {
    val c = SotreusTheme.colors
    var view by rememberSaveable { mutableStateOf(if (live) RouteView.CLOSE else RouteView.WHOLE) }
    var following by remember { mutableStateOf(true) }
    var metersPerPx by remember { mutableStateOf<Double?>(null) }
    val viewOptions = listOf(RouteView.CLOSE to stringResource(R.string.route_view_close), RouteView.WHOLE to stringResource(R.string.route_view_whole))
    if (LocalInspectionMode.current) {
        // Previews cannot host a MapView; show the frame and the controls only.
        Box(modifier.background(c.ink, SotreusTheme.shapes.listCard)) {
            SegmentedToggle(viewOptions, view, { view = it }, Modifier.align(Alignment.TopStart).widthIn(max = TOGGLE_MAX).padding(SotreusTheme.spacing.m))
            ScaleBar(PREVIEW_M_PER_PX, Modifier.align(Alignment.BottomEnd).padding(SotreusTheme.spacing.m))
        }
        return
    }
    val latest by rememberUpdatedState(points)
    val latestMarks by rememberUpdatedState(marks)
    val latestCameras by rememberUpdatedState(cameras)
    val latestHere by rememberUpdatedState(here)
    val clusterLabel = stringResource(R.string.route_camera_cluster)
    val heardLabel = stringResource(R.string.route_mark_heard)
    val hereLabel = stringResource(R.string.route_here)
    val startLabel = stringResource(R.string.route_start)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var aimed by remember { mutableStateOf<String?>(null) }
    val view0 = rememberLifecycleMapView()

    fun aim(target: MapLibreMap, path: List<Pair<Double, Double>>) {
        // Before the first accurate fix joins the path, the latest fix alone still places the map.
        val clean = path.ifEmpty { latestHere?.takeIf { valid(it.lat, it.lon) }?.let { listOf(it.lat to it.lon) } ?: emptyList() }
        if (!following || clean.isEmpty()) return
        val tip = latestHere?.takeIf { valid(it.lat, it.lon) }?.let { it.lat to it.lon } ?: clean.last()
        val single = clean.distinct().size == 1
        val key = if (view == RouteView.CLOSE || single) "c:$tip" else "w:${clean.size}:${clean.first()}:${clean.last()}"
        if (key == aimed) return
        val update = if (view == RouteView.CLOSE || single) {
            CameraUpdateFactory.newLatLngZoom(LatLng(tip.first, tip.second), maxOf(CLOSE_ZOOM, if (aimed?.startsWith("c:") == true) target.cameraPosition.zoom else 0.0))
        } else {
            // A short walk would otherwise fit to a few metres a screen; never zoom closer than Close view.
            val bounds = LatLngBounds.Builder().includes((clean + tip).map { LatLng(it.first, it.second) }).build()
            val fit = target.getCameraForLatLngBounds(bounds, intArrayOf(FIT_PADDING_PX, FIT_PADDING_PX, FIT_PADDING_PX, FIT_PADDING_PX))
            if (fit?.target != null) CameraUpdateFactory.newLatLngZoom(fit.target!!, minOf(fit.zoom, CLOSE_ZOOM))
            else CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PX)
        }
        // The first frame jumps; later moves ease so the map does not snap on every fix.
        if (aimed == null) target.moveCamera(update) else target.animateCamera(update, EASE_MS)
        aimed = key
    }

    fun update(target: MapLibreMap) {
        val style = target.style ?: return
        val clean = latest.filter { valid(it.first, it.second) }
        val geometry = clean.map { Point.fromLngLat(it.second, it.first) }
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(
            if (geometry.size >= 2) arrayOf(Feature.fromGeometry(LineString.fromLngLats(geometry))) else emptyArray()))
        style.getSourceAs<GeoJsonSource>(START_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(
            if (geometry.size >= 2 && clean.distinct().size > 1) arrayOf(Feature.fromGeometry(geometry.first())) else emptyArray()))
        val now = latestHere?.takeIf { valid(it.lat, it.lon) }
        val tip = now?.let { Point.fromLngLat(it.lon, it.lat) } ?: geometry.lastOrNull()
        style.getSourceAs<GeoJsonSource>(TIP_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(tip?.let { arrayOf(Feature.fromGeometry(it)) } ?: emptyArray()))
        // The ring grows with zoom so it keeps its size on the ground.
        val ring = now?.accuracyM?.takeIf { it.isFinite() && it in 1f..MAX_RING_M }
        style.getSourceAs<GeoJsonSource>(RING_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(
            if (ring != null && tip != null) arrayOf(Feature.fromGeometry(tip)) else emptyArray()))
        if (ring != null && now != null) {
            val atZero = ring / (EQUATOR_M_PER_DP_Z0 * cos(now.lat * PI / 180.0))
            style.getLayerAs<CircleLayer>(RING_LAYER)?.setProperties(circleRadius(Expression.interpolate(
                Expression.exponential(2f), Expression.zoom(),
                Expression.stop(0, atZero), Expression.stop(MAX_ZOOM, atZero * 2.0.pow(MAX_ZOOM)),
            )))
        }
        style.getSourceAs<GeoJsonSource>(MARK_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(
            latestMarks.filter { valid(it.lat, it.lon) }.map { m ->
                Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
                    addStringProperty("label", m.label)
                    addBooleanProperty("attention", m.attention)
                }
            }.toTypedArray()))
        style.getSourceAs<GeoJsonSource>(CAMERA_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(
            latestCameras.filter { valid(it.lat, it.lon) }.map { cam ->
                Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).apply { addStringProperty("label", cam.label) }
            }.toTypedArray()))
        aim(target, clean)
    }

    fun measure(target: MapLibreMap) {
        // Meters across a short horizontal run at the centre of the view; independent of tile size and screen density.
        val w = view0.width.toFloat()
        val h = view0.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val run = w / 4f
        val a = target.projection.fromScreenLocation(android.graphics.PointF(w / 2f - run / 2f, h / 2f))
        val b = target.projection.fromScreenLocation(android.graphics.PointF(w / 2f + run / 2f, h / 2f))
        val m = a.distanceTo(b) / run
        if (!m.isFinite() || m <= 0.0) return
        metersPerPx = m
        if (background == MapBackground.NONE) target.style?.getSourceAs<GeoJsonSource>(GRID_SOURCE)?.setGeoJson(grid(target, niceMeters(m * scaleMaxPx(view0))))
    }

    LaunchedEffect(map, background, approximate) {
        map?.let { target ->
            aimed = null
            target.setStyle(mapStyle(background, c.ink)) {
                if (background == MapBackground.NONE) {
                    it.addSource(GeoJsonSource(GRID_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
                    it.addLayer(LineLayer(GRID_LAYER, GRID_SOURCE).withProperties(lineColor(c.lineSubtle.hex()), lineWidth(1f)))
                }
                it.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyArray()), GeoJsonOptions().withLineMetrics(true)))
                listOf(START_SOURCE, TIP_SOURCE, RING_SOURCE, MARK_SOURCE).forEach { id -> it.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyArray()))) }
                it.addSource(GeoJsonSource(CAMERA_SOURCE, FeatureCollection.fromFeatures(emptyArray()), GeoJsonOptions().withCluster(true).withClusterRadius(36).withClusterMaxZoom(16)))
                // Mapped cameras sit under the path and the heard-radio marks, in the Network colour.
                it.addLayer(CircleLayer(CAMERA_CLUSTER_LAYER, CAMERA_SOURCE).withFilter(Expression.has("point_count")).withProperties(
                    circleColor(c.reservedV11Network.hex()), circleOpacity(0.35f), circleRadius(11f), circleStrokeColor(c.reservedV11Network.hex()), circleStrokeWidth(1.5f),
                ))
                it.addLayer(CircleLayer(CAMERA_LAYER, CAMERA_SOURCE).withFilter(Expression.not(Expression.has("point_count"))).withProperties(
                    circleColor(c.ink.hex()), circleRadius(4f), circleStrokeColor(c.reservedV11Network.hex()), circleStrokeWidth(2f),
                ))
                it.addLayer(CircleLayer(RING_LAYER, RING_SOURCE).withProperties(
                    circleColor(c.accent.hex()), circleOpacity(0.12f), circleStrokeColor(c.accent.hex()), circleStrokeOpacity(0.5f), circleStrokeWidth(1f),
                    circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
                ))
                // A dark casing keeps the path readable over streets and grid alike.
                it.addLayer(LineLayer(CASING_LAYER, ROUTE_SOURCE).withProperties(
                    lineColor(c.ink.hex()), lineWidth(8f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ))
                it.addLayer(LineLayer(LINE_LAYER, ROUTE_SOURCE).withProperties(
                    lineWidth(4f), lineJoin(Property.LINE_JOIN_ROUND),
                    *if (approximate) arrayOf(lineColor(c.accent.hex()), lineDasharray(arrayOf(1.5f, 1.5f)))
                    else arrayOf(lineCap(Property.LINE_CAP_ROUND), lineGradient(Expression.interpolate(
                        Expression.linear(), Expression.lineProgress(),
                        Expression.stop(0f, Expression.color(c.accent.copy(alpha = FADED_ALPHA).toArgb())),
                        Expression.stop(1f, Expression.color(c.accent.toArgb())),
                    ))),
                ))
                it.addLayer(CircleLayer(START_LAYER, START_SOURCE).withProperties(
                    circleColor(c.ink.hex()), circleRadius(5f), circleStrokeColor(c.accent.hex()), circleStrokeWidth(2.5f),
                ))
                it.addLayer(CircleLayer(TIP_LAYER, TIP_SOURCE).withProperties(
                    circleColor(c.accent.hex()), circleRadius(7f), circleStrokeColor(c.text.hex()), circleStrokeWidth(2.5f),
                ))
                it.addLayer(CircleLayer(MARK_LAYER, MARK_SOURCE).withProperties(
                    circleColor(Expression.switchCase(Expression.get("attention"), Expression.color(c.attentionLine.toArgb()), Expression.color(c.sensed.toArgb()))),
                    circleRadius(5f), circleStrokeColor(c.ink.hex()), circleStrokeWidth(1.5f),
                ))
                update(target)
                measure(target)
            }
        }
    }
    Box(modifier) {
        AndroidView(factory = {
            view0.apply { getMapAsync { ready ->
                ready.uiSettings.isRotateGesturesEnabled = false
                ready.uiSettings.isCompassEnabled = false
                ready.uiSettings.isTiltGesturesEnabled = false
                ready.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false }
                ready.addOnCameraMoveListener { measure(ready) }
                ready.addOnCameraIdleListener { measure(ready) }
                ready.addOnMapClickListener { at ->
                    val point = ready.projection.toScreenLocation(at)
                    val heard = ready.queryRenderedFeatures(point, MARK_LAYER).firstOrNull()
                    val tip = if (heard == null) ready.queryRenderedFeatures(point, TIP_LAYER).firstOrNull() else null
                    val start = if (heard == null && tip == null) ready.queryRenderedFeatures(point, START_LAYER).firstOrNull() else null
                    val camera = if (heard == null && tip == null && start == null) ready.queryRenderedFeatures(point, CAMERA_LAYER).firstOrNull() else null
                    val cluster = if (heard == null && tip == null && start == null && camera == null) ready.queryRenderedFeatures(point, CAMERA_CLUSTER_LAYER).firstOrNull() else null
                    picked = heard?.getStringProperty("label")?.let { String.format(heardLabel, it) }
                        ?: tip?.let { hereLabel }
                        ?: start?.let { startLabel }
                        ?: camera?.getStringProperty("label")
                        ?: cluster?.let { String.format(clusterLabel, it.getNumberProperty("point_count")?.toInt() ?: 0) }
                    if (cluster != null) {
                        following = false
                        ready.animateCamera(CameraUpdateFactory.newLatLngZoom(at, ready.cameraPosition.zoom + 2))
                    }
                    picked != null
                }
                map = ready
            } }
        }, update = { map?.let(::update) }, modifier = Modifier.matchParentSize())
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(SotreusTheme.spacing.m),
            horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentedToggle(viewOptions, view, { picked = null; view = it; following = true; aimed = ""; map?.let(::update) },
                Modifier.widthIn(max = TOGGLE_MAX).background(c.ink.copy(alpha = OVERLAY_ALPHA), SotreusTheme.shapes.pill))
            if (!following) CompactButton(stringResource(R.string.route_recenter), { following = true; aimed = ""; map?.let(::update) })
        }
        metersPerPx?.let { ScaleBar(it, Modifier.align(Alignment.BottomEnd).padding(SotreusTheme.spacing.m)) }
        picked?.let { label ->
            StateChip(label, ChipTone.NEUTRAL, Modifier.align(Alignment.BottomStart).padding(start = SotreusTheme.spacing.m, end = SCALE_CLEARANCE, bottom = LOGO_CLEARANCE), small = false)
        }
    }
}

/** A plain scale: a bar and its length on the ground. It measures the map, not any radio. */
@Composable
private fun ScaleBar(metersPerPx: Double, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    val density = LocalDensity.current
    val maxPx = with(density) { SCALE_MAX.toPx() }
    val meters = niceMeters(metersPerPx * maxPx)
    val width = with(density) { (meters / metersPerPx).toFloat().toDp() }
    val label = if (meters >= 1_000) stringResource(R.string.route_scale_km, formatKm(meters)) else stringResource(R.string.route_scale_m, meters.roundToInt())
    Column(
        modifier.background(c.ink.copy(alpha = OVERLAY_ALPHA), SotreusTheme.shapes.pill).padding(horizontal = SotreusTheme.spacing.l, vertical = SotreusTheme.spacing.s),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xs),
    ) {
        // Not MonoLabel: its capitals would turn "m" into "M".
        Text(label, style = SotreusTheme.typography.monoLabelS, color = c.textSoft)
        Box(Modifier.width(width).height(SCALE_THICKNESS).background(c.text))
    }
}

private fun formatKm(meters: Double): String = if (meters % 1_000.0 == 0.0) (meters / 1_000).roundToInt().toString() else "%.1f".format(meters / 1_000)

/** Largest 1–2–5 step at or under [limit] meters. */
internal fun niceMeters(limit: Double): Double {
    if (!limit.isFinite() || limit <= 1.0) return 1.0
    val power = 10.0.pow(floor(kotlin.math.log10(limit)))
    val lead = limit / power
    return power * when { lead >= 5 -> 5.0; lead >= 2 -> 2.0; else -> 1.0 }
}

/** Offline grid: squares one scale-bar step wide, over three screens' worth of map so a pan does not show bare edges. */
private fun grid(target: MapLibreMap, stepM: Double): FeatureCollection {
    val box = target.projection.visibleRegion.latLngBounds
    val south = box.latitudeSouth; val north = box.latitudeNorth
    val west = box.longitudeWest; val east = box.longitudeEast
    val midLat = (south + north) / 2
    val cosLat = cos(midLat * PI / 180.0).coerceAtLeast(0.01)
    var latStep = stepM / M_PER_DEG
    var lonStep = stepM / (M_PER_DEG * cosLat)
    val spanLat = (north - south).coerceAtLeast(latStep)
    val spanLon = (east - west).coerceAtLeast(lonStep)
    while (3 * spanLat / latStep + 3 * spanLon / lonStep > MAX_GRID_LINES) { latStep *= 2; lonStep *= 2 }
    val s = (south - spanLat).coerceAtLeast(-85.0); val n = (north + spanLat).coerceAtMost(85.0)
    val w = (west - spanLon).coerceAtLeast(-180.0); val e = (east + spanLon).coerceAtMost(180.0)
    val lines = ArrayList<Feature>()
    var lat = floor(s / latStep) * latStep
    while (lat <= n) { lines += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(w, lat), Point.fromLngLat(e, lat)))); lat += latStep }
    var lon = floor(w / lonStep) * lonStep
    while (lon <= e) { lines += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(lon, s), Point.fromLngLat(lon, n)))); lon += lonStep }
    return FeatureCollection.fromFeatures(lines)
}

private fun scaleMaxPx(view: android.view.View): Double = SCALE_MAX.value * view.resources.displayMetrics.density.toDouble()

private fun valid(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

private const val ROUTE_SOURCE = "route"
private const val START_SOURCE = "start"
private const val TIP_SOURCE = "tip"
private const val RING_SOURCE = "ring"
private const val MARK_SOURCE = "marks"
private const val GRID_SOURCE = "grid"
private const val CAMERA_SOURCE = "cameras"
private const val GRID_LAYER = "grid-lines"
private const val CASING_LAYER = "route-casing"
private const val LINE_LAYER = "route-line"
private const val START_LAYER = "route-start"
private const val TIP_LAYER = "route-tip"
private const val RING_LAYER = "route-ring"
private const val MARK_LAYER = "route-marks"
private const val CAMERA_LAYER = "camera-points"
private const val CAMERA_CLUSTER_LAYER = "camera-clusters"

private const val CLOSE_ZOOM = 17.0
private const val FIT_PADDING_PX = 120
private const val EASE_MS = 600
private const val FADED_ALPHA = 0.35f
private const val OVERLAY_ALPHA = 0.8f
private const val MAX_RING_M = 500f
private const val MAX_ZOOM = 22
private const val MAX_GRID_LINES = 80
private const val M_PER_DEG = 111_320.0
private const val PREVIEW_M_PER_PX = 0.5

/** Ground meters per dp at zoom 0 on the equator, for MapLibre's 512 dp world. */
private const val EQUATOR_M_PER_DP_Z0 = 40_075_016.686 / 512.0

/** Keeps the picked-mark chip above the MapLibre logo and attribution button. */
private val LOGO_CLEARANCE = 44.dp
/** Keeps the picked-mark chip clear of the scale bar. */
private val SCALE_CLEARANCE = 120.dp
private val SCALE_MAX = 96.dp
private val SCALE_THICKNESS = 2.dp
private val TOGGLE_MAX = 240.dp
