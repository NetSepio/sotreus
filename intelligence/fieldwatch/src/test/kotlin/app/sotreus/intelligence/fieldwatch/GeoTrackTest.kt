/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit ddc0d8c).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed; adapted to Sotreus modules.
 */
package app.sotreus.intelligence.fieldwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoTrackTest {
    @Test
    fun aPointBesideANorthLineIsThatFarOffIt() {
        val end = northOf(28.7800, -81.3700, 40.0)
        val side = offset(end.first, end.second, 0.0, 20.0)
        val cross = Geo.crossTrackMeters(28.7800, -81.3700, end.first, end.second, side.first, side.second)
        assertEquals(20.0, abs(cross), 1.5)
    }

    @Test
    fun sideWobbleOnAStraightDriveDoesNotZigzag() {
        val path = mutableListOf<GpsSample>()
        val apex = Geo.DriveApex()
        var lat = 28.7800
        var lon = -81.3700
        Geo.extendDrivePath(path, apex, lat, lon, 1_000L)
        for (i in 1..8) {
            val east = if (i % 2 == 0) 6.0 else -6.0
            val next = offset(lat, lon, 15.0, east)
            lat = next.first
            lon = next.second
            Geo.extendDrivePath(path, apex, lat, lon, 1_000L + i * 1_000L)
        }
        assertEquals(2, path.size)
        assertEquals(lat, path.last().lat, 1e-8)
    }

    @Test
    fun aTurnKeepsTheCorner() {
        val path = mutableListOf<GpsSample>()
        val apex = Geo.DriveApex()
        var lat = 28.7800
        val lon = -81.3700
        Geo.extendDrivePath(path, apex, lat, lon, 1_000L)
        for (i in 1..4) {
            lat = northOf(lat, lon, 20.0).first
            Geo.extendDrivePath(path, apex, lat, lon, 1_000L + i * 1_000L)
        }
        assertEquals(2, path.size)
        var eastLon = lon
        for (i in 1..4) {
            val next = offset(lat, eastLon, 0.0, 20.0)
            eastLon = next.second
            Geo.extendDrivePath(path, apex, lat, eastLon, 10_000L + i * 1_000L)
        }
        assertTrue(path.size >= 3)
        assertEquals(lat, path.last().lat, 1e-8)
        assertEquals(eastLon, path.last().lon, 1e-8)
    }

    @Test
    fun theCapDropsTheOldestCorner() {
        val path = mutableListOf<GpsSample>()
        val apex = Geo.DriveApex()
        var lat = 28.7800
        var lon = -81.3700
        var at = 1_000L
        var trimmed = false
        Geo.extendDrivePath(path, apex, lat, lon, at, cap = 4)
        repeat(6) {
            val north = northOf(lat, lon, 40.0)
            lat = north.first
            at += 1_000L
            trimmed = Geo.extendDrivePath(path, apex, lat, lon, at, cap = 4) || trimmed
            val east = offset(lat, lon, 0.0, 40.0)
            lon = east.second
            at += 1_000L
            trimmed = Geo.extendDrivePath(path, apex, lat, lon, at, cap = 4) || trimmed
        }
        assertTrue(trimmed)
        assertTrue(path.size <= 4)
        assertTrue(path.size >= 2)
    }

    @Test
    fun onlyATightGpsFixJoinsTheDrive() {
        assertTrue(Geo.acceptDriveFix(gps = true, accuracyM = 8f))
        assertTrue(Geo.acceptDriveFix(gps = true, accuracyM = null))
        assertFalse(Geo.acceptDriveFix(gps = true, accuracyM = 40f))
        assertFalse(Geo.acceptDriveFix(gps = false, accuracyM = 8f))
    }

    private fun northOf(lat: Double, lon: Double, meters: Double): Pair<Double, Double> =
        offset(lat, lon, meters, 0.0)

    private fun offset(lat: Double, lon: Double, northM: Double, eastM: Double): Pair<Double, Double> {
        val dLat = northM / 111_320.0
        val dLon = eastM / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return (lat + dLat) to (lon + dLon)
    }
}
