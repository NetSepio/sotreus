package app.sotreus.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalSotreusColors = staticCompositionLocalOf { NightInstrumentColors }
val LocalSotreusTypography = staticCompositionLocalOf { NightInstrumentTypography }
val LocalSotreusRadii = staticCompositionLocalOf { SotreusRadii() }
val LocalSotreusShapes = staticCompositionLocalOf { SotreusShapes.from(SotreusRadii()) }
val LocalSotreusSpacing = staticCompositionLocalOf { SotreusSpacing() }
val LocalSotreusSizes = staticCompositionLocalOf { SotreusSizes() }

/** Token accessors for screens. Screens use these, never raw hex or ad-hoc sizes. */
object SotreusTheme {
    val colors: SotreusColors
        @Composable @ReadOnlyComposable get() = LocalSotreusColors.current
    val typography: SotreusTypography
        @Composable @ReadOnlyComposable get() = LocalSotreusTypography.current
    val radii: SotreusRadii
        @Composable @ReadOnlyComposable get() = LocalSotreusRadii.current
    val shapes: SotreusShapes
        @Composable @ReadOnlyComposable get() = LocalSotreusShapes.current
    val spacing: SotreusSpacing
        @Composable @ReadOnlyComposable get() = LocalSotreusSpacing.current
    val sizes: SotreusSizes
        @Composable @ReadOnlyComposable get() = LocalSotreusSizes.current
}

/**
 * The "Night Instrument" theme. Dark only, no dynamic colour. Material 3 is themed from the same
 * tokens so stock components (switches, radio buttons, ripples) match the design.
 */
@Composable
fun SotreusTheme(content: @Composable () -> Unit) {
    val colors = NightInstrumentColors
    val type = NightInstrumentTypography
    val radii = SotreusRadii()
    val shapes = SotreusShapes.from(radii)

    CompositionLocalProvider(
        LocalSotreusColors provides colors,
        LocalSotreusTypography provides type,
        LocalSotreusRadii provides radii,
        LocalSotreusShapes provides shapes,
        LocalSotreusSpacing provides SotreusSpacing(),
        LocalSotreusSizes provides SotreusSizes(),
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(),
            typography = type.toMaterialTypography(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(radii.glyphWell),
                small = RoundedCornerShape(radii.tile),
                medium = RoundedCornerShape(radii.listCard),
                large = RoundedCornerShape(radii.panel),
                extraLarge = RoundedCornerShape(radii.hero),
            ),
            content = content,
        )
    }
}

private fun SotreusColors.toMaterialColorScheme() = darkColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accentTint,
    onPrimaryContainer = accent,
    secondary = text,
    onSecondary = ink,
    secondaryContainer = accentNavPill,
    onSecondaryContainer = accent,
    tertiary = textSoft,
    onTertiary = ink,
    background = ink,
    onBackground = text,
    surface = ink,
    onSurface = text,
    surfaceVariant = surfaceHigh,
    onSurfaceVariant = textMuted,
    surfaceTint = ink,
    surfaceContainerLowest = ink,
    surfaceContainerLow = surface,
    surfaceContainer = surface,
    surfaceContainerHigh = surfaceRaised,
    surfaceContainerHighest = surfaceHigh,
    inverseSurface = text,
    inverseOnSurface = ink,
    inversePrimary = accent,
    error = destructive,
    onError = ink,
    outline = lineStrong,
    outlineVariant = line,
    scrim = ink,
)

private fun SotreusTypography.toMaterialTypography() = Typography(
    displayLarge = displayXL,
    displayMedium = displayL,
    displaySmall = displayM,
    headlineLarge = title,
    headlineMedium = titleS,
    headlineSmall = titleS,
    titleLarge = bodyL,
    titleMedium = rowTitle,
    titleSmall = rowTitle,
    bodyLarge = bodyL,
    bodyMedium = body,
    bodySmall = bodyS,
    labelLarge = button,
    labelMedium = caption,
    labelSmall = monoLabelS,
)
