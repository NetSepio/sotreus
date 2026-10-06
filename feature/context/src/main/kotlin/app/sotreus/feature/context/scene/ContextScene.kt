package app.sotreus.feature.context.scene

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.sotreus.context.AircraftState
import app.sotreus.context.ContextLocation
import app.sotreus.context.SatelliteState
import app.sotreus.context.remoteid.LiveRemoteId
import app.sotreus.context.satellite.OrbitalElements
import app.sotreus.context.satellite.SatellitePredictor
import app.sotreus.core.database.entity.ContextEventEntity
import app.sotreus.core.designsystem.theme.SotreusColors
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.navigation.AircraftRoute
import app.sotreus.core.navigation.RemoteIdRoute
import app.sotreus.core.navigation.SatelliteRoute
import app.sotreus.core.ui.InlineLink
import app.sotreus.core.ui.SelectChip
import app.sotreus.feature.context.R
import app.sotreus.feature.context.altitude
import app.sotreus.feature.context.compass
import app.sotreus.feature.context.km
import app.sotreus.feature.context.speed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class SceneMode { GLOBE, AIRCRAFT, REMOTE_ID }

private const val MAX_DRONE_RADIUS_KM = 15.0

private data class Selection(val mode: SceneMode, val id: String)

private class Hit(val x: Float, val y: Float, val selection: Selection)

/** Satellite sub-points sampled at two instants; frames interpolate between them. */
private class SatSamples(val elements: List<OrbitalElements>, val t0: Long, val t1: Long, val a: DoubleArray, val b: DoubleArray)

/**
 * The Context scene: one Earth-fixed 3D world drawn on a Canvas. Satellites show on a dot-matrix
 * globe with the day/night terminator; Aircraft and Remote ID fly the camera down to a tilted plate
 * around the user. Drag to turn, pinch to zoom, double-tap to reset, tap an object to select it.
 * Everything is drawn from data already on the phone: no map tiles are requested.
 */
