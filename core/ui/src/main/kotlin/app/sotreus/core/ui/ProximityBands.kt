package app.sotreus.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.ProximityBand
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One dot on the Bands canvas. [angle] is a stable hash, never a bearing. */
data class BandDot(
    val id: String,
    val band: ProximityBand,
    val angle: Double,
    val radial: Double,
    val glyph: GlyphSpec,
    val label: String? = null,
    /** Short name drawn when zoomed in far enough to read it. */
    val zoomLabel: String? = null,
)

data class BandLabels(val near: String, val mid: String, val far: String, val you: String)

/**
 * Proximity Bands (HANDOFF_V1_UI.md §6.3): three rings by 30 s average RSSI. Where a dot sits
 * around a ring is not its direction.
 *
 * Pinch to zoom from the full view (the minimum) up to [MAX_ZOOM]: positions spread while glyphs
 * keep their size, so crowded dots separate and can be tapped. Drag pans only while zoomed, so the
 * page still scrolls at 1×. Double-tap resets. The List view is the accessible equivalent.
 */
@Composable
fun ProximityBands(
    allDots: List<BandDot>,
    labels: BandLabels,
    contentDescription: String,
    onDotClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    zoomHint: String? = null,
    resetLabel: String? = null,
    fullViewLimit: Int = 120,
) {
    val c = SotreusTheme.colors
    val measurer = rememberTextMeasurer()
    val mono = SotreusTheme.typography.monoLabelS.copy(fontSize = 9.sp)
    val shape = SotreusTheme.shapes.hero
    var scale by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(MIN_MAX_ZOOM) }
    // At full view the busiest scenes are capped (callers order dots by priority); zoomed in,
    // every radio is drawn so any of them can be picked out.
    val zoomedIn = scale >= SHOW_ALL_ZOOM
    val dots = if (zoomedIn) allDots else allDots.take(fullViewLimit)
    val overflow = (allDots.size - dots.size).coerceAtLeast(0)
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun clampOffset(o: Offset, s: Float, w: Float, h: Float): Offset {
        val maxX = (s - 1f) * w / 2f
        val maxY = (s - 1f) * h / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(modifier.fillMaxWidth().height(296.dp)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.line, shape)
                .semantics { this.contentDescription = contentDescription }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            // One finger at 1× is a page scroll, not ours.
                            if (fingers >= 2 || scale > 1f) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val newScale = (scale * zoom).coerceIn(1f, maxZoom)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                // Keep the point under the fingers fixed while zooming.
                                val anchored = if (centroid != Offset.Unspecified) {
                                    (offset - (centroid - center)) * (newScale / scale) + (centroid - center)
                                } else {
                                    offset
                                }
                                scale = newScale
                                offset = if (scale <= 1f) Offset.Zero else clampOffset(anchored + pan, scale, size.width.toFloat(), size.height.toFloat())
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(dots) {
                    detectTapGestures(
                        onDoubleTap = { scale = 1f; offset = Offset.Zero },
                        onTap = { tap ->
                            val geo = BandGeometry(size.width.toFloat(), size.height.toFloat(), density, scale, offset)
                            dots.minByOrNull { d -> geo.position(d).let { p -> hypot(p.x - tap.x, p.y - tap.y) } }
                                ?.takeIf { d -> geo.position(d).let { p -> hypot(p.x - tap.x, p.y - tap.y) } < 24.dp.toPx() }
                                ?.let { onDotClick(it.id) }
                        },
                    )
                },
        ) {
            maxZoom = bandsMaxZoom(allDots, size.width, size.height, density)
            val geo = BandGeometry(size.width, size.height, density, scale, offset)
            drawCircle(c.lineNav, geo.rFar, geo.center, style = Stroke(1.dp.toPx()))
            drawCircle(c.line, geo.rMid, geo.center, style = Stroke(1.dp.toPx()))
            drawCircle(c.accent.copy(alpha = 0.03f), geo.rNear, geo.center)
            drawCircle(c.lineStrong, geo.rNear, geo.center, style = Stroke(1.dp.toPx()))
            listOf(labels.far to geo.rFar, labels.mid to geo.rMid, labels.near to geo.rNear).forEachIndexed { i, (text, r) ->
                val layout = measurer.measure(text.uppercase(), mono.copy(color = if (i == 2) c.textMuted else c.textDim))
                val tl = Offset(geo.center.x - layout.size.width / 2f, geo.center.y - r - layout.size.height / 2f)
                drawRect(c.surface, tl - Offset(6.dp.toPx(), 1.dp.toPx()), Size(layout.size.width + 12.dp.toPx(), layout.size.height + 2.dp.toPx()))
                drawText(layout, topLeft = tl)
            }
            val glyphPx = 9.dp.toPx()
            dots.sortedBy { it.glyph.tone == GlyphTone.ATTENTION || it.glyph.tone == GlyphTone.TAGGED }.forEach { d ->
                drawGlyph(d.glyph, c, geo.position(d), if (d.glyph.tone == GlyphTone.ATTENTION || d.glyph.shape == GlyphShape.TAGGED) 10.dp.toPx() else glyphPx)
            }
            fun label(d: BandDot, text: String) {
                val p = geo.position(d)
                if (p.x < 0 || p.y < 0 || p.x > size.width || p.y > size.height) return
                val color = if (d.glyph.tone == GlyphTone.ATTENTION || d.glyph.tone == GlyphTone.NEW) c.accent else c.text
                val layout = measurer.measure(text.uppercase(), mono.copy(color = color, letterSpacing = 0.1.sp), maxLines = 1)
                val pad = 6.dp.toPx()
                var x = p.x + 12.dp.toPx()
                if (x + layout.size.width + pad > size.width) x = p.x - 12.dp.toPx() - layout.size.width - pad
                val y = p.y - layout.size.height / 2f
                drawRoundRect(c.surface.copy(alpha = 0.9f), Offset(x - pad / 2, y - 2.dp.toPx()), Size(layout.size.width + pad, layout.size.height + 4.dp.toPx()), CornerRadius(4.dp.toPx()))
                drawText(layout, topLeft = Offset(x, y))
            }
            if (scale >= LABEL_ZOOM) {
                // Zoomed in: name what is visible, nearest the centre first, up to a readable count.
                dots.filter { it.zoomLabel != null || it.label != null }
                    .map { it to geo.position(it) }
                    .filter { (_, p) -> p.x in 0f..size.width && p.y in 0f..size.height }
                    .sortedBy { (_, p) -> hypot(p.x - size.width / 2, p.y - size.height / 2) }
                    .take(MAX_ZOOM_LABELS)
                    .forEach { (d, _) -> label(d, d.label ?: d.zoomLabel!!) }
            } else {
                dots.filter { it.label != null }.take(3).forEach { label(it, it.label!!) }
            }
            drawGlyph(GlyphSpec(GlyphShape.YOU, GlyphTone.TAGGED), c, geo.center, 12.dp.toPx())
            val you = measurer.measure(labels.you.uppercase(), mono.copy(color = c.textMuted))
            drawText(you, topLeft = Offset(geo.center.x - you.size.width / 2f, geo.center.y + 8.dp.toPx()))
            if (overflow > 0) {
                val more = measurer.measure("+$overflow", mono.copy(color = c.textMuted))
                drawText(more, topLeft = Offset(size.width - more.size.width - 14.dp.toPx(), size.height - more.size.height - 12.dp.toPx()))
            }
            val hint = if (scale > 1f) "%.1f× · ${resetLabel.orEmpty()}".format(scale) else zoomHint
            hint?.let {
                val layout = measurer.measure(it.uppercase(), mono.copy(color = c.textDim))
                drawText(layout, topLeft = Offset(14.dp.toPx(), size.height - layout.size.height - 12.dp.toPx()))
            }
        }
    }
}

