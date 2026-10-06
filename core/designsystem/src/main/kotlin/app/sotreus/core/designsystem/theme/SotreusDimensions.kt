package app.sotreus.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner radii from `design/tokens.json`. Pills (buttons, chips, inputs) are fully rounded. */
@Immutable
data class SotreusRadii(
    val glyphWell: Dp = 10.dp,
    val tile: Dp = 12.dp,
    val listCard: Dp = 14.dp,
    val panel: Dp = 16.dp,
    val featureCard: Dp = 20.dp,
    val hero: Dp = 24.dp,
    val pill: Dp = 999.dp,
)

@Immutable
data class SotreusShapes(
    val glyphWell: Shape,
    val tile: Shape,
    val listCard: Shape,
    val panel: Shape,
    val featureCard: Shape,
    val hero: Shape,
    val pill: Shape,
) {
    companion object {
        fun from(radii: SotreusRadii) = SotreusShapes(
            glyphWell = RoundedCornerShape(radii.glyphWell),
            tile = RoundedCornerShape(radii.tile),
            listCard = RoundedCornerShape(radii.listCard),
            panel = RoundedCornerShape(radii.panel),
            featureCard = RoundedCornerShape(radii.featureCard),
            hero = RoundedCornerShape(radii.hero),
            pill = RoundedCornerShape(percent = 50),
        )
    }
}

/** Spacing scale. Screen padding is [screenH] horizontal, [screenTop] top, [screenBottom] bottom. */
@Immutable
data class SotreusSpacing(
    val screenH: Dp = 20.dp,
    val screenTop: Dp = 24.dp,
    val screenBottom: Dp = 28.dp,
    val section: Dp = 18.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 6.dp,
    val m: Dp = 8.dp,
    val l: Dp = 10.dp,
    val xl: Dp = 12.dp,
    val xxl: Dp = 16.dp,
)

/** Component sizes. Every tap target is at least [minTouch]. */
@Immutable
data class SotreusSizes(
    val minTouch: Dp = 44.dp,
    val buttonPrimary: Dp = 52.dp,
    val buttonSecondary: Dp = 48.dp,
    val chip: Dp = 32.dp,
    val navItem: Dp = 56.dp,
    val navPillWidth: Dp = 56.dp,
    val navPillHeight: Dp = 30.dp,
    val iconSize: Dp = 22.dp,
    /** Stroke width of inline vector icons, in viewport units of a 24-unit icon. */
    val iconStroke: Float = 1.6f,
)
