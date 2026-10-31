package app.sotreus.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import app.sotreus.core.designsystem.theme.SotreusTheme
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Where this phone was when it heard a radio during the session. The mark is on this phone's path,
 * never on the other radio.
 */
data class RouteMark(val lat: Double, val lon: Double, val label: String, val attention: Boolean = false)

/** A camera from a downloaded list (OpenStreetMap data). Mapped by people, not sensed by this phone. */
data class RouteCamera(val lat: Double, val lon: Double, val label: String)

/** North-up phone path with heard-radio marks. Blank local style makes no network requests when online maps are off. */
@Composable
fun RouteMap(
    points: List<Pair<Double, Double>>,
    online: Boolean,
    modifier: Modifier = Modifier,
    marks: List<RouteMark> = emptyList(),
    cameras: List<RouteCamera> = emptyList(),
) {
    val c = SotreusTheme.colors
    val latest by rememberUpdatedState(points)
    val latestMarks by rememberUpdatedState(marks)
    val latestCameras by rememberUpdatedState(cameras)
    val clusterLabel = stringResource(R.string.route_camera_cluster)
    val heardLabel = stringResource(R.string.route_mark_heard)
    var following by remember { mutableStateOf(true) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    val view = rememberLifecycleMapView()
    fun update(target: MapLibreMap) {
        val clean = latest.filter { valid(it.first, it.second) }
        val geometry = clean.map { Point.fromLngLat(it.second, it.first) }
        target.style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(FeatureCollection.fromFeatures(
            if (geometry.size >= 2) arrayOf(Feature.fromGeometry(LineString.fromLngLats(geometry))) else emptyArray()))
        target.style?.getSourceAs<GeoJsonSource>("ends")?.setGeoJson(FeatureCollection.fromFeatures(
            geometry.takeIf { it.isNotEmpty() }?.let { arrayOf(Feature.fromGeometry(it.first()), Feature.fromGeometry(it.last())) } ?: emptyArray()))
        target.style?.getSourceAs<GeoJsonSource>("marks")?.setGeoJson(FeatureCollection.fromFeatures(
            latestMarks.filter { valid(it.lat, it.lon) }.map { m ->
                Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
                    addStringProperty("label", m.label)
                    addBooleanProperty("attention", m.attention)
                }
            }.toTypedArray()))
        target.style?.getSourceAs<GeoJsonSource>(CAMERA_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(
            latestCameras.filter { valid(it.lat, it.lon) }.map { cam ->
                Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).apply { addStringProperty("label", cam.label) }
            }.toTypedArray()))
        if (following && clean.isNotEmpty()) {
            if (clean.distinct().size == 1) target.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(clean[0].first, clean[0].second), 17.0))
            else target.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(clean.map { LatLng(it.first, it.second) }).build(), 70))
        }
    }
    LaunchedEffect(map, online) {
        map?.let { target ->
            target.setStyle(mapStyle(online, c.ink)) {
                it.addSource(GeoJsonSource("route", FeatureCollection.fromFeatures(emptyArray())))
                it.addSource(GeoJsonSource("ends", FeatureCollection.fromFeatures(emptyArray())))
                it.addSource(GeoJsonSource("marks", FeatureCollection.fromFeatures(emptyArray())))
                it.addSource(GeoJsonSource(CAMERA_SOURCE, FeatureCollection.fromFeatures(emptyArray()), GeoJsonOptions().withCluster(true).withClusterRadius(36).withClusterMaxZoom(16)))
                // Mapped cameras sit under the path and the heard-radio marks, in the Network colour.
                it.addLayer(CircleLayer(CAMERA_CLUSTER_LAYER, CAMERA_SOURCE).withFilter(Expression.has("point_count")).withProperties(
                    circleColor(c.reservedV11Network.hex()), circleOpacity(0.35f), circleRadius(11f), circleStrokeColor(c.reservedV11Network.hex()), circleStrokeWidth(1.5f),
                ))
                it.addLayer(CircleLayer(CAMERA_LAYER, CAMERA_SOURCE).withFilter(Expression.not(Expression.has("point_count"))).withProperties(
                    circleColor(c.ink.hex()), circleRadius(4f), circleStrokeColor(c.reservedV11Network.hex()), circleStrokeWidth(2f),
                ))
                it.addLayer(LineLayer("route-line", "route").withProperties(lineColor(c.accent.hex()), lineWidth(4f)))
                it.addLayer(CircleLayer("route-ends", "ends").withProperties(circleColor(c.accent.hex()), circleRadius(6f), circleStrokeColor(c.text.hex()), circleStrokeWidth(2f)))
                it.addLayer(CircleLayer(MARK_LAYER, "marks").withProperties(
                    circleColor(Expression.switchCase(Expression.get("attention"), Expression.color(c.attentionLine.toArgb()), Expression.color(c.sensed.toArgb()))),
                    circleRadius(5f), circleStrokeColor(c.ink.hex()), circleStrokeWidth(1.5f),
                ))
                update(target)
            }
        }
    }
    Box(modifier) {
        AndroidView(factory = {
            view.apply { getMapAsync { ready ->
                ready.uiSettings.isRotateGesturesEnabled = false
                ready.uiSettings.isCompassEnabled = false
                ready.uiSettings.isTiltGesturesEnabled = false
                ready.addOnCameraMoveStartedListener { reason -> if (reason == 1) following = false }
                ready.addOnMapClickListener { at ->
                    val point = ready.projection.toScreenLocation(at)
                    val heard = ready.queryRenderedFeatures(point, MARK_LAYER).firstOrNull()
                    val camera = if (heard == null) ready.queryRenderedFeatures(point, CAMERA_LAYER).firstOrNull() else null
                    val cluster = if (heard == null && camera == null) ready.queryRenderedFeatures(point, CAMERA_CLUSTER_LAYER).firstOrNull() else null
                    picked = heard?.getStringProperty("label")?.let { String.format(heardLabel, it) }
                        ?: camera?.getStringProperty("label")
                        ?: cluster?.let { String.format(clusterLabel, it.getNumberProperty("point_count")?.toInt() ?: 0) }
                    if (cluster != null) {
                        following = false
                        ready.animateCamera(CameraUpdateFactory.newLatLngZoom(at, ready.cameraPosition.zoom + 2))
                    }
                    heard != null || camera != null || cluster != null
                }
                map = ready
            } }
        }, update = { map?.let(::update) }, modifier = Modifier.matchParentSize())
        CompactButton(stringResource(R.string.route_recenter), { following = true; map?.let(::update) }, Modifier.align(Alignment.TopEnd).padding(SotreusTheme.spacing.m))
        picked?.let { label ->
            StateChip(label, ChipTone.NEUTRAL, Modifier.align(Alignment.BottomStart).padding(start = SotreusTheme.spacing.m, end = SotreusTheme.spacing.m, bottom = LOGO_CLEARANCE), small = false)
        }
    }
}

private fun valid(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

private const val MARK_LAYER = "route-marks"
private const val CAMERA_SOURCE = "cameras"
private const val CAMERA_LAYER = "camera-points"
private const val CAMERA_CLUSTER_LAYER = "camera-clusters"

/** Keeps the picked-mark chip above the MapLibre logo and attribution button. */
private val LOGO_CLEARANCE = 44.dp
