package app.sotreus.core.data.pipeline

import app.sotreus.core.data.pipeline.PlaceTracker.Resume
import app.sotreus.core.database.entity.PlaceVisitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Live visits across a restart, and which place a live visit is held for. */
class PlaceTrackerTest {
    private val min = 60_000L
    private val start = 1_000_000_000L

    @Test fun aRestartSoonAfterTheLastRadioContinuesALongVisit() {
        // Open since 19:30, last radio 6 h later, Sotreus back 10 min after that.
        val open = PlaceVisitEntity(id = 5, placeId = 3, startedAtMs = start)
        val lastRadio = start + 360 * min
        assertEquals(Resume.Continue(5), PlaceTracker.resume(open, lastRadio, null, lastRadio + 10 * min))
    }

    @Test fun aRestartAfterTheGapEndsTheOldVisitAtItsLastRadioNotItsStart() {
        val open = PlaceVisitEntity(id = 5, placeId = 3, startedAtMs = start)
        val lastRadio = start + 360 * min
        assertEquals(Resume.New(5L to lastRadio), PlaceTracker.resume(open, lastRadio, null, lastRadio + 31 * min))
    }

    @Test fun anOpenVisitWithNoRadioEndsAtItsStart() {
        val open = PlaceVisitEntity(id = 7, placeId = 3, startedAtMs = start)
        assertEquals(Resume.Continue(7), PlaceTracker.resume(open, null, null, start + 29 * min))
        assertEquals(Resume.New(7L to start), PlaceTracker.resume(open, null, null, start + 31 * min))
    }

    @Test fun anEndedVisitReopensOnlyWithinTheGap() {
        val latest = PlaceVisitEntity(id = 4, placeId = 3, startedAtMs = start, endedAtMs = start + 60 * min)
        assertEquals(Resume.Reopen(4), PlaceTracker.resume(null, null, latest, start + 89 * min))
        assertEquals(Resume.New(), PlaceTracker.resume(null, null, latest, start + 91 * min))
        assertEquals(Resume.New(), PlaceTracker.resume(null, null, null, start))
    }

    @Test fun thePhoneHoldsTheSelectedPlaceOnlyWhenItConfirmsIt() {
        assertEquals(3L, PlaceTracker.heldPlace(3, phoneConfirms = true, edgeFixedPlaceId = null, edgeFixedNow = false))
        assertNull(PlaceTracker.heldPlace(3, phoneConfirms = false, edgeFixedPlaceId = null, edgeFixedNow = false))
        assertNull(PlaceTracker.heldPlace(null, phoneConfirms = true, edgeFixedPlaceId = 3, edgeFixedNow = true))
    }

    @Test fun aPositionedStationaryEdgeHoldsItsPlaceWithoutAPhoneFix() {
        assertEquals(3L, PlaceTracker.heldPlace(3, phoneConfirms = false, edgeFixedPlaceId = 3, edgeFixedNow = true))
        // Placement held (may have moved), or not positioned: no fixed location now.
        assertNull(PlaceTracker.heldPlace(3, phoneConfirms = false, edgeFixedPlaceId = 3, edgeFixedNow = false))
        // The Edge sits in another place than the one selected.
        assertNull(PlaceTracker.heldPlace(3, phoneConfirms = false, edgeFixedPlaceId = 8, edgeFixedNow = true))
    }
}
