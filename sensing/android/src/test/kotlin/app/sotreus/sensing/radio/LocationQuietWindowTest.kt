/*
 * Adapted from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit ddc0d8c).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: integrated with observation ownership and Android 9 compatibility.
 */
package app.sotreus.sensing.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQuietWindowTest {
    private var now = 10_000L
    private val window = LocationQuietWindow(nowMs = { now })

    @Test
    fun theFirstReadingDoesNotHoldRadios() {
        assertFalse(window.observe(true))
        assertFalse(window.radiosHeld())
        assertFalse(window.locationHeld())
        assertFalse(window.takeRadioResume())
        assertFalse(window.takeLocationResume())
    }

    @Test
    fun aSeededOnSwitchDoesNotArmWhenItStaysOn() {
        window.seed(true)
        assertFalse(window.observe(true))
        assertFalse(window.observe(true))
        assertFalse(window.radiosHeld())
    }

    @Test
    fun offToOnHoldsRadiosThenReleasesTheGpsListenerLater() {
        window.seed(false)
        assertTrue(window.observe(true))
        assertEquals(LocationQuietWindow.RADIO_QUIET_MS, window.radioHoldMs())
        assertTrue(window.radiosHeld())
        assertTrue(window.locationHeld())
        assertFalse(window.takeRadioResume())
        assertFalse(window.takeLocationResume())

        now += LocationQuietWindow.RADIO_QUIET_MS
        assertEquals(0L, window.radioHoldMs())
        assertFalse(window.radiosHeld())
        assertTrue(window.takeRadioResume())
        assertFalse(window.takeRadioResume())
        assertTrue(window.locationHeld())
        assertFalse(window.takeLocationResume())

        now += LocationQuietWindow.LOCATION_LAG_MS
        assertFalse(window.locationHeld())
        assertTrue(window.takeLocationResume())
        assertFalse(window.takeLocationResume())
    }

    @Test
    fun turningLocationOffDoesNotArm() {
        window.seed(true)
        assertFalse(window.observe(false))
        assertFalse(window.radiosHeld())
        assertFalse(window.takeRadioResume())
        assertFalse(window.takeLocationResume())
    }

    @Test
    fun turningLocationOffCancelsAPendingRestart() {
        window.seed(false)
        assertTrue(window.observe(true))
        assertTrue(window.radiosHeld())
        assertFalse(window.observe(false))
        assertFalse(window.radiosHeld())
        assertFalse(window.locationHeld())
        now += LocationQuietWindow.RADIO_QUIET_MS + LocationQuietWindow.LOCATION_LAG_MS
        assertFalse(window.takeRadioResume())
        assertFalse(window.takeLocationResume())
    }

    @Test
    fun aSecondOffToOnRestartsTheWindow() {
        window.seed(false)
        assertTrue(window.observe(true))
        now += 1_000L
        assertFalse(window.observe(false))
        assertTrue(window.observe(true))
        assertEquals(LocationQuietWindow.RADIO_QUIET_MS, window.radioHoldMs())
        assertFalse(window.takeRadioResume())

        now += LocationQuietWindow.RADIO_QUIET_MS
        assertTrue(window.takeRadioResume())
        now += LocationQuietWindow.LOCATION_LAG_MS
        assertTrue(window.takeLocationResume())
    }

    @Test
    fun seedDoesNotEraseAnEdgeThatAlreadyArrived() {
        window.seed(false)
        assertTrue(window.observe(true))
        window.seed(false)
        assertTrue(window.radiosHeld())
        assertEquals(LocationQuietWindow.RADIO_QUIET_MS, window.radioHoldMs())
    }
}
