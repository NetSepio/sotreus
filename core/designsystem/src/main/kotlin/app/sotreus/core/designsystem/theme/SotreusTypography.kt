package app.sotreus.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.sotreus.core.designsystem.R

/** Bundled in `res/font` (SIL OFL). No downloadable fonts: the app must work offline. */
val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

/**
 * Type scale from `design/tokens.json`. Mono labels are rendered uppercase by the component
 * that uses them; a [TextStyle] cannot change case.
 */
@Immutable
data class SotreusTypography(
    val displayXL: TextStyle,
    val displayL: TextStyle,
    val displayM: TextStyle,
    val title: TextStyle,
    val titleS: TextStyle,
    val bodyL: TextStyle,
    val body: TextStyle,
    val rowTitle: TextStyle,
    val bodyS: TextStyle,
    val caption: TextStyle,
    val buttonPrimary: TextStyle,
    val button: TextStyle,
    val monoLabel: TextStyle,
    val monoLabelS: TextStyle,
    val monoValue: TextStyle,
    val monoTimer: TextStyle,
) {
    internal fun byTokenName(): Map<String, TextStyle> = mapOf(
        "displayXL" to displayXL,
        "displayL" to displayL,
        "displayM" to displayM,
        "title" to title,
        "titleS" to titleS,
        "bodyL" to bodyL,
        "body" to body,
        "rowTitle" to rowTitle,
        "bodyS" to bodyS,
        "caption" to caption,
        "buttonPrimary" to buttonPrimary,
        "button" to button,
        "monoLabel" to monoLabel,
        "monoLabelS" to monoLabelS,
        "monoValue" to monoValue,
        "monoTimer" to monoTimer,
    )
}

// CSS-like line boxes: the mocks are HTML, so centre text in its line and keep the full height.
private val CssLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    family: FontFamily,
    weight: Int,
    sizeSp: Float,
    lineHeight: Float? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = sizeSp.sp,
    lineHeight = lineHeight?.let { (sizeSp * it).sp } ?: TextUnit.Unspecified,
    letterSpacing = letterSpacing,
    lineHeightStyle = CssLineHeight,
)

val NightInstrumentTypography = SotreusTypography(
    displayXL = style(InstrumentSerif, 400, 72f, 1.0f),
    displayL = style(InstrumentSerif, 400, 48f, 0.98f),
    displayM = style(InstrumentSerif, 400, 44f, 1.0f),
    title = style(InstrumentSerif, 400, 40f, 1.0f),
    titleS = style(InstrumentSerif, 400, 34f, 1.05f),
    bodyL = style(Geist, 400, 16f, 1.55f),
    body = style(Geist, 400, 15f, 1.55f),
    rowTitle = style(Geist, 500, 14.5f, 1.3f),
    bodyS = style(Geist, 400, 13f, 1.5f),
    caption = style(Geist, 400, 12f, 1.45f),
    buttonPrimary = style(Geist, 600, 16f),
    button = style(Geist, 500, 15f),
    monoLabel = style(GeistMono, 400, 11f, letterSpacing = 0.14.em),
    monoLabelS = style(GeistMono, 400, 10f, letterSpacing = 0.14.em),
    monoValue = style(GeistMono, 400, 12f),
    monoTimer = style(GeistMono, 500, 44f),
)
