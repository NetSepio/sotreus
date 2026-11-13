package app.sotreus.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.MapBackground
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos

/** A mesh node at the position it reported (NETWORK), with how far off that may be. */
data class MeshMapPin(val id: Long, val lat: Double, val lon: Double, val precisionM: Double?, val stale: Boolean, val selected: Boolean)

data class MeshMapWaypoint(val id: Long, val lat: Double, val lon: Double, val mine: Boolean)

/**
 * The Mesh map (mock M5, HANDOFF_V3_MESH.md §3.2): this phone, nodes at the positions they report,
 * drawn in the Network colour with a dashed ring for their precision (hollow when stale), and
 * waypoints. A dashed line joins this phone to the selected node, whose distance and bearing come
 * from those two positions only. Devices this phone or the Edge senses never appear here.
 */
@Composable
fun MeshMap(
    phoneLat: Double?,
    phoneLon: Double?,
    pins: List<MeshMapPin>,
    waypoints: List<MeshMapWaypoint>,
    background: MapBackground,
    contentDescription: String,
    onPinTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: ((Double, Double) -> Unit)? = null,
    recentreKey: Int = 0,
) {
    val c = SotreusTheme.colors
    if (LocalInspectionMode.current) {
        Box(modifier.background(c.surface, SotreusTheme.shapes.listCard).semantics { this.contentDescription = contentDescription })
        return
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styled by remember { mutableIntStateOf(0) }
    var framed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onPinTap)
    val press by rememberUpdatedState(onLongPress)
    val view = rememberLifecycleMapView()
    val net = c.reservedV11Network.hex()
    LaunchedEffect(map, background) {
        map?.let { target ->
            target.setStyle(mapStyle(background, c.ink)) {
                listOf("precision", "link", "nodes", "stale", "waypoints", "phone").forEach { id -> it.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyArray()))) }
                it.addLayer(FillLayer("precision-fill", "precision").withProperties(fillColor(net), fillOpacity(0.07f)))
                it.addLayer(LineLayer("precision-line", "precision").withProperties(lineColor(net), lineOpacity(0.4f), lineWidth(1f), lineDasharray(arrayOf(2f, 3f))))
                it.addLayer(LineLayer("link-line", "link").withProperties(lineColor(c.lineStrong.hex()), lineWidth(1.2f), lineDasharray(arrayOf(3f, 3f))))
                it.addLayer(CircleLayer("stale-dot", "stale").withProperties(circleRadius(6f), circleColor(c.ink.hex()), circleStrokeColor(net), circleStrokeWidth(1.5f)))
                it.addLayer(
                    CircleLayer("node-dot", "nodes").withProperties(
                        circleRadius(Expression.switchCase(Expression.get("selected"), Expression.literal(8f), Expression.literal(6.5f))),
                        circleColor(net),
                        circleStrokeColor(Expression.switchCase(Expression.get("selected"), Expression.literal(c.text.hex()), Expression.literal(c.ink.hex()))),
                        circleStrokeWidth(2f),
                    ),
                )
                it.addLayer(
                    CircleLayer("waypoint-dot", "waypoints").withProperties(
                        circleRadius(5f), circleColor(Expression.switchCase(Expression.get("mine"), Expression.literal(c.text.hex()), Expression.literal(c.ink.hex()))),
                        circleStrokeColor(net), circleStrokeWidth(1.5f),
                    ),
                )
                it.addLayer(CircleLayer("phone-dot", "phone").withProperties(circleRadius(7f), circleColor(c.text.hex()), circleStrokeColor(c.ink.hex()), circleStrokeWidth(2f)))
                styled++
            }
            target.addOnMapClickListener { point ->
                val screen = target.projection.toScreenLocation(point)
                val hit = target.queryRenderedFeatures(screen, "node-dot", "stale-dot").firstOrNull()?.getNumberProperty("id")?.toLong()
                if (hit != null) { tap(hit); true } else false
            }
            target.addOnMapLongClickListener { point -> press?.let { it(point.latitude, point.longitude); true } ?: false }
        }
    }
    LaunchedEffect(map, styled, phoneLat, phoneLon, pins, waypoints) {
        val target = map ?: return@LaunchedEffect
        val style = target.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        fun set(id: String, features: List<Feature>) = style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
        set("precision", pins.filter { !it.stale && (it.precisionM ?: 0.0) > 0 }.map { Feature.fromGeometry(ring(it.lat, it.lon, it.precisionM!!)) })
        set("nodes", pins.filter { !it.stale }.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addNumberProperty("id", p.id); addBooleanProperty("selected", p.selected) }
        })
        set("stale", pins.filter { it.stale }.map { p -> Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addNumberProperty("id", p.id) } })
        set("waypoints", waypoints.map { w -> Feature.fromGeometry(Point.fromLngLat(w.lon, w.lat)).apply { addBooleanProperty("mine", w.mine) } })
        set("phone", if (phoneLat != null && phoneLon != null) listOf(Feature.fromGeometry(Point.fromLngLat(phoneLon, phoneLat))) else emptyList())
        val selected = pins.firstOrNull { it.selected && !it.stale }
        set("link", if (selected != null && phoneLat != null && phoneLon != null)
            listOf(Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(phoneLon, phoneLat), Point.fromLngLat(selected.lon, selected.lat))))) else emptyList())
        if (!framed) {
            val pts = pins.filter { !it.stale }.map { LatLng(it.lat, it.lon) } + listOfNotNull(if (phoneLat != null && phoneLon != null) LatLng(phoneLat, phoneLon) else null)
            when {
                pts.size >= 2 -> { target.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(pts).build(), 60)); framed = true }
                pts.size == 1 -> { target.moveCamera(CameraUpdateFactory.newLatLngZoom(pts[0], 13.0)); framed = true }
            }
        }
    }
    LaunchedEffect(recentreKey) {
        if (recentreKey > 0 && phoneLat != null && phoneLon != null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(phoneLat, phoneLon), 14.0))
    }
    AndroidView(
        factory = { view.also { v -> v.getMapAsync { m -> m.uiSettings.isCompassEnabled = false; m.uiSettings.isRotateGesturesEnabled = false; map = m } } },
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    )
}

/** A circle of [radiusM] around a point, as a polygon. */
private fun ring(lat: Double, lon: Double, radiusM: Double): Polygon {
    val dLat = radiusM / 111_320.0
    val dLon = radiusM / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
    val pts = (0..48).map { i ->
        val a = 2 * Math.PI * i / 48
        Point.fromLngLat(lon + dLon * kotlin.math.sin(a), lat + dLat * cos(a))
    }
    return Polygon.fromLngLats(listOf(pts))
}