@Composable
internal fun ContextScene(
    mode: SceneMode,
    location: ContextLocation?,
    satellites: SatelliteState.Ready?,
    aircraft: AircraftState.Ready?,
    aircraftRadiusKm: Int,
    drones: List<LiveRemoteId>,
    droneHistory: List<ContextEventEntity>,
    navigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = SotreusTheme.colors
    val globe by produceState<GlobeData?>(null) { value = withContext(Dispatchers.IO) { runCatching { GlobeData.load(context) }.getOrNull() } }

    // Clocks: real time for frames; scene time can run as a time-lapse on the globe.
    var frameMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var warp by remember { mutableStateOf(1) }
    var anchorReal by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var anchorScene by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun sceneTime(real: Long) = anchorScene + (real - anchorReal) * warp
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameMillis { frameMs = System.currentTimeMillis() }
            delay(24)
        }
    }

    // Camera controls.
    var yaw by remember { mutableDoubleStateOf(0.0) }
    var pitch by remember { mutableDoubleStateOf(0.0) }
    var globeZoom by remember { mutableDoubleStateOf(1.0) }
    var heading by remember { mutableDoubleStateOf(0.0) }
    var tilt by remember { mutableDoubleStateOf(56.0) }
    var plateZoom by remember { mutableDoubleStateOf(1.0) }
    val blend = remember { Animatable(if (mode == SceneMode.GLOBE) 0f else 1f) }
    LaunchedEffect(mode) {
        plateZoom = 1.0
        blend.animateTo(if (mode == SceneMode.GLOBE) 0f else 1f, tween(1700, easing = FastOutSlowInEasing))
    }
    if (mode != SceneMode.GLOBE && warp != 1) {
        anchorScene = sceneTime(System.currentTimeMillis()); anchorReal = System.currentTimeMillis(); warp = 1
    }
    val satAlpha by animateFloatAsState(if (mode == SceneMode.GLOBE) 1f else 0f, tween(900), label = "sat")
    val airAlpha by animateFloatAsState(if (mode == SceneMode.AIRCRAFT) 1f else 0f, tween(900, delayMillis = if (mode == SceneMode.AIRCRAFT) 700 else 0), label = "air")
    val droneAlpha by animateFloatAsState(if (mode == SceneMode.REMOTE_ID) 1f else 0f, tween(900, delayMillis = if (mode == SceneMode.REMOTE_ID) 700 else 0), label = "drone")

    val user = location?.point
    // Fit the plate to broadcasts within reach of a phone radio; farther ones are counted, not drawn.
    val droneDistances = remember(drones, user) {
        if (user == null) emptyList() else drones.flatMap { d ->
            listOfNotNull(d.lat?.let { la -> d.lon?.let { lo -> la to lo } }, d.operatorLat?.let { la -> d.operatorLon?.let { lo -> la to lo } })
        }.map { (la, lo) -> app.sotreus.context.Geo.distanceKm(user, app.sotreus.context.GeoPoint(la, lo)) }
    }
    val droneRadiusKm = ((droneDistances.filter { it <= MAX_DRONE_RADIUS_KM }.maxOrNull() ?: 0.0) * 1.5).coerceIn(0.6, MAX_DRONE_RADIUS_KM)
    val dronesOutside = drones.count { d ->
        val la = d.lat
        val lo = d.lon
        user != null && la != null && lo != null && app.sotreus.context.Geo.distanceKm(user, app.sotreus.context.GeoPoint(la, lo)) > droneRadiusKm
    }
    val baseRadius by animateFloatAsState(
        when (mode) {
            SceneMode.REMOTE_ID -> droneRadiusKm.toFloat()
            else -> aircraftRadiusKm.toFloat()
        },
        tween(1400, easing = FastOutSlowInEasing), label = "radius",
    )

    // Satellite sampling off the main thread.
    val elements = satellites?.elements.orEmpty()
    val samples by produceState<SatSamples?>(null, elements, warp, anchorScene) {
        if (elements.isEmpty()) return@produceState
        while (isActive) {
            val now = System.currentTimeMillis()
            val t0 = sceneTime(now)
            val span = if (warp == 1) 10_000L else 2_000L * warp
            value = withContext(Dispatchers.Default) {
                val times = longArrayOf(t0, t0 + span)
                val a = DoubleArray(elements.size * 3)
                val b = DoubleArray(elements.size * 3)
                elements.forEachIndexed { i, e ->
                    val tr = SatellitePredictor.track(e, times)
                    System.arraycopy(tr, 0, a, i * 3, 3)
                    System.arraycopy(tr, 3, b, i * 3, 3)
                }
                SatSamples(elements, t0, t0 + span, a, b)
            }
            delay(if (warp == 1) 4_000 else 900)
        }
    }

    var selection by remember { mutableStateOf<Selection?>(null) }
    LaunchedEffect(mode) { if (selection?.mode != mode) selection = null }
    val selectedSat = selection?.takeIf { it.mode == SceneMode.GLOBE }?.id?.toIntOrNull()?.let { id -> elements.firstOrNull { it.noradId == id } }
    val orbit by produceState<DoubleArray?>(null, selectedSat, warp, anchorScene) {
        val e = selectedSat ?: run { value = null; return@produceState }
        while (isActive) {
            val periodMs = (86_400_000.0 / e.meanMotion.coerceAtLeast(0.5)).toLong()
            val now = sceneTime(System.currentTimeMillis())
            val n = 160
            val times = LongArray(n) { i -> now - periodMs / 2 + periodMs * i / (n - 1) }
            value = withContext(Dispatchers.Default) { SatellitePredictor.track(e, times) }
            delay(if (warp == 1) 60_000 else 5_000)
        }
    }

    // Aircraft trails from successive reports.
    val trails = remember { HashMap<String, ArrayDeque<DoubleArray>>() }
    LaunchedEffect(aircraft?.fetchedAtMs) {
        aircraft?.aircraft?.forEach { na ->
            val r = na.report
            if (r.lat != null && r.lon != null) {
                val q = trails.getOrPut(r.icao24) { ArrayDeque() }
                if (q.lastOrNull()?.let { it[0] != r.lat || it[1] != r.lon } != false) q.addLast(doubleArrayOf(r.lat!!, r.lon!!, r.altitudeM ?: 0.0))
                while (q.size > 12) q.removeFirst()
            }
        }
    }

    val hits = remember { ArrayList<Hit>() }
    val measurer = rememberTextMeasurer()
    val labelStyle = SotreusTheme.typography.monoLabelS
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val stars = remember { List(140) { i -> Triple(((i * 7919) % 997) / 997f, ((i * 104729) % 991) / 991f, (i % 5) / 4f) } }
    val a11y = stringResource(
        R.string.ctx_scene_a11y,
        when (mode) {
            SceneMode.GLOBE -> stringResource(R.string.ctx_scene_a11y_globe, elements.size)
            SceneMode.AIRCRAFT -> stringResource(R.string.ctx_scene_a11y_aircraft, aircraft?.aircraft?.size ?: 0, aircraftRadiusKm)
            SceneMode.REMOTE_ID -> stringResource(R.string.ctx_scene_a11y_rid, drones.size)
        },
    )

    Box(
        modifier.fillMaxWidth().height(360.dp).clip(SotreusTheme.shapes.panel).border(1.dp, c.line, SotreusTheme.shapes.panel),
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = a11y }
                .pointerInput(mode) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (mode == SceneMode.GLOBE) {
                            yaw -= pan.x * 0.22 / globeZoom
                            pitch = (pitch + pan.y * 0.22 / globeZoom).coerceIn(-55.0, 60.0)
                            globeZoom = (globeZoom * zoom).coerceIn(0.75, 3.2)
                        } else {
                            heading -= pan.x * 0.3
                            tilt = (tilt - pan.y * 0.15).coerceIn(18.0, 82.0)
                            plateZoom = (plateZoom / zoom).coerceIn(0.3, 3.0)
                        }
                    }
                }
                .pointerInput(mode) {
                    detectTapGestures(
                        onDoubleTap = {
                            yaw = 0.0; pitch = 0.0; globeZoom = 1.0; heading = 0.0; tilt = 56.0; plateZoom = 1.0
                        },
                        onTap = { o ->
                            val best = hits.minByOrNull { hypot((it.x - o.x).toDouble(), (it.y - o.y).toDouble()) }
                            selection = best?.takeIf { hypot((it.x - o.x).toDouble(), (it.y - o.y).toDouble()) < 28 * density }?.selection
                        },
                    )
                },
        ) {
            val real = frameMs
            val now = sceneTime(real)
            hits.clear()
            placedLabels.clear()
            val b = blend.value.toDouble()
            drawStars(stars, c, (1 - smoothstep(0.0, 0.5, b)).toFloat())
            if (user == null) return@Canvas
            val cam = Camera.build(
                b, user.lat, user.lon, yaw, pitch, (3.9 / globeZoom).coerceAtLeast(1.32), heading, tilt,
                baseRadius * plateZoom, size.width.toDouble(), size.height.toDouble(),
            )
            val globeA = (1 - smoothstep(0.55, 0.92, b)).toFloat()
            val out = FloatArray(3)
            drawGlobe(cam, globe, sunDirection(now), c, globeA, density, out)
            val plateA = smoothstep(0.4, 0.9, b).toFloat()
            drawCoast(cam, globe, user.lat, user.lon, baseRadius * plateZoom, c, smoothstep(0.25, 0.8, b).toFloat(), density, out)
            drawPlate(cam, user.lat, user.lon, baseRadius * plateZoom, c, plateA, density, measurer, labelStyle, out)
            if (satAlpha > 0.01f) {
                samples?.let { s -> drawSatellites(cam, s, now, user.lat, user.lon, selectedSat, orbit, c, satAlpha, density, measurer, labelStyle, hits, out) }
            }
            if (airAlpha > 0.01f && aircraft != null) drawAircraft(cam, aircraft, trails, real, selection, c, airAlpha, density, measurer, labelStyle, hits, out)
            if (droneAlpha > 0.01f) drawDrones(cam, drones, droneHistory, selection, c, droneAlpha, density, measurer, labelStyle, hits, out)
            drawUser(cam, user.lat, user.lon, real, c, density, out)
        }

        // Header: provenance and scale for the current view.
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Text(
                when (mode) {
                    SceneMode.GLOBE -> stringResource(if (warp == 1) R.string.ctx_scene_globe_live else R.string.ctx_scene_globe_warp, warp)
                    SceneMode.AIRCRAFT -> stringResource(R.string.ctx_scene_aircraft_note, (baseRadius * plateZoom).toInt().coerceAtLeast(1))
                    SceneMode.REMOTE_ID -> stringResource(R.string.ctx_scene_rid_note, km(baseRadius * plateZoom)) +
                        (if (dronesOutside > 0) " · " + pluralStringResource(R.plurals.ctx_scene_rid_outside, dronesOutside, dronesOutside) else "")
                }.uppercase(),
                style = labelStyle, color = c.textMuted, modifier = Modifier.weight(1f),
            )
        }
        if (mode == SceneMode.GLOBE) {
            Row(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 60, 600).forEach { w ->
                    SelectChip(if (w == 1) stringResource(R.string.ctx_scene_live) else "×$w", warp == w, {
                        val t = System.currentTimeMillis()
                        anchorScene = if (w == 1) t else sceneTime(t)
                        anchorReal = t
                        warp = w
                    })
                }
            }
        }
        if (user == null) {
            Text(stringResource(R.string.ctx_scene_no_location), style = SotreusTheme.typography.caption, color = c.textMuted, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
        Text(stringResource(R.string.ctx_scene_hint).uppercase(), style = labelStyle, color = c.textDim, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
        selection?.let { sel ->
            SelectionCard(sel, elements, aircraft, drones, navigate, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = if (mode == SceneMode.GLOBE) 58.dp else 10.dp))
        }
    }
}

