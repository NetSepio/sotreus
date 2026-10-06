package app.sotreus.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme

/** Settings-style row: label, optional trailing value, chevron. At least 52 dp tall. */
@Composable
fun NavRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    showDivider: Boolean = true,
    valueAccent: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = SotreusTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = if (destructive) SotreusTheme.typography.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) else SotreusTheme.typography.body,
                color = if (destructive) colors.destructive else colors.text,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.m),
            ) {
                if (value != null) {
                    Text(value, style = SotreusTheme.typography.bodyS, color = if (valueAccent) colors.accent else colors.textMuted)
                }
                Icon(
                    imageVector = SotreusIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (showDivider) HorizontalDivider(thickness = 1.dp, color = colors.lineSubtle)
    }
}

/** Mono key/value row used in diagnostics and metadata tables. */
@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = SotreusTheme.spacing.s),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = SotreusTheme.typography.monoValue, color = SotreusTheme.colors.textDim)
        Text(value, style = SotreusTheme.typography.monoValue, color = SotreusTheme.colors.text)
    }
}

/** Panel with a dashed `lineStrong` border: used for diagnostics and "what this does not mean" boxes. */
@Composable
fun DashedPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val radius = SotreusTheme.radii.featureCard
    Column(
        modifier
            .fillMaxWidth()
            .dashedBorder(SotreusTheme.colors.lineStrong, radius)
            .padding(start = SotreusTheme.spacing.xxl, end = SotreusTheme.spacing.xxl, top = 14.dp, bottom = SotreusTheme.spacing.s),
        content = content,
    )
}

/** Solid-bordered panel on `surface`, as used by the sensor cards. */
@Composable
fun SolidPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = SotreusTheme.shapes.featureCard
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, SotreusTheme.colors.line, shape)
            .padding(start = SotreusTheme.spacing.xxl, end = SotreusTheme.spacing.xxl, top = 14.dp, bottom = SotreusTheme.spacing.s),
        content = content,
    )
}

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val strokeWidth = 1.dp.toPx()
    val inset = strokeWidth / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

@Preview(widthDp = 390)
@Composable
private fun RowsPreview() {
    SotreusTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NavRow("Diagnostics", onClick = {})
            DashedPanel {
                MonoLabel("Diagnostics", small = true)
                KeyValueRow("Android", "15 · API 35")
                KeyValueRow("Database schema", "v1")
            }
        }
    }
}
