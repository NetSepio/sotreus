package app.sotreus.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceVisitsTest {
    private val gap = 30 * 60_000L
    private fun s(min: Long, id: String = "ble:a") = PlaceVisits.Sighting(min * 60_000L, id)

    @Test fun nothingStoredNoVisits() = assertTrue(PlaceVisits.from(emptyList(), gap).isEmpty())

    @Test fun aGapUnderTheLimitContinuesTheVisit() {
        val v = PlaceVisits.from(listOf(s(0), s(10), s(39)), gap)
        assertEquals(1, v.size)
        assertEquals(0L until 39 * 60_000L + 1, v[0].startMs until v[0].endMs + 1)
    }

    @Test fun aGapOfTheLimitStartsANewVisit() {
        val v = PlaceVisits.from(listOf(s(0), s(30), s(31)), gap)
        assertEquals(listOf(0L to 0L, 30 * 60_000L to 31 * 60_000L), v.map { it.startMs to it.endMs })
    }

    @Test fun outOfOrderSightingsAreSortedFirst() {
        val v = PlaceVisits.from(listOf(s(100), s(0), s(5), s(90)), gap)
        assertEquals(listOf(0L to 5 * 60_000L, 90 * 60_000L to 100 * 60_000L), v.map { it.startMs to it.endMs })
    }

    @Test fun eachRadioKeepsItsOwnSpanWithinAVisit() {
        val v = PlaceVisits.from(listOf(s(0, "ble:a"), s(3, "wifi:b"), s(7, "ble:a"), s(9, "wifi:b"), s(12, "ble:c")), gap).single()
        assertEquals(
            mapOf("ble:a" to 0L..7 * 60_000L, "wifi:b" to 3 * 60_000L..9 * 60_000L, "ble:c" to 12 * 60_000L..12 * 60_000L),
            v.entities,
        )
    }
}
