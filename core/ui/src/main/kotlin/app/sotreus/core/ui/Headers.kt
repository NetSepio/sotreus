package app.sotreus.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.icon.SotreusIcons
import app.sotreus.core.designsystem.theme.SotreusTheme

/** Serif screen title for tab roots and detail screens, with an optional lead paragraph. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, lead: String? = null) {
    Column(modifier) {
        Text(
            text = title,
            style = SotreusTheme.typography.title,
            color = SotreusTheme.colors.text,
            modifier = Modifier.semantics { heading() },
        )
        if (lead != null) {
            Spacer(Modifier.size(SotreusTheme.spacing.l))
            Text(lead, style = SotreusTheme.typography.bodyL, color = SotreusTheme.colors.textMuted)
        }
    }
}

/** Uppercase mono section label. */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text = text.uppercase(),
        style = if (small) SotreusTheme.typography.monoLabelS else SotreusTheme.typography.monoLabel,
        color = SotreusTheme.colors.textDim,
        modifier = modifier,
    )
}

/** 44 dp back button, nudged left so its glyph aligns with the screen edge, plus optional trailing content. */
@Composable
fun BackTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(SotreusTheme.sizes.minTouch).offset(x = (-10).dp),
        ) {
            Icon(
                imageVector = SotreusIcons.Back,
                contentDescription = stringResource(R.string.core_ui_back),
                tint = SotreusTheme.colors.text,
                modifier = Modifier.size(SotreusTheme.sizes.iconSize),
            )
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}
