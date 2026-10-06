/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit e71b9b2).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 */
package app.sotreus.intelligence.fieldwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarPlotTest {
    private val maxR = 1000f

    @Test
    fun loudSitsNearCenter() {
        val r30 = RadarPlot.radius(-30, maxR)
        val r100 = RadarPlot.radius(-100, maxR)
        assertEquals(120f, r30, 0.5f)
        assertEquals(1000f, r100, 0.5f)
        assertTrue(r30 < RadarPlot.radius(-40, maxR))
        assertTrue(RadarPlot.radius(-60, maxR) < r100)
    }

    @Test
    fun zoomSpreadsInnerRadiosAndPushesWeakOffDisc() {
        val inner = RadarPlot.radius(-40, maxR, zoom = 1f)
        val innerZ = RadarPlot.radius(-40, maxR, zoom = 2f)
        assertEquals(inner * 2f, innerZ, 0.5f)
        assertTrue(RadarPlot.onDisc(-100, maxR, zoom = 1f))
        assertFalse(RadarPlot.onDisc(-100, maxR, zoom = 2f))
        assertTrue(RadarPlot.onDisc(-40, maxR, zoom = 2f))
    }

    @Test
    fun zoomIsClamped() {
        assertEquals(RadarPlot.radius(-50, maxR, 1f), RadarPlot.radius(-50, maxR, 0.2f), 0.01f)
        assertEquals(RadarPlot.radius(-50, maxR, 4f), RadarPlot.radius(-50, maxR, 9f), 0.01f)
    }
}
