package app.sotreus.core.designsystem.icon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sotreus.core.designsystem.theme.SotreusTheme

/** The Sotreus mark from screen 01: a ringed sphere with an amber core (64-unit artboard). */
@Composable
fun SotreusLogo(modifier: Modifier = Modifier, size: Dp = 32.dp, contentDescription: String? = null) {
    val c = SotreusTheme.colors
    Canvas(
        modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        val u = this.size.width / 64f
        val center = Offset(32 * u, 32 * u)
        val ring = Stroke(width = 4 * u, cap = StrokeCap.Round)
        val oval = Offset(3 * u, 22 * u) to Size(58 * u, 20 * u)
        rotate(-24f, center) { drawArc(c.text, 180f, 180f, false, oval.first, oval.second, style = ring) }
        drawCircle(c.ink, 18 * u, center)
        drawCircle(c.text, 18 * u, center, style = Stroke(5 * u))
        rotate(-24f, center) { drawArc(c.text, 0f, 180f, false, oval.first, oval.second, style = ring) }
        drawCircle(c.accent, 7.5f * u, center)
    }
}
