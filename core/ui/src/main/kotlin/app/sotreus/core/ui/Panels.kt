package app.sotreus.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme

/** Card variants used across screens. */
enum class CardStyle { SURFACE, RAISED, OUTLINE_STRONG, ATTENTION, ATTENTION_LARGE }

@Composable
fun SotreusCard(
    modifier: Modifier = Modifier,
    style: CardStyle = CardStyle.RAISED,
    large: Boolean = false,
    onClick: (() -> Unit)? = null,
    padding: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = SotreusTheme.colors
    val shape = if (large) SotreusTheme.shapes.featureCard else SotreusTheme.shapes.listCard
    val (bg, border) = when (style) {
        CardStyle.SURFACE -> c.surface to c.line
        CardStyle.RAISED -> c.surfaceRaised to c.line
        CardStyle.OUTLINE_STRONG -> c.surface to c.lineStrong
        CardStyle.ATTENTION -> c.attentionSurface to c.attentionLine
        CardStyle.ATTENTION_LARGE -> c.attentionSurfaceLarge to c.attentionLine
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** "What this does not mean" box: dashed border, mono kicker, soft body. */
@Composable
fun CaveatBox(kicker: String, body: String, modifier: Modifier = Modifier) {
    DashedPanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s), modifier = Modifier.padding(bottom = SotreusTheme.spacing.m)) {
            MonoLabel(kicker, small = true)
            Text(body, style = SotreusTheme.typography.bodyS, color = SotreusTheme.colors.textSoft)
        }
    }
}

/** Amber banner with a clock icon, e.g. Wi-Fi throttling. Never hide throttling. */
@Composable
fun WarningBanner(text: String, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Row(
        modifier.fillMaxWidth().background(c.attentionSurface, SotreusTheme.shapes.tile).border(1.dp, c.attentionLine, SotreusTheme.shapes.tile)
            .padding(horizontal = SotreusTheme.spacing.xl, vertical = SotreusTheme.spacing.l),
        horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l),
    ) {
        Icon(SotreusIcons.Clock, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(text, style = SotreusTheme.typography.caption, color = c.textSoft)
    }
}

/** Mono key/value table on `surface` with `lineSubtle` dividers (screens 06, 13, S5). */
@Composable
fun KeyValueTable(rows: List<Pair<String, String>>, modifier: Modifier = Modifier, title: String? = null, attention: Boolean = false) {
    val c = SotreusTheme.colors
    Column(
        modifier.fillMaxWidth()
            .background(if (attention) c.attentionSurfaceLarge else c.surface, SotreusTheme.shapes.panel)
            .border(1.dp, if (attention) c.attentionLine else c.line, SotreusTheme.shapes.panel)
            .padding(horizontal = 14.dp, vertical = SotreusTheme.spacing.s),
    ) {
        if (title != null) MonoLabel(title, small = true, modifier = Modifier.padding(top = SotreusTheme.spacing.m, bottom = SotreusTheme.spacing.xs))
        rows.forEachIndexed { i, (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, style = SotreusTheme.typography.monoValue, color = if (attention) c.textMuted else c.textDim)
                Text(v, style = SotreusTheme.typography.monoValue, color = c.text, modifier = Modifier.padding(start = SotreusTheme.spacing.xl))
            }
            if (i < rows.lastIndex) HorizontalDivider(thickness = 1.dp, color = if (attention) c.attentionDivider else c.lineSubtle)
        }
    }
}

data class Stat(val value: String, val label: String, val kicker: String? = null, val attention: Boolean = false)

/** Separate stat tiles (screen 03's HERE / FAMILIAR / CHANGED / SEEN). */
@Composable
fun StatTiles(stats: List<Stat>, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
        stats.forEach { s ->
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (s.attention) c.attentionSurface else c.surfaceRaised, SotreusTheme.shapes.tile)
                    .border(1.dp, if (s.attention) c.attentionLine else c.line, SotreusTheme.shapes.tile)
                    .padding(SotreusTheme.spacing.m),
            ) {
                s.kicker?.let { Text(it.uppercase(), style = SotreusTheme.typography.monoLabelS.copy(fontSize = SotreusTheme.typography.monoLabelS.fontSize * 0.9f), color = if (s.attention) c.accent else c.textDim, maxLines = 1) }
                Text(s.value, style = SotreusTheme.typography.bodyL.copy(fontSize = SotreusTheme.typography.bodyL.fontSize * 1.375f, fontWeight = FontWeight.Medium), color = c.text)
                Text(s.label, style = SotreusTheme.typography.caption.copy(fontSize = SotreusTheme.typography.caption.fontSize * 0.85f), color = c.textMuted, maxLines = 1)
            }
        }
    }
}

