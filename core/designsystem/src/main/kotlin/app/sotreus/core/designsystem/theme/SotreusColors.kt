package app.sotreus.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * "Night Instrument" palette, mirrored from `design/tokens.json` (dark only in V1).
 *
 * Amber ([accent] / [sensed]) means sensed, needs a look, or primary action. There is no threat
 * colour. [familiarGlyph] is for glyphs only; it fails text contrast. [reservedV11Network] and
 * [reservedV11Predicted] are defined for V1.1 provenance and must not be used in V1.
 */
@Immutable
data class SotreusColors(
    val ink: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHigh: Color,
    val surfaceBanner: Color,
    val lineSubtle: Color,
    val line: Color,
    val lineNav: Color,
    val lineMid: Color,
    val lineStrong: Color,
    val text: Color,
    val textSoft: Color,
    val textMuted: Color,
    val textDim: Color,
    val accent: Color,
    val sensed: Color,
    val accentTint: Color,
    val accentNavPill: Color,
    val attentionSurface: Color,
    val attentionSurfaceLarge: Color,
    val attentionLine: Color,
    val attentionDivider: Color,
    val familiarGlyph: Color,
    val destructive: Color,
    val onAccent: Color,
    val reservedV11Network: Color,
    val reservedV11Predicted: Color,
) {
    /** Token name (as in `tokens.json`) to colour. Used by the token parity test. */
    internal fun byTokenName(): Map<String, Color> = mapOf(
        "ink" to ink,
        "surface" to surface,
        "surfaceRaised" to surfaceRaised,
        "surfaceHigh" to surfaceHigh,
        "surfaceBanner" to surfaceBanner,
        "lineSubtle" to lineSubtle,
        "line" to line,
        "lineNav" to lineNav,
        "lineMid" to lineMid,
        "lineStrong" to lineStrong,
        "text" to text,
        "textSoft" to textSoft,
        "textMuted" to textMuted,
        "textDim" to textDim,
        "accent" to accent,
        "sensed" to sensed,
        "accentTint" to accentTint,
        "accentNavPill" to accentNavPill,
        "attentionSurface" to attentionSurface,
        "attentionSurfaceLarge" to attentionSurfaceLarge,
        "attentionLine" to attentionLine,
        "attentionDivider" to attentionDivider,
        "familiarGlyph" to familiarGlyph,
        "destructive" to destructive,
        "onAccent" to onAccent,
        "reservedV11Network" to reservedV11Network,
        "reservedV11Predicted" to reservedV11Predicted,
    )
}

private val Amber = Color(0xFFF2B33D)

val NightInstrumentColors = SotreusColors(
    ink = Color(0xFF0B0E13),
    surface = Color(0xFF0D1117),
    surfaceRaised = Color(0xFF10141B),
    surfaceHigh = Color(0xFF121721),
    surfaceBanner = Color(0xFF1A1F28),
    lineSubtle = Color(0xFF161B23),
    line = Color(0xFF1E2530),
    lineNav = Color(0xFF1A2029),
    lineMid = Color(0xFF232A36),
    lineStrong = Color(0xFF2F3846),
    text = Color(0xFFE9E6DF),
    textSoft = Color(0xFFC9CBCF),
    textMuted = Color(0xFFA3A7AE),
    textDim = Color(0xFF7C828C),
    accent = Amber,
    sensed = Amber,
    accentTint = Color(0x1FF2B33D),
    accentNavPill = Color(0x29F2B33D),
    attentionSurface = Color(0xFF15130E),
    attentionSurfaceLarge = Color(0xFF13110C),
    attentionLine = Color(0xFF3B3220),
    attentionDivider = Color(0xFF2A2418),
    familiarGlyph = Color(0xFF5B626D),
    destructive = Color(0xFFF59A8C),
    onAccent = Color(0xFF0B0E13),
    reservedV11Network = Color(0xFF6CB8F0),
    reservedV11Predicted = Color(0xFFB9A6FF),
)
