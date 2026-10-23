package app.sotreus.intelligence.fieldwatch

import org.junit.Assert.*
import org.junit.Test

class RadioDbTest {
    @Test fun releaseAssetLoadsInRuntimeLookupAndRetainsAssignedNumbers() {
        RadioDb.init { checkNotNull(javaClass.getResourceAsStream("/radiodb.bin")) }
        assertTrue(RadioDb.nameCount > 30_000)
        assertTrue(RadioDb.vendorForMac("00:00:00:12:34:56")!!.contains("XEROX", ignoreCase = true))
        assertTrue(RadioDb.company(0x004C)!!.contains("Apple", ignoreCase = true))
        assertEquals("Generic Access", RadioDb.serviceUuid("1800"))
        assertTrue(RadioDb.appearance(64)!!.contains("Phone", ignoreCase = true))
        assertTrue(RadioDb.builtYmd >= 20260824)
    }
}