/** Joined stat grid with 1 px dividers (screens 06, 11, 12, 14, S4). */
@Composable
fun StatGrid(stats: List<Stat>, modifier: Modifier = Modifier, columns: Int = stats.size.coerceAtMost(3), mono: Boolean = false) {
    val c = SotreusTheme.colors
    Column(
        modifier.fillMaxWidth().clip(SotreusTheme.shapes.panel).background(c.line).border(1.dp, c.line, SotreusTheme.shapes.panel),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        stats.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                row.forEach { s ->
                    Column(
                        Modifier.weight(1f).fillMaxHeight().background(if (s.attention) c.attentionSurfaceLarge else c.ink).padding(horizontal = SotreusTheme.spacing.xl + 2.dp, vertical = SotreusTheme.spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (s.kicker != null) {
                            MonoLabel(s.kicker, small = true)
                            Text(s.value, style = SotreusTheme.typography.bodyL.copy(fontWeight = FontWeight.Medium, fontSize = SotreusTheme.typography.bodyL.fontSize * 0.95f), color = c.text)
                        } else {
                            Text(s.value, style = SotreusTheme.typography.bodyL.copy(fontWeight = FontWeight.Medium, fontSize = SotreusTheme.typography.bodyL.fontSize * 1.375f), color = if (s.attention) c.accent else c.text)
                            Text(s.label, style = SotreusTheme.typography.caption, color = c.textMuted)
                        }
                    }
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f).fillMaxHeight().background(c.ink)) }
            }
        }
    }
}

/** Two-cell split: left neutral ("goes on-chain"), right attention ("stays on this phone"). */
@Composable
fun PublicPrivateSplit(leftKicker: String, leftLines: List<String>, rightKicker: String, rightLines: List<String>, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(SotreusTheme.shapes.featureCard).background(c.line).border(1.dp, c.line, SotreusTheme.shapes.featureCard),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        listOf(Triple(leftKicker, leftLines, false), Triple(rightKicker, rightLines, true)).forEach { (k, lines, attention) ->
            Column(
                Modifier.weight(1f).fillMaxHeight().background(if (attention) c.attentionSurfaceLarge else c.surface).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s),
            ) {
                Text(k.uppercase(), style = SotreusTheme.typography.monoLabelS, color = if (attention) c.accent else c.textDim)
                Text(lines.joinToString("\n"), style = SotreusTheme.typography.bodyS, color = c.textSoft)
            }
        }
    }
}

/** Amber-dot attention card with kicker, title and chevron (screen 03). */
@Composable
fun AttentionRowCard(kicker: String, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Row(
        modifier.fillMaxWidth().clip(SotreusTheme.shapes.listCard).background(c.surfaceHigh).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = SotreusTheme.sizes.minTouch).padding(horizontal = 14.dp, vertical = SotreusTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl),
    ) {
        Box(Modifier.size(8.dp).background(c.accent, SotreusTheme.shapes.pill))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(kicker.uppercase(), style = SotreusTheme.typography.monoLabelS, color = c.accent)
            Text(title, style = SotreusTheme.typography.bodyS, color = c.text)
        }
        Icon(SotreusIcons.ChevronRight, contentDescription = null, tint = c.textDim, modifier = Modifier.size(18.dp))
    }
}

/** Divider used between list rows. */
@Composable
fun RowDivider(modifier: Modifier = Modifier, strong: Boolean = false) {
    HorizontalDivider(modifier, thickness = 1.dp, color = if (strong) SotreusTheme.colors.line else SotreusTheme.colors.lineSubtle)
}

/** Label + optional trailing slot header used above lists ("PREVIOUS · 7 ......... Compare sits"). */
@Composable
fun SectionHeader(label: String, modifier: Modifier = Modifier, accent: Boolean = false, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = SotreusTheme.typography.monoLabel, color = if (accent) SotreusTheme.colors.accent else SotreusTheme.colors.textDim, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Small spacer helper for inline row gaps. */
@Composable
fun HGap(width: androidx.compose.ui.unit.Dp) = Box(Modifier.width(width))

/** Thin amber/grey fill used by input bars and composition bars. */
@Composable
fun BarTrack(fraction: Float, modifier: Modifier = Modifier, color: Color = SotreusTheme.colors.accent) {
    Box(modifier.height(4.dp).clip(SotreusTheme.shapes.pill).background(SotreusTheme.colors.lineNav)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(color))
    }
}
