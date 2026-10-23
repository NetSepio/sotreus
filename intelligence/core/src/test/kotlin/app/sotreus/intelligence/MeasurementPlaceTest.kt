package app.sotreus.intelligence

import org.junit.Assert.*
import org.junit.Test

class MeasurementPlaceTest {
    private val area = PlaceGeofence.Area(1, 0.0, 0.0, 150)
    private val here = PlaceGeofence.Fix(10_000, 0.0, 0.0, 10f)

    @Test fun measurementFromPreviousSelectionCannotBeAssignedToNextPlace() {
        assertNull(MeasurementPlace.resolve(1, true, 11_000, 10_000, area, here))
    }

    @Test fun automaticPlaceNeedsMatchingGeographyAndFreshAccurateFix() {
        assertEquals(1L, MeasurementPlace.resolve(1, true, 0, 10_000, area, here))
        assertNull(MeasurementPlace.resolve(1, true, 0, 10_000, area, here.copy(lon = 1.0)))
        assertNull(MeasurementPlace.resolve(1, true, 0, 40_000, area, here))
        assertNull(MeasurementPlace.resolve(1, true, 0, 10_000, area, here.copy(accuracyM = 500f)))
        assertNull(MeasurementPlace.resolve(1, true, 0, 10_000, area, null))
    }

    @Test fun explicitManualPlaceDoesNotRequireCoordinates() {
        assertEquals(1L, MeasurementPlace.resolve(1, false, 0, 10_000, null, null))
    }
}