@Composable
private fun SelectionCard(sel: Selection, elements: List<OrbitalElements>, aircraft: AircraftState.Ready?, drones: List<LiveRemoteId>, navigate: (Any) -> Unit, modifier: Modifier) {
    val c = SotreusTheme.colors
    val (title, subtitle, route) = when (sel.mode) {
        SceneMode.GLOBE -> elements.firstOrNull { it.noradId.toString() == sel.id }?.let {
            Triple(it.name, stringResource(R.string.ctx_scene_sat_card, it.noradId), SatelliteRoute(it.noradId))
        }
        SceneMode.AIRCRAFT -> aircraft?.aircraft?.firstOrNull { it.report.icao24 == sel.id }?.let { na ->
            val r = na.report
            Triple(
                r.callsign ?: r.icao24.uppercase(),
                listOfNotNull(altitude(r.altitudeM), speed(r.speedMps), r.courseDeg?.let { compass(it) }, km(na.distanceKm)).joinToString(" · "),
                AircraftRoute(r.icao24),
            )
        }
        SceneMode.REMOTE_ID -> drones.firstOrNull { it.subjectId == sel.id }?.let { d ->
            Triple(d.maker ?: d.selfId ?: d.uasId ?: d.subjectId, listOfNotNull(altitude(d.altM), speed(d.speedMps)).joinToString(" · "), RemoteIdRoute(d.subjectId))
        }
    } ?: return
    Column(
        modifier.clip(SotreusTheme.shapes.panel).background(c.surfaceRaised.copy(alpha = 0.92f))
            .border(1.dp, c.lineMid, SotreusTheme.shapes.panel).padding(start = 12.dp, end = 4.dp, top = 8.dp),
    ) {
        Text(title, style = SotreusTheme.typography.rowTitle, color = c.text, maxLines = 1)
        if (subtitle.isNotEmpty()) Text(subtitle, style = SotreusTheme.typography.caption, color = c.textMuted, maxLines = 1)
        InlineLink(stringResource(R.string.ctx_scene_details), { navigate(route) })
    }
}

