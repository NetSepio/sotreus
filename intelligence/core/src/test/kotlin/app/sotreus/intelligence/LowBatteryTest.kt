package app.sotreus.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryTest {
    @Test fun offNeverSlows() = assertFalse(LowBattery.slowed(0, 1, charging = false, slowed = true))

    @Test fun startsAtTheLevelAndLiftsFourPointsAbove() {
        assertFalse(LowBattery.slowed(20, 21, charging = false, slowed = false))
        assertTrue(LowBattery.slowed(20, 20, charging = false, slowed = false))
        assertTrue(LowBattery.slowed(20, 23, charging = false, slowed = true))
        assertFalse(LowBattery.slowed(20, 24, charging = false, slowed = true))
    }

    @Test fun chargingLiftsIt() = assertFalse(LowBattery.slowed(20, 5, charging = true, slowed = true))

    @Test fun aMissingReadingChangesNothing() {
        assertTrue(LowBattery.slowed(20, null, charging = false, slowed = true))
        assertFalse(LowBattery.slowed(20, null, charging = false, slowed = false))
    }

    @Test fun offIsAChoice() = assertTrue(0 in LowBattery.CHOICES)
}
