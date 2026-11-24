package app.sotreus.core.data.pipeline

import app.sotreus.intelligence.fieldwatch.*
import org.junit.Assert.*
import org.junit.Test

class LiveSightingsTest {
    private fun observation(at: Long, fresh: Boolean = true, kind: RadioKind = RadioKind.WIFI) = Observation(
        kind = kind, mac = "00:11:22:33:44:55", name = "Example", rssi = -60, channel = 1,
        frequencyMhz = 2412, hiddenSsid = false, serviceUuids = emptyList(), manufacturerId = null,
        manufacturerDataHex = "", rawHex = "", extras = "", at = at, fresh = fresh,
    )

    @Test fun cachedWifiNeverRefreshesLastHeardOrPresence() {
        val live = LiveSightings()
        live.upsert("a", observation(1_000))
        val cached = live.upsert("a", observation(100_000, fresh = false))
        assertEquals(1_000L, cached.lastSeen)
        assertEquals(1, cached.hitCount)
    }

    @Test fun bleRandomAddressDoesNotUseIeeeLocalBitToClaimStability() {
        val live = LiveSightings()
        val random = observation(1_000, kind = RadioKind.BLE).copy(facts = RadioFacts(addressType = "Random"))
        assertTrue(live.upsert("a", random).randomized)
        assertFalse(live.upsert("a", random.copy(facts = RadioFacts(addressType = "Public"))).randomized)
    }

    @Test fun aPacketWithoutAReadingIsNeverTheLoudest() {
        val live = LiveSightings()
        val unread = live.upsert("a", observation(1_000).copy(rssi = Rssi.wifiLevel(0)))
        assertFalse(Rssi.measured(unread.rssi))
        assertTrue(unread.rssiHistory.isEmpty())
        val first = live.upsert("a", observation(2_000))
        assertEquals(listOf(-60, -60, -60), listOf(first.rssi, first.rssiMin, first.rssiMax))
        val again = live.upsert("a", observation(3_000).copy(rssi = 127))
        assertEquals(listOf(-60, -60, -60), listOf(again.rssi, again.rssiMin, again.rssiMax))
        assertEquals(3_000L, again.lastSeen)
        assertEquals(1, again.rssiHistory.size)
    }
}