// ---- Layers ----

private fun DrawScope.drawStars(stars: List<Triple<Float, Float, Float>>, c: SotreusColors, alpha: Float) {
    if (alpha <= 0.01f) return
    stars.forEach { (x, y, m) -> drawCircle(c.textSoft.copy(alpha = alpha * (0.15f + 0.35f * m)), radius = 0.6f + m * 0.9f, center = Offset(x * size.width, y * size.height)) }
}

private val dotPaint = Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
private val linePaint = Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }

private fun DrawScope.drawGlobe(cam: Camera, globe: GlobeData?, sun: V3, c: SotreusColors, alpha: Float, density: Float, out: FloatArray) {
    if (alpha <= 0.01f) return
    // Disc and atmosphere: the limb is the circle where sight lines graze the sphere.
    val cl = cam.pos.length
    val ch = cam.pos.unit()
    val a = (ch cross V3(0.0, 0.0, 1.0)).let { if (it.length < 1e-6) V3(1.0, 0.0, 0.0) else it.unit() }
    val bb = ch cross a
    val centre = ch * (1 / cl)
    val rr = kotlin.math.sqrt(1 - 1 / (cl * cl))
    val limb = Path()
    var sx = 0f
    var sy = 0f
    var n = 0
    for (i in 0..96) {
        val t = i / 96.0 * 2 * PI
        val p = centre + (a * cos(t) + bb * sin(t)) * rr
        if (!cam.project(p, out)) continue
        if (n == 0) limb.moveTo(out[0], out[1]) else limb.lineTo(out[0], out[1])
        sx += out[0]; sy += out[1]; n++
    }
    if (n > 3) {
        limb.close()
        val cx = sx / n
        val cy = sy / n
        cam.project(centre + a * rr, out)
        val radius = hypot((out[0] - cx).toDouble(), (out[1] - cy).toDouble()).toFloat()
        drawCircle(
            Brush.radialGradient(
                0.80f to Color.Transparent, 0.90f to c.accent.copy(alpha = 0.10f * alpha), 1f to Color.Transparent,
                center = Offset(cx, cy), radius = radius * 1.18f,
            ),
            radius = radius * 1.18f, center = Offset(cx, cy),
        )
        drawPath(limb, c.surface.copy(alpha = 0.92f * alpha))
        drawPath(limb, c.lineMid.copy(alpha = alpha), style = Stroke(1f * density))
    }
    // Graticule every 30°.
    val grat = Path()
    for (lat in -60..60 step 30) graticule(cam, grat, out) { t -> ecef(lat.toDouble(), t * 360.0 - 180) }
    for (lon in -180 until 180 step 30) graticule(cam, grat, out) { t -> ecef(t * 170.0 - 85, lon.toDouble()) }
    drawPath(grat, c.lineSubtle.copy(alpha = 0.9f * alpha), style = Stroke(0.8f * density))
    // Land as dots, dimmer on the night side.
    val land = globe?.land ?: return
    val day = FloatArray(land.size / 3 * 2)
    val night = FloatArray(land.size / 3 * 2)
    var nd = 0
    var nn = 0
    var i = 0
    while (i < land.size) {
        val x = land[i]; val y = land[i + 1]; val z = land[i + 2]
        i += 3
        if (!cam.facing(x, y, z) || !cam.project(x, y, z, out)) continue
        if (x * sun.x + y * sun.y + z * sun.z > -0.05) { day[nd++] = out[0]; day[nd++] = out[1] } else { night[nn++] = out[0]; night[nn++] = out[1] }
    }
    drawIntoCanvas { canvas ->
        dotPaint.strokeWidth = 2.3f * density
        dotPaint.color = c.textSoft.copy(alpha = 0.78f * alpha).toArgb()
        canvas.nativeCanvas.drawPoints(day, 0, nd, dotPaint)
        dotPaint.color = c.textDim.copy(alpha = 0.55f * alpha).toArgb()
        canvas.nativeCanvas.drawPoints(night, 0, nn, dotPaint)
    }
}

