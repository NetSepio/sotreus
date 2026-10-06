package app.sotreus.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.theme.SotreusTheme

enum class ChipTone { NEUTRAL, TEXT, ACCENT, ACCENT_FILLED, DASHED, ATTENTION_OUTLINE }

/** Small mono chip for states and metadata: NEW, FAMILIAR, TAGGED, STALE, MAINNET, SENSED · 1 S AGO. */
@Composable
fun StateChip(text: String, tone: ChipTone, modifier: Modifier = Modifier, small: Boolean = true) {
    val c = SotreusTheme.colors
    val (fg, border, fill) = when (tone) {
        ChipTone.NEUTRAL -> Triple(c.textMuted, c.lineStrong, Color.Transparent)
        ChipTone.TEXT -> Triple(c.text, c.text, Color.Transparent)
        ChipTone.ACCENT -> Triple(c.accent, c.accent, Color.Transparent)
        ChipTone.ACCENT_FILLED -> Triple(c.accent, Color.Transparent, c.accentTint)
        ChipTone.DASHED -> Triple(c.textSoft, c.textMuted, Color.Transparent)
        ChipTone.ATTENTION_OUTLINE -> Triple(c.accent, c.attentionLine, Color.Transparent)
    }
    val shape = SotreusTheme.shapes.pill
    Box(
        modifier
            .then(if (fill != Color.Transparent) Modifier.background(fill, shape) else Modifier)
            .then(
                when {
                    tone == ChipTone.DASHED -> Modifier.dashedPill(border)
                    border != Color.Transparent -> Modifier.border(1.dp, border, shape)
                    else -> Modifier
                },
            )
            .padding(horizontal = if (small) 6.dp else 8.dp, vertical = if (small) 2.dp else 4.dp),
    ) {
        Text(text.uppercase(), style = if (small) SotreusTheme.typography.monoLabelS.copy(letterSpacing = SotreusTheme.typography.monoLabelS.letterSpacing * 0.7f) else SotreusTheme.typography.monoLabelS, color = fg, maxLines = 1)
    }
}

private fun Modifier.dashedPill(color: Color) = drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(size.height / 2),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
    )
}

data class FilterOption<T>(val value: T, val label: String, val accent: Boolean = false)

/** Selectable chips with counts (screens 04, 08). The selected chip is filled with `text`. */
@Composable
fun <T> FilterChipRow(options: List<FilterOption<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s),
    ) {
        options.forEach { o -> SelectChip(o.label, o.value == selected, onClick = { onSelect(o.value) }, accent = o.accent) }
    }
}

/** One selectable pill chip (also used for "Your label" single-select). */
@Composable
fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false, tall: Boolean = false, role: Role = Role.Tab) {
    val c = SotreusTheme.colors
    val shape = SotreusTheme.shapes.pill
    val border = when {
        selected -> c.text
        accent -> c.attentionLine
        else -> c.lineStrong
    }
    val fg = when {
        selected -> c.ink
        accent -> c.accent
        else -> c.textSoft
    }
    Box(
        modifier
            .heightIn(min = SotreusTheme.sizes.minTouch)
            .selectable(selected = selected, role = role, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = if (tall) 36.dp else SotreusTheme.sizes.chip)
                .then(if (selected) Modifier.background(c.text, shape) else Modifier)
                .border(1.dp, border, shape)
                .padding(horizontal = SotreusTheme.spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = (if (tall) SotreusTheme.typography.bodyS else SotreusTheme.typography.caption).copy(fontWeight = FontWeight.Medium), color = fg, maxLines = 1)
        }
    }
}

/** Live status pill with a haloed dot: OBSERVING, LISTENING, SCANNING, SIMULATED, PAUSED. */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, live: Boolean = true, filled: Boolean = true) {
    val c = SotreusTheme.colors
    val color = if (live) c.accent else c.textMuted
    Row(
        modifier
            .heightIn(min = 36.dp)
            .then(if (filled) Modifier.background(if (live) c.accentTint else c.surfaceHigh, SotreusTheme.shapes.pill) else Modifier)
            .padding(horizontal = if (filled) SotreusTheme.spacing.xl else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m),
    ) {
        Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
            if (live) Box(Modifier.size(15.dp).background(c.accent.copy(alpha = 0.18f), SotreusTheme.shapes.pill))
            Box(Modifier.size(7.dp).background(color, SotreusTheme.shapes.pill))
        }
        Text(text.uppercase(), style = SotreusTheme.typography.monoLabel, color = color, maxLines = 1)
    }
}
