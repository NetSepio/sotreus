package app.sotreus.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sotreus.core.designsystem.theme.SotreusTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** North-up phone path. Blank local style makes no network requests when online maps are off. */
@Composable
fun RouteMap(points: List<Pair<Double, Double>>, online: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(points)
    var following by remember { mutableStateOf(true) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val view = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }
    fun update(target: MapLibreMap) {
        val clean = latest.filter { it.first.isFinite() && it.second.isFinite() && it.first in -90.0..90.0 && it.second in -180.0..180.0 }
        val geometry = clean.map { Point.fromLngLat(it.second, it.first) }
        target.style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(FeatureCollection.fromFeatures(
            if (geometry.size >= 2) arrayOf(Feature.fromGeometry(LineString.fromLngLats(geometry))) else emptyArray()))
        target.style?.getSourceAs<GeoJsonSource>("ends")?.setGeoJson(FeatureCollection.fromFeatures(
            geometry.takeIf { it.isNotEmpty() }?.let { arrayOf(Feature.fromGeometry(it.first()), Feature.fromGeometry(it.last())) } ?: emptyArray()))
        if (following && clean.isNotEmpty()) {
            if (clean.distinct().size == 1) target.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(clean[0].first, clean[0].second), 17.0))
            else target.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(clean.map { LatLng(it.first, it.second) }).build(), 70))
        }
    }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> view.onStart()
            Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause()
            Lifecycle.Event.ON_STOP -> view.onStop()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose { lifecycle.removeObserver(observer); view.onPause(); view.onStop(); view.onDestroy() }
    }
    LaunchedEffect(map, online) {
        map?.let { target ->
            val style = if (online) Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark") else
                Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#0B0E13"}}]}""")
            target.setStyle(style) {
                it.addSource(GeoJsonSource("route", FeatureCollection.fromFeatures(emptyArray())))
                it.addSource(GeoJsonSource("ends", FeatureCollection.fromFeatures(emptyArray())))
                it.addLayer(LineLayer("route-line", "route").withProperties(lineColor("#F4B332"), lineWidth(4f)))
                it.addLayer(CircleLayer("route-ends", "ends").withProperties(circleColor("#F4B332"), circleRadius(6f), circleStrokeColor("#E7E5DE"), circleStrokeWidth(2f)))
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
                map = ready
            } }
        }, update = { map?.let(::update) }, modifier = Modifier.matchParentSize())
        CompactButton(stringResource(R.string.route_recenter), { following = true; map?.let(::update) }, Modifier.align(Alignment.TopEnd).padding(SotreusTheme.spacing.m))
    }
}
