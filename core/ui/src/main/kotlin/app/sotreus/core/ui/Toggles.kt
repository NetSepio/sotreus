package app.sotreus.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.theme.SotreusTheme

/** 2–3 option pill toggle; selected = `text` fill with `ink` text (screens 03, 04, 13). */
@Composable
fun <T> SegmentedToggle(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Row(
        modifier.fillMaxWidth().border(1.dp, c.lineMid, SotreusTheme.shapes.pill).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(SotreusTheme.shapes.pill)
                    .then(if (on) Modifier.background(c.text) else Modifier)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = SotreusTheme.typography.bodyS.copy(fontWeight = FontWeight.Medium), color = if (on) c.ink else c.textMuted)
            }
        }
    }
}

/** Underlined tabs (History, Compare). */
@Composable
fun <T> UnderlineTabs(tabs: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = SotreusTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            tabs.forEach { (value, label) ->
                val on = value == selected
                Column(
                    Modifier.width(IntrinsicSize.Max).heightIn(min = SotreusTheme.sizes.minTouch).selectable(selected = on, role = Role.Tab, onClick = { onSelect(value) }),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, style = SotreusTheme.typography.rowTitle.copy(fontSize = SotreusTheme.typography.rowTitle.fontSize * 0.965f), color = if (on) c.text else c.textMuted, modifier = Modifier.padding(bottom = 11.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).then(if (on) Modifier.background(c.accent) else Modifier))
                }
            }
        }
        RowDivider(strong = true)
    }
}