private inline fun graticule(cam: Camera, path: Path, out: FloatArray, point: (Double) -> V3) {
    var drawing = false
    for (k in 0..72) {
        val p = point(k / 72.0)
        val ok = cam.facing(p.x, p.y, p.z) && cam.project(p, out)
        if (ok && k % 2 == 0) {
            if (!drawing) path.moveTo(out[0], out[1]) else path.lineTo(out[0], out[1])
            drawing = true
        } else if (ok) {
            if (drawing) path.lineTo(out[0], out[1])
            drawing = false
        } else {
            drawing = false
        }
    }
}

private val coastBuffer = FloatArray(260_000)

private fun DrawScope.drawCoast(cam: Camera, globe: GlobeData?, lat: Double, lon: Double, radiusKm: Double, c: SotreusColors, alpha: Float, density: Float, out: FloatArray) {
    if (alpha <= 0.01f || globe == null) return
    val spanLat = radiusKm * 6 / 111.0 + 0.5
    val spanLon = spanLat / cos(Math.toRadians(lat)).coerceAtLeast(0.1)
    var n = 0
    val prev = FloatArray(2)
    for (line in globe.coast) {
        if (line.latMax < lat - spanLat || line.latMin > lat + spanLat || line.lonMax < lon - spanLon || line.lonMin > lon + spanLon) continue
        var havePrev = false
        var i = 0
        while (i < line.xyz.size && n < coastBuffer.size - 4) {
            val x = line.xyz[i]; val y = line.xyz[i + 1]; val z = line.xyz[i + 2]
            i += 3
            val ok = cam.facing(x, y, z) && cam.project(x, y, z, out)
            if (ok && havePrev) {
                coastBuffer[n++] = prev[0]; coastBuffer[n++] = prev[1]; coastBuffer[n++] = out[0]; coastBuffer[n++] = out[1]
            }
            havePrev = ok
            if (ok) { prev[0] = out[0]; prev[1] = out[1] }
        }
    }
    drawIntoCanvas { canvas ->
        linePaint.strokeWidth = 1.2f * density
        linePaint.color = c.textMuted.copy(alpha = 0.75f * alpha).toArgb()
        canvas.nativeCanvas.drawLines(coastBuffer, 0, n, linePaint)
    }
}

private fun DrawScope.drawPlate(
    cam: Camera, lat: Double, lon: Double, radiusKm: Double, c: SotreusColors, alpha: Float, density: Float,
    measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, out: FloatArray,
) {
    if (alpha <= 0.01f) return
    val enu = Enu(lat, lon)
    fun at(bearingRad: Double, km: Double) = enu.up + (enu.north * cos(bearingRad) + enu.east * sin(bearingRad)) * (km / EARTH_KM)
    for (k in 1..3) {
        val r = radiusKm * k / 3
        val path = Path()
        var started = false
        for (i in 0..120) {
            if (!cam.project(at(i / 120.0 * 2 * PI, r), out)) { started = false; continue }
            if (!started) path.moveTo(out[0], out[1]) else path.lineTo(out[0], out[1])
            started = true
        }
        if (k == 3) drawPath(path, c.accent.copy(alpha = 0.05f * alpha))
        drawPath(path, (if (k == 3) c.accent.copy(alpha = 0.55f * alpha) else c.lineStrong.copy(alpha = 0.8f * alpha)), style = Stroke((if (k == 3) 1.4f else 1f) * density, pathEffect = if (k == 3) null else PathEffect.dashPathEffect(floatArrayOf(4 * density, 5 * density))))
    }
    val centre = FloatArray(3)
    if (!cam.project(enu.up, centre)) return
    for (i in 0 until 8) {
        if (!cam.project(at(i * PI / 4, radiusKm), out)) continue
        drawLine(c.lineSubtle.copy(alpha = alpha), Offset(centre[0], centre[1]), Offset(out[0], out[1]), 0.8f * density)
    }
    listOf("N" to 0.0, "E" to PI / 2, "S" to PI, "W" to 3 * PI / 2).forEach { (label, br) ->
        if (cam.project(at(br, radiusKm * 1.12), out)) {
            val tl = measurer.measure(label, style.copy(color = (if (label == "N") c.accent else c.textMuted).copy(alpha = alpha)))
            drawText(tl, topLeft = Offset(out[0] - tl.size.width / 2f, out[1] - tl.size.height / 2f))
        }
    }
    if (cam.project(at(PI / 4, radiusKm), out)) {
        val tl = measurer.measure(km(radiusKm), style.copy(color = c.textMuted.copy(alpha = alpha)))
        drawText(tl, topLeft = Offset(out[0] + 4 * density, out[1] - tl.size.height))
    }
}