private const val MIN_MAX_ZOOM = 3f
private const val CAP_MAX_ZOOM = 24f
private const val SEPARATION_DP = 28f

/**
 * Zoom in only as far as needed: until the closest pair of dots is [SEPARATION_DP] apart, so
 * every device can be picked out, bounded to 3×–24×. Zooming out stops at the full view (1×).
 */
internal fun bandsMaxZoom(dots: List<BandDot>, width: Float, height: Float, density: Float): Float {
    if (dots.size < 2) return MIN_MAX_ZOOM
    val geo = BandGeometry(width, height, density)
    val points = dots.map(geo::position)
    var closest = Float.MAX_VALUE
    for (i in points.indices) for (j in i + 1 until points.size) {
        closest = minOf(closest, hypot(points[i].x - points[j].x, points[i].y - points[j].y))
    }
    if (closest <= 0f) return CAP_MAX_ZOOM
    return (SEPARATION_DP * density / closest).coerceIn(MIN_MAX_ZOOM, CAP_MAX_ZOOM)
}
private const val LABEL_ZOOM = 2.5f
private const val SHOW_ALL_ZOOM = 1.5f
private const val MAX_ZOOM_LABELS = 10

internal class BandGeometry(w: Float, h: Float, density: Float, private val scale: Float = 1f, offset: Offset = Offset.Zero) {
    private val viewCenter = Offset(w / 2, h / 2)
    val center = viewCenter + offset
    private val baseFar = (h / 2 - 8 * density).coerceAtMost(w / 2 - 8 * density)
    val rFar = baseFar * scale
    val rMid = rFar * 95f / 140f
    val rNear = rFar * 50f / 140f
    private val rYou = rFar * 14f / 140f

    fun position(d: BandDot): Offset {
        val (inner, outer) = when (d.band) {
            ProximityBand.NEAR -> rYou to rNear
            ProximityBand.MID -> rNear to rMid
            ProximityBand.FAR -> rMid to rFar
        }
        val r = inner + (outer - inner) * d.radial.toFloat()
        return Offset(center.x + r * cos(d.angle).toFloat(), center.y + r * sin(d.angle).toFloat())
    }
}
