package app.sotreus.core.data.edge

import app.sotreus.core.data.edge.FullSizePackets.loss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Full-size Bluetooth packets: the default, and turning off after links drop early. */
class FullSizePacketsTest {
    private val min = 60_000L
    private val boot = 12L

    @Test fun theOwnersChoiceWinsElseBluetooth5DecidesTheDefault() {
        assertTrue(FullSizePackets.wanted(null, phoneBluetooth5 = true))
        assertFalse(FullSizePackets.wanted(null, phoneBluetooth5 = false))
        assertTrue(FullSizePackets.wanted(true, phoneBluetooth5 = false))
        assertFalse(FullSizePackets.wanted(false, phoneBluetooth5 = true))
    }

    @Test fun twoFullSizeLinksInARowLostWithinTwoMinutesTurnItOff() {
        val one = FullSizePackets.afterReconnect(0, loss(70_000, boot), boot)
        assertEquals(1, one)
        assertFalse(FullSizePackets.turnsOff(one))
        assertTrue(FullSizePackets.turnsOff(FullSizePackets.afterReconnect(one, loss(30_000, boot), boot)))
    }

    @Test fun aLinkThatHeldStartsTheCountAgain() {
        assertEquals(0, FullSizePackets.afterReconnect(1, loss(2 * min, boot), boot))
        assertEquals(0, FullSizePackets.afterReconnect(1, loss(6 * 60 * min, boot), boot + 1))
    }

    @Test fun aDropAcrossAnEdgeRestartIsTheRestartNotThePhone() {
        // A flash or reboot drops the link within seconds; the Edge answers on a new boot.
        assertEquals(1, FullSizePackets.afterReconnect(1, loss(30_000, boot), boot + 1))
        assertEquals(0, FullSizePackets.afterReconnect(0, loss(30_000, null), boot))
    }

    @Test fun limitedLinksNeitherCountNorReset() {
        // The first link after turning it on can stay limited (the command came after the Edge's 3 s step).
        assertEquals(1, FullSizePackets.afterReconnect(1, null, boot))
    }
}