private fun DrawScope.drawUser(cam: Camera, lat: Double, lon: Double, real: Long, c: SotreusColors, density: Float, out: FloatArray) {
    if (!cam.project(ecef(lat, lon), out)) return
    val phase = (real % 2200) / 2200f
    val o = Offset(out[0], out[1])
    drawCircle(c.accent.copy(alpha = 0.45f * (1 - phase)), radius = (6 + 16 * phase) * density, center = o, style = Stroke(1.2f * density))
    drawCircle(c.accent.copy(alpha = 0.22f), radius = 9 * density, center = o)
    drawCircle(c.ink, radius = 5.5f * density, center = o)
    drawCircle(c.accent, radius = 4f * density, center = o)
}

/** Rectangles of labels drawn this frame, so later labels skip overlaps. */
private val placedLabels = ArrayList<androidx.compose.ui.geometry.Rect>()

/** Draws [text] beside a point, flipped left near the right edge; skipped when it would overlap. */
private fun DrawScope.label(measurer: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, color: Color, x: Float, y: Float, density: Float, force: Boolean = false) {
    val tl = measurer.measure(text, style.copy(color = color))
    val w = tl.size.width.toFloat()
    val h = tl.size.height.toFloat()
    val left = if (x + 7 * density + w > size.width - 6 * density) x - 7 * density - w else x + 7 * density
    val rect = androidx.compose.ui.geometry.Rect(left, y - h - 2 * density, left + w, y - 2 * density)
    if (!force && placedLabels.any { it.overlaps(rect) }) return
    placedLabels += rect
    drawRect(Color.Black.copy(alpha = 0.35f * color.alpha), topLeft = rect.topLeft, size = rect.size)
    drawText(tl, topLeft = rect.topLeft)
}

private fun DrawScope.drawSatellites(
    cam: Camera, s: SatSamples, now: Long, userLat: Double, userLon: Double, selected: OrbitalElements?, orbit: DoubleArray?,
    c: SotreusColors, alpha: Float, density: Float, measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, hits: MutableList<Hit>, out: FloatArray,
) {
    val f = ((now - s.t0).toDouble() / (s.t1 - s.t0)).coerceIn(-0.5, 2.0)
    val userEnu = Enu(userLat, userLon)
    val userUp = userEnu.up
    val userPt = FloatArray(3)
    val haveUser = cam.project(userUp, userPt)
    // Selected orbit first, behind the dots.
    if (selected != null && orbit != null) {
        val path = Path()
        var started = false
        for (i in 0 until orbit.size / 3) {
            val la = orbit[i * 3]; val lo = orbit[i * 3 + 1]; val alt = orbit[i * 3 + 2]
            if (la.isNaN()) { started = false; continue }
            val p = ecef(la, lo, compressedRadius(alt))
            if (cam.hidden(p) || !cam.project(p, out)) { started = false; continue }
            if (!started) path.moveTo(out[0], out[1]) else path.lineTo(out[0], out[1])
            started = true
        }
        drawPath(path, c.accent.copy(alpha = 0.7f * alpha), style = Stroke(1.3f * density))
    }
    data class Up(val name: String, val x: Float, val y: Float, val el: Double)
    val overhead = ArrayList<Up>()
    s.elements.forEachIndexed { i, e ->
        val la0 = s.a[i * 3]; val lo0 = s.a[i * 3 + 1]; val al0 = s.a[i * 3 + 2]
        val la1 = s.b[i * 3]; val lo1 = s.b[i * 3 + 1]; val al1 = s.b[i * 3 + 2]
        if (la0.isNaN() || la1.isNaN()) return@forEachIndexed
        val dLon = ((lo1 - lo0 + 540) % 360) - 180
        val la = la0 + (la1 - la0) * f
        val lo = lo0 + dLon * f
        val alt = al0 + (al1 - al0) * f
        val p = ecef(la, lo, compressedRadius(alt))
        if (cam.hidden(p) || !cam.project(p, out)) return@forEachIndexed
        val truePos = ecef(la, lo, 1 + alt / EARTH_KM)
        val rel = truePos - userUp
        val el = Math.toDegrees(kotlin.math.asin(((rel dot userUp) / rel.length).coerceIn(-1.0, 1.0)))
        val isSel = selected?.noradId == e.noradId
        val geo = e.geosynchronous
        val o = Offset(out[0], out[1])
        when {
            el > 0 && !geo -> {
                if (haveUser) drawLine(c.accent.copy(alpha = 0.28f * alpha), Offset(userPt[0], userPt[1]), o, 1f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * density, 4 * density)))
                drawCircle(c.accent.copy(alpha = 0.25f * alpha), 7f * density, o)
                drawCircle(c.accent.copy(alpha = alpha), 3.4f * density, o)
                overhead += Up(e.name, o.x, o.y, el)
            }
            geo -> drawCircle(c.textMuted.copy(alpha = 0.7f * alpha), 1.7f * density, o)
            else -> drawCircle(c.textSoft.copy(alpha = 0.75f * alpha), 2.1f * density, o)
        }
        if (isSel) {
            drawCircle(c.accent.copy(alpha = alpha), 9f * density, o, style = Stroke(1.5f * density))
            label(measurer, e.name, style, c.text.copy(alpha = alpha), o.x, o.y, density, force = true)
        }
        hits += Hit(o.x, o.y, Selection(SceneMode.GLOBE, e.noradId.toString()))
    }
    overhead.sortedByDescending { it.el }.take(4).forEach { u ->
        if (selected?.name != u.name) label(measurer, u.name, style, c.accent.copy(alpha = alpha), u.x, u.y, density)
    }
}

