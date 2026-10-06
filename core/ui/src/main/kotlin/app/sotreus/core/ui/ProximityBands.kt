package app.sotreus.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
)

/**
 * Proximity Bands (HANDOFF_V1_UI.md §6.3): three rings by 30 s average RSSI. Where a dot sits
 * around a ring is not its direction. The canvas carries a TalkBack summary; the List view is the
 * accessible equivalent.
 */
@Composable
fun ProximityBands(
    dots: List<BandDot>,
    overflow: Int,
    labels: BandLabels,
    contentDescription: String,
    onDotClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = SotreusTheme.colors
    val measurer = rememberTextMeasurer()
    val mono = SotreusTheme.typography.monoLabelS.copy(fontSize = 9.sp)
    val shape = SotreusTheme.shapes.hero
    Canvas(
        modifier
            .fillMaxWidth()
            .height(296.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(dots) {
                detectTapGestures { tap ->
                    val geo = BandGeometry(size.width.toFloat(), size.height.toFloat(), density)
                    dots.minByOrNull { d -> geo.position(d).let { p -> hypot(p.x - tap.x, p.y - tap.y) } }
                        ?.takeIf { d -> geo.position(d).let { p -> hypot(p.x - tap.x, p.y - tap.y) } < 24.dp.toPx() }
                        ?.let { onDotClick(it.id) }
                }
            },
    ) {
        val geo = BandGeometry(size.width, size.height, density)
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
        dots.filter { it.label != null }.take(3).forEach { d ->
            val p = geo.position(d)
            val color = if (d.glyph.tone == GlyphTone.ATTENTION || d.glyph.tone == GlyphTone.NEW) c.accent else c.text
            val layout = measurer.measure(d.label!!.uppercase(), mono.copy(color = color, letterSpacing = 0.1.sp))
            val pad = 6.dp.toPx()
            var x = p.x + 12.dp.toPx()
            if (x + layout.size.width + pad > size.width) x = p.x - 12.dp.toPx() - layout.size.width - pad
            val y = p.y - layout.size.height / 2f
            drawRoundRect(c.surface.copy(alpha = 0.9f), Offset(x - pad / 2, y - 2.dp.toPx()), Size(layout.size.width + pad, layout.size.height + 4.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawText(layout, topLeft = Offset(x, y))
        }
        drawGlyph(GlyphSpec(GlyphShape.YOU, GlyphTone.TAGGED), c, geo.center, 12.dp.toPx())
        val you = measurer.measure(labels.you.uppercase(), mono.copy(color = c.textMuted))
        drawText(you, topLeft = Offset(geo.center.x - you.size.width / 2f, geo.center.y + 8.dp.toPx()))
        if (overflow > 0) {
            val more = measurer.measure("+$overflow", mono.copy(color = c.textMuted))
            drawText(more, topLeft = Offset(size.width - more.size.width - 14.dp.toPx(), size.height - more.size.height - 12.dp.toPx()))
        }
    }
}

data class BandLabels(val near: String, val mid: String, val far: String, val you: String)

private class BandGeometry(w: Float, h: Float, density: Float) {
    val center = Offset(w / 2, h / 2)
    val rFar = (h / 2 - 8 * density).coerceAtMost(w / 2 - 8 * density)
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
