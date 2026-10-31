package app.sotreus.context

import app.sotreus.context.environment.MeteoAlarmFeed
import app.sotreus.context.environment.NwsAlerts
import app.sotreus.context.environment.OpenAq
import app.sotreus.context.environment.WarningRegions
import app.sotreus.core.model.AlertSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class WarningParsersTest {
    private fun fixture(name: String) = javaClass.getResource("/environment/$name")!!.readText()
    private fun at(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun nwsListsCurrentAlertsMostSevereFirst() {
        val alerts = NwsAlerts.parse(fixture("nws.json"), at("2026-10-31T12:00:00Z"))
        assertEquals(listOf("Winter Weather Advisory", "Beach Hazards Statement", "Coastal Flood Advisory"), alerts.map { it.event })
        assertTrue(alerts.all { it.source == AlertSource.NWS && it.area.isNotBlank() })
        assertEquals("Minor", alerts.last().severity)
        // "ends" wins over "expires": the advisory runs until its end time.
        assertEquals(at("2026-11-01T08:00:00-07:00"), alerts.first().untilMs)
    }

    @Test
    fun nwsDropsAlertsThatHaveEnded() {
        val alerts = NwsAlerts.parse(fixture("nws.json"), at("2026-11-02T00:00:00Z"))
        assertEquals(listOf("Beach Hazards Statement"), alerts.map { it.event })
    }

    @Test
    fun meteoAlarmReadsLevelEventAndAreas() {
        val alerts = MeteoAlarmFeed.parse(fixture("meteoalarm.xml"), at("2026-10-31T12:00:00Z"))
        assertEquals(4, alerts.size)
        val first = alerts.first()
        assertEquals("Yellow", first.level)
        assertEquals("Snow-ice Warning", first.event)
        assertEquals("Kreis Cham", first.area)
        assertTrue(first.url!!.startsWith("https://meteoalarm.org?geocode="))
        val groups = MeteoAlarmFeed.group(alerts)
        assertEquals(alerts.size, groups.sumOf { it.areas.size })
        assertTrue(groups.all { it.level == "Yellow" && it.event == "Snow-ice Warning" })
        assertTrue(groups.first().areas == groups.first().areas.sorted())
    }

    @Test
    fun meteoAlarmDropsExpiredWarnings() {
        assertTrue(MeteoAlarmFeed.parse(fixture("meteoalarm.xml"), at("2026-12-01T00:00:00Z")).isEmpty())
    }

    @Test
    fun meteoAlarmRefusesDoctypes() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE feed [<!ENTITY x SYSTEM "file:///etc/passwd">]><feed><entry><title>&x;</title></entry></feed>"""
        assertTrue(runCatching { MeteoAlarmFeed.parse(hostile, 0) }.getOrNull().orEmpty().isEmpty())
    }

    @Test
    fun openAqPicksTheNearestRecentStationAndNamesItsValues() {
        val stations = OpenAq.parseLocations(fixture("openaq-locations.json"))
        assertEquals(3, stations.size)
        // The silent station is closest but has not reported for a long time.
        val (station, km) = OpenAq.nearest(stations, GeoPoint(15.69, 73.72), at("2026-10-31T12:00:00Z"), 24 * 3_600_000L)!!
        assertEquals("Station Near", station.name)
        assertTrue(km in 5.0..15.0)
        assertEquals("Example Board", station.attribution)
        val values = OpenAq.values(station, OpenAq.parseLatest(fixture("openaq-latest.json")))
        assertEquals(listOf("PM2.5", "O₃"), values.map { it.parameter })
        assertEquals(21.4, values.first().value, 0.0)
        assertEquals("ppm", values.last().units)
    }

    @Test
    fun openAqWithNoRecentStationsHasNoNearest() {
        val stations = OpenAq.parseLocations(fixture("openaq-locations.json"))
        assertNull(OpenAq.nearest(stations, GeoPoint(15.69, 73.72), at("2027-06-01T00:00:00Z"), 24 * 3_600_000L))
    }

    @Test
    fun regionListsHaveUniqueCodes() {
        assertEquals(WarningRegions.NWS.size, WarningRegions.NWS.map { it.first }.toSet().size)
        assertEquals(WarningRegions.METEOALARM.size, WarningRegions.METEOALARM.map { it.first }.toSet().size)
        assertEquals("California", WarningRegions.nwsName("CA"))
        assertEquals("Germany", WarningRegions.meteoAlarmName("germany"))
    }
}
