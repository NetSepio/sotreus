package app.sotreus.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.theme.SotreusTheme

/**
 * Entity list row (screens 04, 08, 12): glyph, title + subtitle, trailing value and chip.
 * Stale rows render at 55 % opacity with a dashed glyph.
 */
@Composable
fun EntityRow(
    title: String,
    subtitle: String,
    glyph: GlyphSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingValue: String? = null,
    chip: (@Composable () -> Unit)? = null,
    wellGlyph: Boolean = true,
    trailingValueStrong: Boolean = false,
    trailingCaption: String? = null,
) {
    val c = SotreusTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp)
                .alpha(if (glyph.stale) 0.55f else 1f).padding(vertical = 9.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl),
        ) {
            if (wellGlyph) GlyphWell(glyph) else Box(Modifier.padding(horizontal = 6.dp)) { Glyph(glyph) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = SotreusTheme.typography.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = SotreusTheme.typography.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (trailingValue != null) {
                    Text(
                        trailingValue,
                        style = if (trailingValueStrong) SotreusTheme.typography.rowTitle else SotreusTheme.typography.monoValue.copy(fontSize = SotreusTheme.typography.monoValue.fontSize * 0.92f),
                        color = if (trailingValueStrong) c.text else c.text,
                    )
                }
                trailingCaption?.let { Text(it.uppercase(), style = SotreusTheme.typography.monoLabelS.copy(letterSpacing = SotreusTheme.typography.monoValue.letterSpacing), color = c.textDim) }
                chip?.invoke()
            }
        }
        RowDivider()
    }
}

/** Title + subtitle + trailing slot (sessions, friends, proof history, encounter rows). */
@Composable
fun InfoRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    titleStyleBody: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = SotreusTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = 52.dp).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = if (titleStyleBody) SotreusTheme.typography.body else SotreusTheme.typography.rowTitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = SotreusTheme.typography.caption, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            trailing()
        }
        if (showDivider) RowDivider()
    }
}

/** Two-column row: label left, muted value right (Encountered at, Normally present). */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, showDivider: Boolean = true, onClick: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 44.dp).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.97f), color = SotreusTheme.colors.text, modifier = Modifier.weight(1f))
            Text(value, style = SotreusTheme.typography.bodyS, color = SotreusTheme.colors.textMuted)
        }
        if (showDivider) RowDivider()
    }
}

/** Title + subtitle + real Switch semantics, amber when on (07, 09, 10, 14, 15). */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    bordered: Boolean = true,
    enabled: Boolean = true,
) {
    val c = SotreusTheme.colors
    Column(modifier.fillMaxWidth()) {
        if (bordered) RowDivider(strong = true)
        Row(
            Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = 56.dp).padding(vertical = SotreusTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xxl),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = SotreusTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = c.text)
                subtitle?.let { Text(it, style = SotreusTheme.typography.caption, color = c.textMuted) }
            }
            SotreusSwitch(checked = checked, enabled = enabled)
        }
        if (bordered) RowDivider(strong = true)
    }
}

/** The switch visual. Semantics come from the parent row's toggleable. */
@Composable
fun SotreusSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val c = SotreusTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.accent, checkedThumbColor = c.ink, checkedBorderColor = c.accent,
            uncheckedTrackColor = c.surfaceHigh, uncheckedThumbColor = c.textDim, uncheckedBorderColor = c.lineStrong,
        ),
    )
}

data class RadioOption<T>(val value: T, val label: String, val enabled: Boolean = true)

/** Native radio semantics with the amber accent (screens 14, S3). */
@Composable
fun <T> RadioGroupRows(options: List<RadioOption<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Column(modifier.fillMaxWidth().selectableGroup()) {
        options.forEachIndexed { i, o ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = o.value == selected, enabled = o.enabled, role = Role.RadioButton, onClick = { onSelect(o.value) })
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.xl),
            ) {
                RadioButton(
                    selected = o.value == selected,
                    onClick = null,
                    enabled = o.enabled,
                    colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.textMuted, disabledUnselectedColor = c.lineStrong),
                )
                Text(o.label, style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize), color = if (o.enabled) c.text else c.textDim)
            }
            if (i < options.lastIndex) RowDivider()
        }
    }
}

data class ReasonItem(val title: String, val detail: String?, val strong: Boolean)

/** Dot + bold reason + muted detail (screen 05). */
@Composable
fun ReasonList(items: List<ReasonItem>, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(vertical = SotreusTheme.spacing.m), horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                Box(Modifier.padding(top = 7.dp).size(6.dp).background(if (r.strong) c.accent else c.familiarGlyph, SotreusTheme.shapes.pill))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = c.text)) { append(r.title) }
                        r.detail?.let { withStyle(SpanStyle(color = c.textMuted)) { append(" · $it") } }
                    },
                    style = SotreusTheme.typography.body.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f),
                )
            }
            if (i < items.lastIndex) RowDivider()
        }
    }
}

/** Labelled 4 dp bars for attention inputs (screen 05). */
@Composable
fun InputBars(title: String, bars: List<Triple<String, Float, Boolean>>, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    SotreusCard(modifier, style = CardStyle.SURFACE) {
        MonoLabel(title, small = true, modifier = Modifier.padding(bottom = SotreusTheme.spacing.m))
        Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m)) {
            bars.forEach { (label, value, neutral) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l)) {
                    Text(label, style = SotreusTheme.typography.bodyS, color = c.textSoft, modifier = Modifier.width(110.dp))
                    BarTrack(value, Modifier.weight(1f), color = if (neutral) c.textSoft else c.accent)
                }
            }
        }
    }
}
