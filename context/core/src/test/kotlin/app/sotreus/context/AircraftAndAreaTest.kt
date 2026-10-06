package app.sotreus.context

import app.sotreus.context.aircraft.AircraftReport
import app.sotreus.context.aircraft.OpenSkyProvider
import app.sotreus.core.model.AircraftMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftAndAreaTest {
    @Test
    fun parsesOpenSkyStateVectors() {
        val body = """{"time":1791263690,"states":[
            ["76cc65","SIA842  ","Singapore",1791263689,1791263689,104.0249,1.2545,1813.56,false,141.54,46.33,6.83,null,1920.24,"2233",false,0],
            ["76b869","TGW638  ","Singapore",1791263598,1791263600,103.9981,1.3641,null,true,2.83,112.5,null,null,null,null,false,0]]}"""
        val r = OpenSkyProvider.parse(body)
        assertEquals(1791263690000L, r.providerTimeMs)
        val a = r.reports[0]
        assertEquals("76cc65", a.icao24)
        assertEquals("SIA842", a.callsign)
        assertEquals(1.2545, a.lat!!, 1e-9)
        assertEquals(104.0249, a.lon!!, 1e-9)
        assertEquals(1920.24, a.altitudeM!!, 1e-9) // geometric altitude preferred over barometric
        assertEquals(46.33, a.courseDeg!!, 1e-9)
        assertFalse(a.onGround)
        assertTrue(r.reports[1].onGround)
        assertNull(r.reports[1].altitudeM)
    }

    @Test
    fun coarseAreaNeverNarrowsBelowTheDegreeGrid() {
        // Two points far apart inside the same 1° cell produce the identical request.
        val a = Geo.coarseArea(GeoPoint(1.2834, 103.8607))
        val b = Geo.coarseArea(GeoPoint(1.9, 103.05))
        assertEquals(a, b)
        assertEquals(GeoArea(0.0, 102.0, 3.0, 105.0), a)
        // 3°×3° stays within OpenSky's cheapest credit tier (< 25 square degrees).
        assertTrue((a.latMax - a.latMin) * (a.lonMax - a.lonMin) < 25.0)
    }

    @Test
    fun distanceAndExactArea() {
        assertEquals(340.5, Geo.distanceKm(GeoPoint(51.5007, -0.1246), GeoPoint(48.8584, 2.2945)), 1.0)
        val box = Geo.around(GeoPoint(1.3, 103.8), 25.0)
        assertTrue(Geo.distanceKm(GeoPoint(1.3, 103.8), GeoPoint(box.latMax, 103.8)) in 24.0..26.0)
    }

    @Test
    fun coarseModeAsksForTheGridCellAndFiltersOnThePhone() {
        val here = GeoPoint(1.2834, 103.8607)
        assertEquals(Geo.coarseArea(here), ContextRepository.queryArea(AircraftMode.COARSE_AREA, here, 25.0))
        assertEquals(Geo.around(here, 25.0), ContextRepository.queryArea(AircraftMode.EXACT_AREA, here, 25.0))
        fun at(id: String, lat: Double?, lon: Double?) = AircraftReport(id, null, null, null, 0, lat, lon, null, false, null, null, null, null)
        val near = ContextRepository.withinRadius(
            listOf(at("far", 2.5, 104.5), at("close", 1.30, 103.87), at("mid", 1.40, 103.90), at("nopos", null, null)),
            here, 25.0,
        )
        assertEquals(listOf("close", "mid"), near.map { it.report.icao24 })
        assertTrue(near[0].distanceKm < near[1].distanceKm)
    }
}
