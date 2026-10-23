package app.sotreus.sensing.radio

import org.junit.Assert.*
import org.junit.Test

class ScanFreshnessTest {
    @Test fun usesMeasurementTimeAndDoesNotRefreshCachedResults() {
        val f = ScanFreshness()
        assertEquals(98_000L, f.measuredAt("a", 8_000_000, 10_000_000, 100_000))
        assertNull(f.measuredAt("a", 8_000_000, 15_000_000, 105_000))
        assertNull(f.measuredAt("a", 7_000_000, 15_000_000, 105_000))
        assertEquals(105_000L, f.measuredAt("a", 15_000_000, 15_000_000, 105_000))
    }

    @Test fun rejectsOldInvalidAndFutureMeasurementsEvenForNewAccessPoints() {
        val f = ScanFreshness()
        assertNull(f.measuredAt("old", 1_000_000, 40_000_000, 100_000))
        assertNull(f.measuredAt("zero", 0, 40_000_000, 100_000))
        assertNull(f.measuredAt("future", 41_000_000, 40_000_000, 100_000))
    }

    @Test fun separateAccessPointsHaveSeparateMeasurementClocks() {
        val f = ScanFreshness()
        assertNotNull(f.measuredAt("a", 9_000_000, 10_000_000, 100_000))
        assertNotNull(f.measuredAt("b", 9_000_000, 10_000_000, 100_000))
    }
}
