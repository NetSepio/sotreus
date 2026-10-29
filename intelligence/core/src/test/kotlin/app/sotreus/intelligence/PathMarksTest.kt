package app.sotreus.intelligence

import app.sotreus.intelligence.PathMarks.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathMarksTest {
    private val path = listOf(PathPoint(30_000, 1.3, 2.3), PathPoint(10_000, 1.1, 2.1), PathPoint(20_000, 1.2, 2.2))

    @Test
    fun anEventSitsOnThePointTakenClosestInTime() {
        val placed = PathMarks.place(listOf(14_000L to "a", 16_000L to "b", 30_000L to "c"), path)
        assertEquals(listOf(1.1, 1.2, 1.3), placed.map { it.lat })
        assertEquals(listOf("a", "b", "c"), placed.map { it.item })
    }

    @Test
    fun eventsFarFromAnyPointAreLeftOff() {
        val placed = PathMarks.place(listOf(0L to "early", 200_000L to "late", 25_000L to "kept"), path, maxGapMs = 9_000)
        assertEquals(listOf("kept"), placed.map { it.item })
    }

    @Test
    fun noPathPlacesNothing() {
        assertTrue(PathMarks.place(listOf(1L to "a"), emptyList()).isEmpty())
    }
}
