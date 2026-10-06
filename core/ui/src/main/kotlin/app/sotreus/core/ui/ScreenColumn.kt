package app.sotreus.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.sotreus.core.designsystem.theme.SotreusTheme

/**
 * Standard screen body: token screen padding, section gaps, vertical scroll. System insets are
 * applied by the app scaffold, not here. The column is at least the viewport tall, so a
 * `Modifier.weight` spacer can push a footer to the bottom.
 */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    top: Dp = SotreusTheme.spacing.screenTop,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = SotreusTheme.spacing
    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(start = spacing.screenH, end = spacing.screenH, top = top, bottom = spacing.screenBottom),
            verticalArrangement = Arrangement.spacedBy(spacing.section),
            content = content,
        )
    }
}
