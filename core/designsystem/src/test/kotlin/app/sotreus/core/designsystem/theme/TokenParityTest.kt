package app.sotreus.core.designsystem.theme

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the Compose theme in lockstep with the design tokens. The module carries its own copy of the
 * token spec (`src/test/resources/sotreus-tokens.json`) so this runs in CI; where the handoff kit's
 * `design/tokens.json` is present locally, the copy must match it too.
 */
class TokenParityTest {

    private val specText: String by lazy {
        requireNotNull(javaClass.classLoader?.getResource("sotreus-tokens.json")) { "sotreus-tokens.json missing from test resources" }.readText()
    }

    private val tokens: JsonObject by lazy { Json.parseToJsonElement(specText).jsonObject }

    @Test
    fun bundledSpecMatchesHandoffKitWhenPresent() {
        val handoff = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "design/tokens.json") }
            .firstOrNull { it.isFile }
            ?: return // The handoff kit is not checked in; nothing to compare against (e.g. CI).
        assertEquals(
            "core/designsystem/src/test/resources/sotreus-tokens.json is out of date with design/tokens.json",
            Json.parseToJsonElement(handoff.readText()),
            Json.parseToJsonElement(specText),
        )
    }

    @Test
    fun everyColorTokenIsMirrored() {
        val kotlin = NightInstrumentColors.byTokenName()
        val json = tokens.getValue("color").jsonObject
        assertEquals("color token names", json.keys, kotlin.keys)
        json.forEach { (name, value) ->
            assertEquals("color.$name", parseHex(value.jsonPrimitive.content), kotlin.getValue(name).toArgb())
        }
    }

    @Test
    fun everyTypeTokenIsMirrored() {
        val kotlin = NightInstrumentTypography.byTokenName()
        val json = tokens.getValue("type").jsonObject
        assertEquals("type token names", json.keys, kotlin.keys)
        json.forEach { (name, element) ->
            val spec = element.jsonObject
            val style = kotlin.getValue(name)
            val size = spec.getValue("size").jsonPrimitive.float
            assertEquals("type.$name size", size, style.fontSize.value, 0.001f)
            assertEquals("type.$name weight", FontWeight(spec.getValue("weight").jsonPrimitive.int), style.fontWeight)
            spec["lineHeight"]?.let {
                assertEquals("type.$name lineHeight", size * it.jsonPrimitive.float, style.lineHeight.value, 0.01f)
            }
            spec["letterSpacingEm"]?.let {
                assertTrue("type.$name letterSpacing in em", style.letterSpacing.isEm)
                assertEquals("type.$name letterSpacing", it.jsonPrimitive.float, style.letterSpacing.value, 0.001f)
            }
            val family = when (spec.getValue("font").jsonPrimitive.content) {
                "display" -> InstrumentSerif
                "body" -> Geist
                "mono" -> GeistMono
                else -> error("unknown font role in type.$name")
            }
            assertEquals("type.$name family", family, style.fontFamily)
        }
    }

    @Test
    fun radiiSpacingAndSizesAreMirrored() {
        val radii = SotreusRadii()
        assertDpTokens(
            "radius",
            mapOf(
                "glyphWell" to radii.glyphWell, "tile" to radii.tile, "listCard" to radii.listCard,
                "panel" to radii.panel, "featureCard" to radii.featureCard, "hero" to radii.hero,
                "pill" to radii.pill,
            ),
        )

        val spacing = SotreusSpacing()
        assertDpTokens(
            "spacing",
            mapOf(
                "screenH" to spacing.screenH, "screenTop" to spacing.screenTop,
                "screenBottom" to spacing.screenBottom, "section" to spacing.section,
                "xs" to spacing.xs, "s" to spacing.s, "m" to spacing.m, "l" to spacing.l,
                "xl" to spacing.xl, "xxl" to spacing.xxl,
            ),
        )

        val sizes = SotreusSizes()
        val json = tokens.getValue("size").jsonObject
        assertEquals(json.getValue("minTouch").jsonPrimitive.float.dp, sizes.minTouch)
        assertEquals(json.getValue("buttonPrimary").jsonPrimitive.float.dp, sizes.buttonPrimary)
        assertEquals(json.getValue("buttonSecondary").jsonPrimitive.float.dp, sizes.buttonSecondary)
        assertEquals(json.getValue("chip").jsonPrimitive.float.dp, sizes.chip)
        assertEquals(json.getValue("navItem").jsonPrimitive.float.dp, sizes.navItem)
        val pill = json.getValue("navPill").jsonObject
        assertEquals(pill.getValue("w").jsonPrimitive.float.dp, sizes.navPillWidth)
        assertEquals(pill.getValue("h").jsonPrimitive.float.dp, sizes.navPillHeight)
        assertEquals(json.getValue("iconStroke").jsonPrimitive.float, sizes.iconStroke, 0.001f)
        assertEquals(json.getValue("iconSize").jsonPrimitive.float.dp, sizes.iconSize)
    }

    private fun assertDpTokens(group: String, kotlin: Map<String, Dp>) {
        val json = tokens.getValue(group).jsonObject
        assertEquals("$group token names", json.keys, kotlin.keys)
        json.forEach { (name, value) ->
            assertEquals("$group.$name", value.jsonPrimitive.float.dp, kotlin.getValue(name))
        }
    }

    /** `#RRGGBB` or `#AARRGGBB` to an ARGB int. */
    private fun parseHex(hex: String): Int {
        val digits = hex.removePrefix("#")
        val argb = if (digits.length == 6) "FF$digits" else digits
        return argb.toLong(16).toInt()
    }
}
