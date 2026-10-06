package app.sotreus.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.theme.InstrumentSerif
import app.sotreus.core.designsystem.theme.SotreusTheme

/** One freshness item: label, value, and whether the value is limited (amber). */
data class Freshness(val label: String, val value: String?, val limited: Boolean = false)

/** "BLE · 2 S   WI-FI · 38 S · THROTTLED   BASELINE · 14 VISITS" (screens 03, 11). */
@Composable
fun FreshnessLine(items: List<Freshness>, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    val style = SotreusTheme.typography.monoLabelS.copy(letterSpacing = SotreusTheme.typography.monoLabelS.letterSpacing * 0.7f)
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { f ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.textDim)) { append(f.label.uppercase()) }
                    f.value?.let {
                        withStyle(SpanStyle(color = c.textDim)) { append(" · ") }
                        withStyle(SpanStyle(color = if (f.limited) c.accent else c.text)) { append(it.uppercase()) }
                    }
                },
                style = style,
            )
        }
    }
}

/** Full-width devnet banner with a dashed bottom edge. Non-dismissible (screen S4). */
@Composable
fun DevnetBanner(text: String, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Box(
        modifier.fillMaxWidth().heightIn(min = 40.dp).background(c.surfaceBanner).drawBehind {
            drawLine(c.textDim, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        },
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), style = SotreusTheme.typography.monoLabel, color = c.text) }
}

/** Initial-letter avatar (friends, profile). */
@Composable
fun Avatar(name: String, size: Dp = 38.dp, serif: Boolean = false, dashed: Boolean = false) {
    val c = SotreusTheme.colors
    Box(
        Modifier.size(size).then(if (dashed) Modifier.border(1.dp, c.textDim, SotreusTheme.shapes.pill) else Modifier.background(c.lineMid, SotreusTheme.shapes.pill)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "·" },
            style = if (serif) SotreusTheme.typography.titleS.copy(fontFamily = InstrumentSerif, fontSize = SotreusTheme.typography.titleS.fontSize * (size.value / 64f) * 0.9f) else SotreusTheme.typography.rowTitle.copy(fontWeight = FontWeight.Medium),
            color = c.text,
        )
    }
}

/** Legend under the Bands canvas. */
@Composable
fun GlyphLegend(items: List<Pair<GlyphSpec, String>>, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (g, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Glyph(g, size = 8.dp)
                Text(label.uppercase(), style = SotreusTheme.typography.monoLabelS.copy(letterSpacing = SotreusTheme.typography.monoLabelS.letterSpacing * 0.7f), color = SotreusTheme.colors.textMuted)
            }
        }
    }
}

/** Calm empty / first-run state composed from existing type styles. */
@Composable
fun EmptyHint(kicker: String, body: String, modifier: Modifier = Modifier) {
    CaveatBox(kicker, body, modifier)
}
