package app.sotreus.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.theme.SotreusTheme

/** 60 s RSSI line (screen 07). Up = louder. Not distance, not direction. */
@Composable
fun RssiSparkline(
    samples: List<Pair<Long, Int>>,
    now: Long,
    topLabel: String,
    bottomLabel: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = SotreusTheme.colors
    Box(
        modifier.fillMaxWidth().height(128.dp).clip(SotreusTheme.shapes.panel).background(c.surface).border(1.dp, c.line, SotreusTheme.shapes.panel)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            listOf(0.25f, 0.5f, 0.75f).forEach { f -> drawLine(c.lineNav, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx()) }
            if (samples.size >= 2) {
                val minDbm = -100f
                val maxDbm = -30f
                fun x(t: Long) = (1f - (now - t) / 60_000f).coerceIn(0f, 1f) * size.width
                fun y(v: Int) = (1f - (v - minDbm) / (maxDbm - minDbm)).coerceIn(0.04f, 0.96f) * size.height
                val path = Path()
                samples.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
                drawPath(path, c.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val (lt, lv) = samples.last()
                drawCircle(c.accent, 4.dp.toPx(), Offset(x(lt), y(lv)))
            }
        }
        Text(topLabel.uppercase(), style = SotreusTheme.typography.monoLabelS, color = c.textDim, modifier = Modifier.padding(start = 10.dp, top = 8.dp))
        Text(bottomLabel.uppercase(), style = SotreusTheme.typography.monoLabelS, color = c.textDim, modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 8.dp))
    }
}

data class TimelineItem(val time: String, val text: String, val glyph: GlyphSpec, val muted: Boolean)

/** Vertical rule + glyph markers + mono time (screen 11). */
@Composable
fun SessionTimeline(items: List<TimelineItem>, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Column(modifier.fillMaxWidth().padding(start = 4.dp)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp)) {
                Box(Modifier.width(12.dp).heightIn(min = 36.dp), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(1.dp).height(38.dp).background(c.lineStrong))
                    Glyph(item.glyph, Modifier.padding(top = 13.dp).alpha(if (item.muted && item.glyph.shape != GlyphShape.SYSTEM_NOTE) 0.5f else 1f), size = 9.dp)
                }
                Row(Modifier.padding(start = 12.dp, top = 9.dp, bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(item.time, style = SotreusTheme.typography.monoValue, color = c.textDim, modifier = Modifier.width(44.dp))
                    Text(item.text, style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f), color = if (item.muted) c.textMuted else c.text)
                }
            }
        }
    }
}

/** Composition bar (screen 09): normally present / occasional / new. */
@Composable
fun CompositionBar(normal: Int, occasional: Int, new: Int, contentDescription: String, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    val total = (normal + occasional + new).coerceAtLeast(1)
    Row(
        modifier.fillMaxWidth().height(10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(normal to c.familiarGlyph, occasional to c.textMuted, new to c.accent).filter { it.first > 0 }.forEach { (n, color) ->
            Box(Modifier.weight(n.toFloat() / total).fillMaxSize().background(color))
        }
    }
}

/** Big number + caption triple used under the composition bar. */
@Composable
fun CountTriple(values: List<Triple<String, String, Boolean>>, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
        values.forEach { (value, label, accent) ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value, style = SotreusTheme.typography.bodyL.copy(fontWeight = FontWeight.Medium, fontSize = SotreusTheme.typography.bodyL.fontSize * 1.375f), color = if (accent) c.accent else c.text)
                Text(label, style = SotreusTheme.typography.caption, color = c.textMuted)
            }
        }
    }
}