private fun deadReckon(lat: Double, lon: Double, speedMps: Double?, courseDeg: Double?, fromMs: Long?, now: Long, onGround: Boolean): Pair<Double, Double> {
    if (onGround || speedMps == null || courseDeg == null || fromMs == null) return lat to lon
    val dt = ((now - fromMs) / 1000.0).coerceIn(0.0, 150.0)
    val d = speedMps * dt
    val cr = Math.toRadians(courseDeg)
    val dLat = d * cos(cr) / 111_320.0
    val dLon = d * sin(cr) / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
    return (lat + dLat) to (lon + dLon)
}

private fun DrawScope.drawAircraft(
    cam: Camera, state: AircraftState.Ready, trails: Map<String, ArrayDeque<DoubleArray>>, real: Long, selection: Selection?,
    c: SotreusColors, alpha: Float, density: Float, measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, hits: MutableList<Hit>, out: FloatArray,
) {
    val ground = FloatArray(3)
    val ahead = FloatArray(3)
    state.aircraft.forEachIndexed { idx, na ->
        val r = na.report
        val lat0 = r.lat ?: return@forEachIndexed
        val lon0 = r.lon ?: return@forEachIndexed
        val (la, lo) = deadReckon(lat0, lon0, r.speedMps, r.courseDeg, r.positionAtMs, real, r.onGround)
        val altKm = (r.altitudeM ?: 0.0).coerceAtLeast(0.0) / 1000
        val air = ecef(la, lo, 1 + altKm / EARTH_KM)
        if (!cam.project(air, out)) return@forEachIndexed
        val isSel = selection?.mode == SceneMode.AIRCRAFT && selection.id == r.icao24
        val col = if (isSel) c.accent else c.text
        // Trail of previous reports.
        trails[r.icao24]?.let { q ->
            var px = Float.NaN
            var py = Float.NaN
            q.forEachIndexed { k, p ->
                val t = FloatArray(3)
                if (!cam.project(ecef(p[0], p[1], 1 + p[2].coerceAtLeast(0.0) / 1000 / EARTH_KM), t)) return@forEachIndexed
                if (!px.isNaN()) drawLine(col.copy(alpha = alpha * 0.15f * (k + 1) / q.size * 3), Offset(px, py), Offset(t[0], t[1]), 1.2f * density)
                px = t[0]; py = t[1]
            }
            if (!px.isNaN()) drawLine(col.copy(alpha = alpha * 0.35f), Offset(px, py), Offset(out[0], out[1]), 1.2f * density)
        }
        // Altitude stem and shadow.
        if (cam.project(ecef(la, lo), ground)) {
            drawLine(c.textMuted.copy(alpha = 0.6f * alpha), Offset(ground[0], ground[1]), Offset(out[0], out[1]), 1f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * density, 3 * density)))
            drawCircle(c.textDim.copy(alpha = 0.8f * alpha), 2.2f * density, Offset(ground[0], ground[1]))
        }
        // Arrow along the course.
        val course = r.courseDeg ?: 0.0
        val (la2, lo2) = deadReckon(la, lo, 200.0, course, 0, 2_000, false)
        val ang = if (cam.project(ecef(la2, lo2, 1 + altKm / EARTH_KM), ahead)) atan2((ahead[1] - out[1]).toDouble(), (ahead[0] - out[0]).toDouble()) else -PI / 2
        val sz = (if (isSel) 11f else 9f) * density
        val tri = Path().apply {
            moveTo((out[0] + cos(ang) * sz).toFloat(), (out[1] + sin(ang) * sz).toFloat())
            lineTo((out[0] + cos(ang + 2.5) * sz * 0.75).toFloat(), (out[1] + sin(ang + 2.5) * sz * 0.75).toFloat())
            lineTo((out[0] + cos(ang + PI) * sz * 0.25).toFloat(), (out[1] + sin(ang + PI) * sz * 0.25).toFloat())
            lineTo((out[0] + cos(ang - 2.5) * sz * 0.75).toFloat(), (out[1] + sin(ang - 2.5) * sz * 0.75).toFloat())
            close()
        }
        drawCircle(col.copy(alpha = 0.12f * alpha), sz * 1.6f, Offset(out[0], out[1]))
        drawPath(tri, col.copy(alpha = alpha))
        if (isSel) drawCircle(c.accent.copy(alpha = alpha), 12f * density, Offset(out[0], out[1]), style = Stroke(1.4f * density))
        if (idx < 6 || isSel) label(measurer, r.callsign ?: r.icao24.uppercase(), style, col.copy(alpha = 0.9f * alpha), out[0], out[1], density)
        hits += Hit(out[0], out[1], Selection(SceneMode.AIRCRAFT, r.icao24))
    }
}

