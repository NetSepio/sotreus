package app.sotreus.core.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.ProximityBand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Crowded dots (a few pixels apart at 1×) become separately tappable after pinch-zooming, and
 * zooming out never goes below the full view.
 */
@RunWith(AndroidJUnit4::class)
class ProximityBandsZoomTest {
    @get:Rule val rule = createComposeRule()

    private val a = BandDot("a", ProximityBand.MID, angle = 0.0, radial = 0.50, glyph = GlyphSpec(GlyphShape.BLE, GlyphTone.FAMILIAR))
    private val b = BandDot("b", ProximityBand.MID, angle = 0.0, radial = 0.56, glyph = GlyphSpec(GlyphShape.BLE, GlyphTone.FAMILIAR))
    private var tapped: String? = null

    /** A single tap is reported only after the double-tap window passes. */
    private fun settle() {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    private fun show() {
        rule.setContent {
            SotreusTheme {
                ProximityBands(
                    allDots = listOf(a, b), labels = BandLabels("Near", "Mid", "Far", "You"),
                    contentDescription = "bands", onDotClick = { tapped = it }, modifier = Modifier.width(350.dp),
                )
            }
        }
    }

    @Test
    fun pinchSeparatesCrowdedDots() {
        show()
        val node = rule.onNodeWithContentDescription("bands")
        val bounds = node.fetchSemanticsNode().size
        val w = bounds.width.toFloat()
        val h = bounds.height.toFloat()
        val density = rule.density.density
        val rFar = minOf(h / 2 - 8 * density, w / 2 - 8 * density)
        fun pos(d: BandDot, scale: Float): Offset {
            val near = rFar * 50f / 140f * scale
            val mid = rFar * 95f / 140f * scale
            val r = near + (mid - near) * d.radial.toFloat()
            return Offset(w / 2 + r, h / 2) // angle 0, zoom anchored at the centre
        }
        // At 1× the two dots are within one finger of each other.
        assert(pos(b, 1f).x - pos(a, 1f).x < 12 * density)

        // Pinch out over the crowded spot, as a person would. Zoom is anchored under the fingers,
        // so the dots spread around that point: q = m + (p - m) × scale.
        val pa = pos(a, 1f)
        val pb = pos(b, 1f)
        val m = Offset((pa.x + pb.x) / 2, (pa.y + pb.y) / 2)
        node.performTouchInput {
            pinch(m - Offset(0f, 10f), m - Offset(0f, 200f), m + Offset(0f, 10f), m + Offset(0f, 200f), durationMillis = 400)
        }
        settle()
        // Requested 20×; the adaptive maximum stops once the closest dots are 28 dp apart.
        val scale = minOf(20f, bandsMaxZoom(listOf(a, b), w, h, density))
        fun zoomed(p: Offset) = m + (p - m) * scale
        assert(zoomed(pb).x - zoomed(pa).x >= 27.5f * density)
        tapped = null
        node.performTouchInput { click(zoomed(pa)) }
        settle()
        assertEquals("a", tapped)
        tapped = null
        node.performTouchInput { click(zoomed(pb)) }
        settle()
        assertEquals("b", tapped)

        // Double-tap returns to the full view.
        node.performTouchInput { doubleClick(m) }
        settle()
        tapped = null
        node.performTouchInput { click(pa) }
        settle()
        assert(tapped == "a" || tapped == "b")
    }

    @Test
    fun zoomingOutStopsAtTheFullView() {
        show()
        val node = rule.onNodeWithContentDescription("bands")
        node.performTouchInput {
            pinch(center - Offset(300f, 0f), center - Offset(10f, 0f), center + Offset(300f, 0f), center + Offset(10f, 0f), durationMillis = 400)
        }
        rule.waitForIdle()
        // Still at 1×: a dot is tappable at its unzoomed position.
        val bounds = node.fetchSemanticsNode().size
        val density = rule.density.density
        val w = bounds.width.toFloat()
        val h = bounds.height.toFloat()
        val rFar = minOf(h / 2 - 8 * density, w / 2 - 8 * density)
        val near = rFar * 50f / 140f
        val mid = rFar * 95f / 140f
        tapped = null
        node.performTouchInput { click(Offset(w / 2 + near + (mid - near) * 0.5f, h / 2)) }
        settle()
        assert(tapped == "a" || tapped == "b")
    }
}
