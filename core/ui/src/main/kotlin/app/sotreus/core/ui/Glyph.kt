package app.sotreus.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.theme.SotreusColors
import app.sotreus.core.designsystem.theme.SotreusTheme

/** The shared glyph language (HANDOFF_V1_UI.md §3.4). */
enum class GlyphShape { BLE, WIFI, TAGGED, YOU, SYSTEM_NOTE }

enum class GlyphTone { NEW, FAMILIAR, TAGGED, NEUTRAL, ATTENTION }

data class GlyphSpec(val shape: GlyphShape, val tone: GlyphTone, val stale: Boolean = false)

@Composable
fun Glyph(spec: GlyphSpec, modifier: Modifier = Modifier, size: Dp = 10.dp, outlineWifi: Boolean = true) {
    val colors = SotreusTheme.colors
    Canvas(modifier.size(size)) { drawGlyph(spec, colors, Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension, outlineWifi) }
}

/** Draws a glyph centred at [center] with extent [d] px. Also used by the Bands canvas. */
fun DrawScope.drawGlyph(spec: GlyphSpec, c: SotreusColors, center: Offset, d: Float, outlineWifi: Boolean = false) {
    val fill: Color = when (spec.tone) {
        GlyphTone.NEW, GlyphTone.ATTENTION -> c.accent
        GlyphTone.FAMILIAR -> c.familiarGlyph
        GlyphTone.TAGGED -> c.text
        GlyphTone.NEUTRAL -> c.textMuted
    }
    val dash = PathEffect.dashPathEffect(floatArrayOf(d * 0.22f, d * 0.16f))
    if (spec.tone == GlyphTone.ATTENTION) {
        drawCircle(c.accent.copy(alpha = 0.08f), radius = d * 1.4f, center = center)
        drawCircle(c.accent.copy(alpha = 0.22f), radius = d * 0.9f, center = center)
    }
    val stroke = Stroke(width = d * 0.15f, pathEffect = if (spec.stale) dash else null)
    when (spec.shape) {
        GlyphShape.BLE -> if (spec.stale) {
            drawCircle(c.textMuted, radius = d / 2 - stroke.width / 2, center = center, style = stroke)
        } else {
            drawCircle(fill, radius = d / 2, center = center)
        }
        GlyphShape.WIFI -> {
            val s = d - stroke.width
            val tl = Offset(center.x - s / 2, center.y - s / 2)
            val corner = CornerRadius(d * 0.2f)
            when {
                spec.stale -> drawRoundRect(c.textMuted, tl, Size(s, s), corner, style = stroke)
                outlineWifi -> drawRoundRect(if (spec.tone == GlyphTone.NEW || spec.tone == GlyphTone.ATTENTION) c.accent else c.textMuted, tl, Size(s, s), corner, style = stroke)
                else -> drawRoundRect(fill, Offset(center.x - d / 2, center.y - d / 2), Size(d, d), corner)
            }
        }
        GlyphShape.TAGGED -> {
            val r = d / 2
            val p = Path().apply {
                moveTo(center.x, center.y - r)
                lineTo(center.x + r, center.y)
                lineTo(center.x, center.y + r)
                lineTo(center.x - r, center.y)
                close()
            }
            if (spec.stale) drawPath(p, c.textMuted, style = stroke) else drawPath(p, c.text)
        }
        GlyphShape.YOU -> drawCircle(c.text, radius = d / 2 - d * 0.0625f, center = center, style = Stroke(width = d * 0.125f))
        GlyphShape.SYSTEM_NOTE -> drawCircle(c.textDim, radius = d / 2 - stroke.width / 2, center = center, style = Stroke(width = d * 0.11f, pathEffect = dash))
    }
}

/** 32 dp rounded well holding a glyph (lists). Attention-styled for new radios. */
@Composable
fun GlyphWell(spec: GlyphSpec, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    val bg = if (spec.tone == GlyphTone.NEW || spec.tone == GlyphTone.ATTENTION) c.attentionSurface else c.surfaceHigh
    Box(modifier.size(32.dp).background(bg, SotreusTheme.shapes.glyphWell), contentAlignment = Alignment.Center) {
        Glyph(spec.copy(tone = if (spec.tone == GlyphTone.ATTENTION) GlyphTone.NEW else spec.tone))
    }
}
