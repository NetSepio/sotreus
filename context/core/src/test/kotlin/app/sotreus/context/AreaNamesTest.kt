package app.sotreus.context

import app.sotreus.context.environment.AreaName
import app.sotreus.context.environment.AreaNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class AreaNamesTest {
    private fun asset(name: String) = listOf(File("src/main/assets/regions/$name"), File("context/core/src/main/assets/regions/$name")).first { it.isFile }.readBytes()
    private val names = AreaNames(asset("countries.bin"), asset("places.bin"))

    @Test
    fun namesTheNearestTownItsRegionAndTheCountry() {
        assertEquals(AreaName("Panaji", "Goa", "India"), names.at(15.4909, 73.8278))
        assertEquals("Germany", names.at(52.52, 13.405)!!.country)
        assertEquals("Berlin", names.at(52.52, 13.405)!!.town)
        assertEquals("United States of America", names.at(37.7749, -122.4194)!!.country)
        assertEquals("Singapore", names.at(1.3521, 103.8198)!!.country)
    }

    @Test
    fun aTownAcrossABorderIsNotUsed() {
        // Strasbourg's suburbs reach the Rhine; across it is Kehl, Germany. A point in France names a French town.
        val a = names.at(48.5734, 7.7521)!!
        assertEquals("France", a.country)
    }

    @Test
    fun openSeaHasNoName() {
        assertNull(names.at(30.0, -40.0))
    }
}
