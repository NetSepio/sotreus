package app.sotreus.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import app.sotreus.core.model.MapBackground
import app.sotreus.core.model.MapStyles

/** A MapView that follows the screen's lifecycle and is destroyed with the composable. */
@Composable
internal fun rememberLifecycleMapView(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }
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
    return view
}

/**
 * [MapBackground.NONE] is a blank local style with no network requests. [MapBackground.OFFLINE] reads the street
 * style and tiles only from the area downloaded on this phone: MapLibre's connection flag is switched off, so nothing
 * is requested even where the area ends. [MapBackground.ONLINE] loads tiles as the map moves.
 */
internal fun mapStyle(background: MapBackground, ink: Color): Style.Builder {
    MapLibre.setConnected(if (background == MapBackground.OFFLINE) false else null)
    return if (background == MapBackground.NONE) Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"${ink.hex()}"}}]}""")
    else Style.Builder().fromUri(MapStyles.STREETS)
}

internal fun Color.hex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

