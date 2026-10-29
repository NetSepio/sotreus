package app.sotreus.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.sotreus.core.designsystem.theme.SotreusTheme
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/** A north-up ARGB picture of where this phone heard one radio, and how loud, inside these bounds. */
@Suppress("ArrayInDataClass")
data class HeatImage(
    val pixels: IntArray,
    val cols: Int,
    val rows: Int,
    val north: Double,
    val west: Double,
    val south: Double,
    val east: Double,
)

/**
 * Signal map: this phone's walk colored by loudness, a ring on the loudest patch, and the phone's
 * dot with its uncertainty circle. Nothing on it marks where the other radio is. Street tiles load
 * only when [online] is on and the walk has been placed on a GPS fix ([onGlobe]); before that the
 * picture uses local coordinates and stays blank.
 */
@Composable
fun SignalHeatMap(
    heat: HeatImage?,
    hereLat: Double?,
    hereLon: Double?,
    uncertaintyM: Double,
    hotLat: Double?,
    hotLon: Double?,
    onGlobe: Boolean,
    online: Boolean,
    holdFlat: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = SotreusTheme.colors
    if (LocalInspectionMode.current) {
        // Previews cannot host a MapView; show the frame and legend only.
        Column(modifier, verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
            Box(Modifier.fillMaxWidth().weight(1f).background(c.ink, SotreusTheme.shapes.listCard))
            HeatLegend()
        }
        return
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var following by remember { mutableStateOf(true) }
    var styled by remember { mutableIntStateOf(0) }
    val view = rememberLifecycleMapView()
    val streets = online && onGlobe
    LaunchedEffect(map, streets) {
        map?.let { target ->
            target.setStyle(mapStyle(streets, c.ink)) {
                it.addSource(GeoJsonSource("uncertainty", FeatureCollection.fromFeatures(emptyArray())))
                it.addSource(GeoJsonSource("here", FeatureCollection.fromFeatures(emptyArray())))
                it.addSource(GeoJsonSource("hot", FeatureCollection.fromFeatures(emptyArray())))
                it.addLayer(FillLayer("uncertainty-fill", "uncertainty").withProperties(fillColor(c.text.hex()), fillOpacity(0.08f)))
                it.addLayer(LineLayer("uncertainty-line", "uncertainty").withProperties(lineColor(if (holdFlat) c.accent.hex() else c.textMuted.hex()), lineWidth(1f)))
                it.addLayer(CircleLayer("hot-ring", "hot").withProperties(circleRadius(14f), circleColor("rgba(0,0,0,0)"), circleStrokeColor(c.text.hex()), circleStrokeWidth(2f)))
                it.addLayer(CircleLayer("here-dot", "here").withProperties(circleRadius(6f), circleColor(c.text.hex()), circleStrokeColor(c.ink.hex()), circleStrokeWidth(2f)))
                styled++
            }
        }
    }
    LaunchedEffect(map, styled, heat, hereLat, hereLon, uncertaintyM, hotLat, hotLon, holdFlat) {
        val target = map ?: return@LaunchedEffect
        val style = target.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        style.getLayerAs<LineLayer>("uncertainty-line")?.setProperties(lineColor(if (holdFlat) c.accent.hex() else c.textMuted.hex()))
        if (heat != null) {
            val quad = LatLngQuad(LatLng(heat.north, heat.west), LatLng(heat.north, heat.east), LatLng(heat.south, heat.east), LatLng(heat.south, heat.west))
            val bitmap = Bitmap.createBitmap(heat.pixels, heat.cols, heat.rows, Bitmap.Config.ARGB_8888)
            val source = style.getSourceAs<ImageSource>("heat")
            if (source == null) {
                style.addSource(ImageSource("heat", quad, bitmap))
                style.addLayerBelow(RasterLayer("heat-layer", "heat").withProperties(rasterResampling("linear")), "uncertainty-fill")
            } else {
                source.setCoordinates(quad)
                source.setImage(bitmap)
            }
        }
        val here = if (hereLat != null && hereLon != null) Point.fromLngLat(hereLon, hereLat) else null
        style.getSourceAs<GeoJsonSource>("here")?.setGeoJson(FeatureCollection.fromFeatures(here?.let { arrayOf(Feature.fromGeometry(it)) } ?: emptyArray()))
        style.getSourceAs<GeoJsonSource>("uncertainty")?.setGeoJson(FeatureCollection.fromFeatures(
            if (hereLat != null && hereLon != null && uncertaintyM > 0) arrayOf(Feature.fromGeometry(circle(hereLat, hereLon, uncertaintyM))) else emptyArray()))
        style.getSourceAs<GeoJsonSource>("hot")?.setGeoJson(FeatureCollection.fromFeatures(
            if (hotLat != null && hotLon != null) arrayOf(Feature.fromGeometry(Point.fromLngLat(hotLon, hotLat))) else emptyArray()))
        if (following && hereLat != null && hereLon != null) {
            target.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(hereLat, hereLon), OPEN_ZOOM))
        } else if (following && heat != null) {
            // Paused map: frame the whole painted walk.
            target.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(heat.north, heat.east, heat.south, heat.west), 40))
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = {
                view.apply { getMapAsync { ready ->
                    ready.uiSettings.isRotateGesturesEnabled = false
                    ready.uiSettings.isCompassEnabled = false
                    ready.uiSettings.isTiltGesturesEnabled = false
                    ready.uiSettings.isAttributionEnabled = streets
                    ready.uiSettings.isLogoEnabled = false
                    ready.addOnCameraMoveStartedListener { reason -> if (reason == 1) following = false }
                    map = ready
                } }
            }, modifier = Modifier.matchParentSize())
            CompactButton(stringResource(R.string.route_recenter), { following = true; styled++ }, Modifier.align(Alignment.TopEnd).padding(SotreusTheme.spacing.m))
        }
        HeatLegend()
    }
}

/** Weak → strong ramp, the same colors the map paints. */
@Composable
fun HeatLegend(modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xs)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRoundRect(Brush.horizontalGradient(HEAT_RAMP), cornerRadius = CornerRadius(size.height / 2))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.heat_weak), style = SotreusTheme.typography.monoValue, color = c.textMuted, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.heat_strong), style = SotreusTheme.typography.monoValue, color = c.textMuted)
        }
    }
}

/** A [radiusM] circle around a point, as a polygon so it keeps its size in meters at every zoom. */
private fun circle(lat: Double, lon: Double, radiusM: Double): Polygon {
    val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.2)
    val ring = (0..48).map { i ->
        val a = 2 * Math.PI * i / 48
        Point.fromLngLat(lon + radiusM * sin(a) / (111_320.0 * cosLat), lat + radiusM * cos(a) / 110_540.0)
    }
    return Polygon.fromLngLats(listOf(ring))
}

/** Data colors from Fieldwatch HuntHeat (blue → cyan → green → yellow → orange → red); not theme colors. */
private val HEAT_RAMP = listOf(Color(0xFF1E4BFF), Color(0xFF00C8FF), Color(0xFF2DFF6A), Color(0xFFFFE033), Color(0xFFFF7A00), Color(0xFFFF1E1E))

/** About 12 m from the middle to the edge on a phone, the Fieldwatch Hunt opening view. */
private const val OPEN_ZOOM = 19.5
