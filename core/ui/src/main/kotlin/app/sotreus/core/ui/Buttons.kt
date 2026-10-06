package app.sotreus.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.theme.SotreusTheme

/** Amber pill: the one primary action on a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = SotreusTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = SotreusTheme.sizes.buttonPrimary),
        shape = SotreusTheme.shapes.pill,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.surfaceHigh, disabledContentColor = c.textDim),
    ) { Text(text, style = SotreusTheme.typography.buttonPrimary) }
}

/** Light pill (`text` fill): ending a session, Done, neutral confirmations. */
@Composable
fun LightButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null) {
    val c = SotreusTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = SotreusTheme.sizes.buttonPrimary),
        shape = SotreusTheme.shapes.pill,
        colors = ButtonDefaults.buttonColors(containerColor = c.text, contentColor = c.ink, disabledContainerColor = c.surfaceHigh, disabledContentColor = c.textDim),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.l), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Text(text, style = SotreusTheme.typography.button.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/** Outlined pill on `lineStrong`. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = SotreusTheme.sizes.buttonSecondary,
    fillWidth: Boolean = true,
    strong: Boolean = false,
) {
    val c = SotreusTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).heightIn(min = minHeight),
        shape = SotreusTheme.shapes.pill,
        border = BorderStroke(1.dp, c.lineStrong),
        contentPadding = PaddingValues(horizontal = SotreusTheme.spacing.xxl),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (strong) c.text else c.textSoft, disabledContentColor = c.textDim),
    ) { Text(text, style = SotreusTheme.typography.button, textAlign = TextAlign.Center) }
}

/** Compact outlined pill (36 dp tall, 44 dp touch area) for top-bar actions like Rename, Export, Add note. */
@Composable
fun CompactButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    GhostButton(text, onClick, modifier.heightIn(min = SotreusTheme.sizes.minTouch), enabled, minHeight = 36.dp, fillWidth = false)
}

/** Quiet text action ("Not now", "Continue local only"). */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = SotreusTheme.sizes.buttonSecondary),
        shape = SotreusTheme.shapes.pill,
        colors = ButtonDefaults.textButtonColors(contentColor = SotreusTheme.colors.textMuted),
    ) { Text(text, style = SotreusTheme.typography.button) }
}

/** Destructive text action. Always paired with a confirm dialog that says exactly what is removed. */
@Composable
fun DestructiveTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = SotreusTheme.sizes.minTouch),
        shape = SotreusTheme.shapes.pill,
        colors = ButtonDefaults.textButtonColors(contentColor = SotreusTheme.colors.destructive),
    ) { Text(text, style = SotreusTheme.typography.button) }
}

/** Inline underlined link-style action ("View all", "See all", "Compare sits"). */
@Composable
fun InlineLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = SotreusTheme.sizes.minTouch),
        contentPadding = PaddingValues(horizontal = SotreusTheme.spacing.xs),
        colors = ButtonDefaults.textButtonColors(contentColor = SotreusTheme.colors.textSoft),
    ) {
        Text(text, style = SotreusTheme.typography.bodyS.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))
    }
}
