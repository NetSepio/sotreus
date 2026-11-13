package app.sotreus.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sotreus.core.designsystem.theme.SotreusTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * A MapLibre map with a fixed centre pin (places, a stationary Edge). With [onCenterChanged] the map is draggable and reports
 * the point under the pin; otherwise it is a static preview. Rotation and the compass are off: the
 * app never shows bearings.
 */
@Composable
fun PinMap(
    lat: Double,
    lon: Double,
    zoom: Double,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onCenterChanged: ((Double, Double) -> Unit)? = null,
    onReady: (MapLibreMap) -> Unit = {},
    /** Where tiles come from: the owner's Field tools setting, or ONLINE when they asked for it on this screen. */
    background: app.sotreus.core.model.MapBackground = app.sotreus.core.model.MapBackground.ONLINE,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    val c = SotreusTheme.colors
    Box(modifier.clip(SotreusTheme.shapes.panel).border(1.dp, c.line, SotreusTheme.shapes.panel).semantics { this.contentDescription = contentDescription }) {
        AndroidView(
            factory = {
                mapView.getMapAsync { map ->
                    map.setStyle(mapStyle(background, c.ink))
                    map.uiSettings.apply {
                        isRotateGesturesEnabled = false
                        isCompassEnabled = false
                        isTiltGesturesEnabled = false
                        setLogoEnabled(false)
                        val interactive = onCenterChanged != null
                        isScrollGesturesEnabled = interactive
                        isZoomGesturesEnabled = interactive
                        isDoubleTapGesturesEnabled = interactive
                    }
                    map.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).build()
                    onCenterChanged?.let { report ->
                        map.addOnCameraIdleListener { map.cameraPosition.target?.let { t -> report(t.latitude, t.longitude) } }
                    }
                    onReady(map)
                }
                mapView
            },
            modifier = Modifier.fillMaxSize(),
        )
        // The pin: the shared "needs a look / sensed" amber dot with a soft halo.
        Box(Modifier.align(Alignment.Center).size(26.dp).background(c.accent.copy(alpha = 0.22f), SotreusTheme.shapes.pill))
        Box(Modifier.align(Alignment.Center).size(12.dp).background(c.accent, SotreusTheme.shapes.pill).border(2.dp, c.ink, SotreusTheme.shapes.pill))
    }
}

/**
 * Shown in place of a map picker while street maps are off (Field tools › Map background: None):
 * nothing is loaded from the internet unless the owner asks for this screen. The picker's offline
 * ways to set the point (this phone's location, typed coordinates) sit below it.
 */
@Composable
fun MapOffPanel(onLoadOnline: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(SotreusTheme.spacing.m)) {
        CaveatBox(androidx.compose.ui.res.stringResource(R.string.map_off_kicker), androidx.compose.ui.res.stringResource(R.string.map_off_body))
        GhostButton(androidx.compose.ui.res.stringResource(R.string.map_off_load), onLoadOnline)
    }
}

/** Under a map that reads only the downloaded street area (Map background: Offline). */
@Composable
fun MapOfflineAreaNote(modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        androidx.compose.ui.res.stringResource(R.string.map_offline_area_note),
        style = SotreusTheme.typography.caption, color = SotreusTheme.colors.textDim, modifier = modifier,
    )
}

@androidx.compose.ui.tooling.preview.Preview(widthDp = 390, heightDp = 260, showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun MapOffPanelPreview() {
    SotreusTheme { MapOffPanel({}) }
}
