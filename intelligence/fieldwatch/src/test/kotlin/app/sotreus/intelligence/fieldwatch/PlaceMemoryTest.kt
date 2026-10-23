/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit ddc0d8c).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed; adapted to Sotreus modules.
 */
package app.sotreus.intelligence.fieldwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class PlaceMemoryTest {
    private val zone = ZoneOffset.UTC
    private val day = Instant.parse("2026-06-15T12:00:00Z")
    private val t0 = day.toEpochMilli()
    private val localDay = day.atZone(zone).toLocalDate().toEpochDay()
    private val names = mapOf("tile" to "Tile")





    @Test
    fun placesFromTheLastWeekStayListed() {
        val day = 24 * HOUR
        val record = visit(null, home, t0).let { visit(it, store, t0 + 3 * day) }
        val end = t0 + 3 * day + MINUTE
        val lines = PlaceMemory.detailLines(record, end, zone)
        assertEquals("Seen at 2 places", lines[0])
        assertTrue(lines[1].contains("Jun 15, 12:00"))
        assertTrue(lines[2].startsWith("Home · 12:00"))
        assertTrue(lines.last().startsWith("Heard again after 3 days"))
        val stale = visit(null, home, t0).let { visit(it, store, t0 + 8 * day) }
        val later = PlaceMemory.detailLines(stale, t0 + 8 * day + MINUTE, zone)
        assertTrue(later.none { it.startsWith("Seen at ") })
    }

    @Test
    fun onePlaceBssidDoesNotAlert() {
        val wifi = radio(RadioKind.WIFI, "00:11:22:33:44:66", fleetIds = emptySet())
        assertNull(PlaceMemory.accept(wifi, names, emptySet(), generic = false))
        val id = PlaceMemory.accept(wifi, names, emptySet(), generic = true)!!
        var record: PlaceMemory.Record? = null
        repeat(6) { i ->
            record = PlaceMemory.note(record, hear(id, t0 + i * MINUTE, home))
        }
        val lines = PlaceMemory.detailLines(record!!, t0 + 10 * MINUTE, zone)
        assertTrue(lines.none { it.startsWith("Seen at ") })
    }

    @Test
    fun rotatingBleDoesNotAlert() {
        val rotating = radio(RadioKind.BLE, "02:11:22:33:44:55", fleetIds = setOf("tile"))
        assertNull(PlaceMemory.accept(rotating, names, emptySet()))
    }

    @Test
    fun mineDoesNotAlert() {
        val device = tile()
        assertNull(PlaceMemory.accept(device, names, setOf(device.key)))
        assertEquals("ble:${device.mac}", PlaceMemory.accept(device, names, emptySet()))
    }

    @Test
    fun returnAfterTwentyFiveMinutesFlags() {
        val id = "ble:${tile().mac}"
        val record = PlaceMemory.note(
            PlaceMemory.note(null, hear(id, t0, home)),
            hear(id, t0 + 25 * MINUTE, home),
        )
        val lines = PlaceMemory.detailLines(record, t0 + 25 * MINUTE, zone)
        assertEquals(listOf("Heard again after 25 minutes"), lines)
    }

    @Test
    fun returnAfterTenMinutesDoesNotFlag() {
        val id = "ble:${tile().mac}"
        val record = PlaceMemory.note(
            PlaceMemory.note(null, hear(id, t0, home)),
            hear(id, t0 + 10 * MINUTE, home),
        )
        assertTrue(PlaceMemory.detailLines(record, t0 + 10 * MINUTE, zone).isEmpty())
    }

    @Test
    fun driveAlreadyWithYouDoesNotOpenASecondPlace() {
        val id = "ble:${tile().mac}"
        var record = visit(null, home, t0)
        record = PlaceMemory.note(record, hear(id, t0 + 5 * MINUTE, store))
        record = PlaceMemory.note(record, hear(id, t0 + 10 * MINUTE, park))
        assertEquals(1, PlaceMemory.placeCount(record, t0 + 10 * MINUTE))
        assertNull(
            PlaceMemory.section(
                devices = listOf(tile()),
                records = listOf(record),
                names = names,
                customNames = emptyMap(),
                mineKeys = emptySet(),
                now = t0 + 10 * MINUTE,
                zone = zone,
            ),
        )
    }

    @Test
    fun oneJumpedFixDoesNotCount() {
        val id = "ble:${tile().mac}"
        val record = PlaceMemory.note(visit(null, home, t0), hear(id, t0 + 30 * MINUTE, store))
        assertEquals(1, PlaceMemory.placeCount(record, t0 + 30 * MINUTE))
    }

    @Test
    fun placeCapStaysSmall() {
        val id = "ble:${tile().mac}"
        var record: PlaceMemory.Record? = null
        repeat(12) { i ->
            val at = t0 + i * 2 * HOUR
            record = visit(record, i * 0.02 to -77.0, at)
        }
        assertEquals(PlaceMemory.MAX_PLACES, record!!.places.size)
        assertTrue(record.places.any { it.firstAt == t0 + 11 * 2 * HOUR })
        assertTrue(record.places.none { it.firstAt == t0 })
    }

    @Test
    fun oneJumpAtTheCapDoesNotDropAnOlderStop() {
        val id = "ble:${tile().mac}"
        var record: PlaceMemory.Record? = null
        repeat(8) { i ->
            record = visit(record, i * 0.02 to -77.0, t0 + i * 2 * HOUR)
        }
        val oldest = t0
        val jumped = PlaceMemory.note(record, hear(id, t0 + 16 * HOUR, 0.20 to -77.0))
        assertTrue(jumped.places.any { it.firstAt == oldest })
        assertEquals(8, PlaceMemory.placeCount(jumped, t0 + 16 * HOUR))
        val confirmed = PlaceMemory.note(jumped, hear(id, t0 + 16 * HOUR + MINUTE, 0.20 to -77.0))
        assertTrue(confirmed.places.none { it.firstAt == oldest })
        assertTrue(confirmed.places.any { it.firstAt == t0 + 16 * HOUR })
        assertEquals(8, PlaceMemory.placeCount(confirmed, t0 + 16 * HOUR + MINUTE))
    }



    @Test
    fun localDateFollowsThePhoneZone() {
        val east = ZoneId.of("America/New_York")
        val at = Instant.parse("2026-06-15T03:30:00Z")
        assertEquals(LocalDate.of(2026, 6, 14), PlaceMemory.localDate(at.toEpochMilli(), east))
        assertEquals(LocalDate.of(2026, 6, 15), PlaceMemory.localDate(at.toEpochMilli(), ZoneOffset.UTC))
    }

    private fun visit(prev: PlaceMemory.Record?, at: Pair<Double, Double>, whenAt: Long): PlaceMemory.Record {
        val id = "ble:${tile().mac}"
        val first = PlaceMemory.note(prev, hear(id, whenAt, at, "Home"))
        return PlaceMemory.note(first, hear(id, whenAt + MINUTE, at, "Home"))
    }

    private fun hear(
        id: String,
        at: Long,
        where: Pair<Double, Double>,
        label: String? = null,
    ) = PlaceMemory.Hear(
        id = id,
        deviceKey = tile().key,
        at = at,
        lat = where.first,
        lon = where.second,
        sitId = "sit",
        sitLabel = label,
        localDay = localDay,
    )

    private fun tile() = radio(RadioKind.BLE, "00:11:22:33:44:55", fleetIds = setOf("tile"))

    private fun radio(kind: RadioKind, mac: String, fleetIds: Set<String>) = Sighting(
        key = "${kind.name}:$mac",
        kind = kind,
        mac = mac,
        name = "Tile",
        rssi = -60,
        rssiMin = -70,
        rssiMax = -50,
        channel = 0,
        frequencyMhz = 0,
        vendor = null,
        randomized = MacUtil.isRandomized(mac),
        hiddenSsid = false,
        serviceUuids = emptyList(),
        manufacturerId = null,
        manufacturerDataHex = "",
        rawHex = "",
        extras = "",
        firstSeen = t0,
        lastSeen = t0,
        hitCount = 1,
        fleetIds = fleetIds,
        rssiHistory = emptyList(),
        presence = emptyList(),
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        val home = 38.0 to -77.0
        val store = 38.01 to -77.0
        val park = 38.02 to -77.0
    }
}