private fun DrawScope.drawDrones(
    cam: Camera, drones: List<LiveRemoteId>, history: List<ContextEventEntity>, selection: Selection?,
    c: SotreusColors, alpha: Float, density: Float, measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, hits: MutableList<Hit>, out: FloatArray,
) {
    val ground = FloatArray(3)
    val op = FloatArray(3)
    val bySubject = history.groupBy { it.subjectId }
    drones.forEach { d ->
        val la = d.lat ?: return@forEach
        val lo = d.lon ?: return@forEach
        val altKm = (d.altM ?: 0.0).coerceAtLeast(0.0) / 1000
        val air = ecef(la, lo, 1 + altKm / EARTH_KM)
        if (!cam.project(air, out)) return@forEach
        val isSel = selection?.mode == SceneMode.REMOTE_ID && selection.id == d.subjectId
        val col = c.sensed
        // Broadcast history.
        // Recent history near the current position only (an ID can reappear somewhere else entirely).
        val here = app.sotreus.context.GeoPoint(la, lo)
        bySubject[d.subjectId]
            ?.filter { it.lat != null && it.lon != null && app.sotreus.context.Geo.distanceKm(here, app.sotreus.context.GeoPoint(it.lat!!, it.lon!!)) < 5.0 }
            ?.sortedBy { it.atMs }?.takeLast(40)?.let { pts ->
            val path = Path()
            var started = false
            pts.forEach { p ->
                val t = FloatArray(3)
                if (!cam.project(ecef(p.lat!!, p.lon!!, 1 + (p.altM ?: 0.0).coerceAtLeast(0.0) / 1000 / EARTH_KM), t)) return@forEach
                if (!started) path.moveTo(t[0], t[1]) else path.lineTo(t[0], t[1])
                started = true
            }
            if (started) path.lineTo(out[0], out[1])
            drawPath(path, col.copy(alpha = 0.45f * alpha), style = Stroke(1.3f * density))
        }
        val hasGround = cam.project(ecef(la, lo), ground)
        if (hasGround) {
            drawLine(c.textMuted.copy(alpha = 0.6f * alpha), Offset(ground[0], ground[1]), Offset(out[0], out[1]), 1f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * density, 3 * density)))
            drawCircle(c.textDim.copy(alpha = 0.8f * alpha), 2.2f * density, Offset(ground[0], ground[1]))
        }
        // Operator position and link.
        val oLat = d.operatorLat
        val oLon = d.operatorLon
        if (oLat != null && oLon != null && cam.project(ecef(oLat, oLon), op)) {
            if (hasGround) drawLine(col.copy(alpha = 0.5f * alpha), Offset(op[0], op[1]), Offset(ground[0], ground[1]), 1f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5 * density, 4 * density)))
            val s = 4.5f * density
            drawRect(col.copy(alpha = alpha), topLeft = Offset(op[0] - s, op[1] - s), size = androidx.compose.ui.geometry.Size(2 * s, 2 * s), style = Stroke(1.4f * density))
        }
        // Quadcopter glyph: four rotors around a body.
        val o = Offset(out[0], out[1])
        val arm = (if (isSel) 7f else 5.5f) * density
        for (k in 0 until 4) {
            val a = PI / 4 + k * PI / 2
            val rp = Offset((o.x + cos(a) * arm).toFloat(), (o.y + sin(a) * arm).toFloat())
            drawLine(col.copy(alpha = alpha), o, rp, 1.4f * density)
            drawCircle(col.copy(alpha = alpha), 2.6f * density, rp, style = Stroke(1.2f * density))
        }
        drawCircle(col.copy(alpha = alpha), 2.2f * density, o)
        if (isSel) drawCircle(c.accent.copy(alpha = alpha), 14f * density, o, style = Stroke(1.4f * density))
        label(measurer, d.maker ?: d.selfId ?: d.uasId ?: d.subjectId, style, col.copy(alpha = alpha), o.x + 4 * density, o.y, density)
        hits += Hit(o.x, o.y, Selection(SceneMode.REMOTE_ID, d.subjectId))
    }
}
