package app.sotreus.edge.protocol.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshRegionGuessTest {
    @Test fun countriesMapToOneRegion() {
        assertEquals("IN", MeshRegionGuess.forCountry("in"))
        assertEquals("EU_868", MeshRegionGuess.forCountry("GB"))
        assertEquals("EU_868", MeshRegionGuess.forCountry("de"))
        assertEquals("US", MeshRegionGuess.forCountry("US"))
        assertEquals("NP_865", MeshRegionGuess.forCountry("NP"))
        assertEquals("ANZ", MeshRegionGuess.forCountry("au"))
    }

    @Test fun unknownOrUnclearAsksTheOwner() {
        assertNull(MeshRegionGuess.forCountry(null))
        assertNull(MeshRegionGuess.forCountry(""))
        assertNull(MeshRegionGuess.forCountry("ZZ"))
        assertNull(MeshRegionGuess.forCountry("IND"))
    }
}
