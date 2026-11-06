package app.sotreus.feature.edge

import app.sotreus.edge.location.GeoFix
import app.sotreus.edge.location.PlacementAverager
import app.sotreus.edge.location.QualityLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Averaging that ends outside the limit keeps its estimate, with the accuracy it really has. */
class PositionStepTest {
    private fun wide(): PlacementAverager.Estimate {
        val a = PlacementAverager(QualityLimits())
        repeat(8) { a.add(GeoFix(atMs = it * 1_000L, lat = 12.9716 + it * 5e-5, lon = 77.5946, accuracyM = 40f)) }
        return a.estimate()!!
    }

    @Test fun anUnacceptedEstimateStaysOnScreen() {
        val e = wide()
        assertFalse(e.accepted)
        val step = EdgeViewModel.averagingEnded(e)
        assertTrue(step is PositionStep.Estimated)
        val ui = PositionUi(step)
        assertEquals(e, ui.estimate)
        assertEquals(e.accuracyM, ui.estimate!!.toPlacement(1, anyway = true)!!.accuracyM!!, 0f)
        assertNull(ui.estimate!!.toPlacement(1))
    }

    @Test fun noFixesSaysSo() {
        assertEquals(PositionStep.NoFixes, EdgeViewModel.averagingEnded(null))
        assertNull(PositionUi(PositionStep.NoFixes).estimate)
    }
}
