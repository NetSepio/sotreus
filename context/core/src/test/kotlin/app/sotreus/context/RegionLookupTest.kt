package app.sotreus.context

import app.sotreus.context.environment.AqiScale
import app.sotreus.context.environment.RegionLookup
import app.sotreus.context.environment.WarningRegion
import app.sotreus.context.environment.WarningRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RegionLookupTest {
    private val lookup = RegionLookup(
        listOf(File("src/main/assets/regions/warning_regions.bin"), File("context/core/src/main/assets/regions/warning_regions.bin"))
            .first { it.isFile }.readBytes(),
    )

    @Test
    fun usStatesAndTerritoriesMapToNwsAreaCodes() {
        assertEquals(WarningRegion.Nws("CA"), lookup.warningRegion(37.7749, -122.4194)) // San Francisco
        assertEquals(WarningRegion.Nws("NY"), lookup.warningRegion(40.7128, -74.0060)) // New York
        assertEquals(WarningRegion.Nws("HI"), lookup.warningRegion(21.3069, -157.8583)) // Honolulu
        assertEquals(WarningRegion.Nws("AK"), lookup.warningRegion(61.2181, -149.9003)) // Anchorage
        assertEquals(WarningRegion.Nws("DC"), lookup.warningRegion(38.8977, -77.0365)) // Washington
        assertEquals(WarningRegion.Nws("PR"), lookup.warningRegion(18.4655, -66.1057)) // San Juan
        assertEquals(WarningRegion.Nws("FL"), lookup.warningRegion(25.7617, -80.1918)) // Miami
        assertEquals(WarningRegion.Nws("WA"), lookup.warningRegion(47.6062, -122.3321)) // Seattle
    }

    @Test
    fun europeanCountriesMapToMeteoAlarmFeeds() {
        assertEquals(WarningRegion.MeteoAlarm("germany"), lookup.warningRegion(52.52, 13.405)) // Berlin
        assertEquals(WarningRegion.MeteoAlarm("france"), lookup.warningRegion(48.8566, 2.3522)) // Paris
        assertEquals(WarningRegion.MeteoAlarm("norway"), lookup.warningRegion(59.9139, 10.7522)) // Oslo
        assertEquals(WarningRegion.MeteoAlarm("united-kingdom"), lookup.warningRegion(51.5074, -0.1278)) // London
        assertEquals(WarningRegion.MeteoAlarm("switzerland"), lookup.warningRegion(47.3769, 8.5417)) // Zurich
        assertEquals(WarningRegion.MeteoAlarm("netherlands"), lookup.warningRegion(52.3676, 4.9041)) // Amsterdam
        assertEquals(WarningRegion.MeteoAlarm("italy"), lookup.warningRegion(40.8518, 14.2681)) // Naples
    }

    @Test
    fun placesWithoutACoveredServiceHaveNoRegion() {
        assertNull(lookup.warningRegion(15.4909, 73.8278)) // Goa, India
        assertNull(lookup.warningRegion(1.3521, 103.8198)) // Singapore
        assertNull(lookup.warningRegion(43.6532, -79.3832)) // Toronto, across the lake from New York
        assertNull(lookup.warningRegion(49.2827, -123.1207)) // Vancouver, near Washington
        assertNull(lookup.warningRegion(25.6866, -100.3161)) // Monterrey, Mexico
        assertNull(lookup.warningRegion(30.0, -40.0)) // mid-Atlantic
    }

    @Test
    fun everyMeteoAlarmFeedHasABoundary() {
        WarningRegions.METEOALARM.forEach { (slug, name) ->
            val iso = listOf("AD", "AT", "BE", "BA", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IS", "IE", "IL", "IT", "LV", "LT", "LU", "MT", "MD", "ME", "NL", "MK", "NO", "PL", "PT", "RO", "RS", "SK", "SI", "ES", "SE", "CH", "UA", "GB")
                .firstOrNull { WarningRegions.meteoAlarmFor(it) == slug }
            assertEquals("no ISO code for $name", slug, iso?.let { WarningRegions.meteoAlarmFor(it) })
        }
    }

    @Test
    fun airQualityUsesTheScaleOfTheRegion() {
        assertEquals(AqiScale.EUROPEAN, WarningRegions.aqiScale(lookup.codeAt(52.52, 13.405))) // Berlin
        assertEquals(AqiScale.US, WarningRegions.aqiScale(lookup.codeAt(37.7749, -122.4194))) // San Francisco
        assertEquals(AqiScale.US, WarningRegions.aqiScale(lookup.codeAt(15.4909, 73.8278))) // Goa: no local index in the feed
        assertEquals(AqiScale.US, WarningRegions.aqiScale("IL"))
    }
}
