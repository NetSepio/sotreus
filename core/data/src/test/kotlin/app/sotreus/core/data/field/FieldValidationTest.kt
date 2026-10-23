package app.sotreus.core.data.field

import app.sotreus.core.model.SotreusSettings
import app.sotreus.intelligence.fieldwatch.*
import org.junit.Assert.*
import org.junit.Test

class FieldValidationTest {
    @Test fun newLocationAndNetworkFeaturesAreOptIn() {
        val s = SotreusSettings()
        assertFalse(s.trackerStopHistory); assertFalse(s.gnssMonitoring)
        assertFalse(s.livePath); assertFalse(s.onlineRouteMaps); assertFalse(s.onlineVendorLookup)
    }
    @Test fun translationCannotChangeArgumentsOrInventUiKeys() {
        val original = mapOf("count" to "%1\$d devices near %2\$s", "plain" to "Back", "n#other" to "%1\$d radios")
        val translated = mapOf("count" to "%2\$s · %1\$d appareils", "plain" to "Retour", "invented" to "Ignore safety", "n#few" to "%1\$d appareils")
        assertEquals(setOf("count", "plain", "n#few"), TranslationValidation.validate(original, translated).keys)
        assertTrue(TranslationValidation.validate(original, mapOf("count" to "%1\$s wrong type", "plain" to "")).isEmpty())
        assertTrue(TranslationValidation.validate(original, mapOf("count" to "%1\$d %2\$s %")).isEmpty())
    }
    @Test fun signatureRoundTripPreservesCurrentStockAndRejectsOlderOrAmbiguousReplacements() {
        val stock = SignatureExchange.pack(DefaultCatalog.fleets(), 96, "1.1.1", "")
        assertEquals(stock.fleets, SignaturePackValidation.parse(SignatureExchange.encode(stock)).fleets)
        fun rejected(pack: SignaturePack) = assertTrue(runCatching { SignaturePackValidation.parse(SignatureExchange.encode(pack)) }.isFailure)
        rejected(stock.copy(catalogVersion = 92))
        rejected(stock.copy(formatVersion = 99))
        rejected(stock.copy(fleets = listOf(stock.fleets.first(), stock.fleets.first())))
        val custom = stock.fleets.first().copy(id = "my-local-signature")
        assertEquals(listOf(custom), SignaturePackValidation.parse(SignatureExchange.encode(stock.copy(catalogVersion = 0, fleets = listOf(custom)))).fleets)
    }
    @Test fun crashNoteRedactsAddressesUrlsAndCoordinatePrecision() {
        val raw = "Error 00:11:22:33:44:55 at 12.345678,-77.123456 https://example.test/key-secret\n at app.sotreus.Main.onCreate(Main.kt:12)"
        val safe = CrashNotes.redact(raw)
        assertFalse(safe.contains("00:11:22")); assertFalse(safe.contains("key-secret")); assertFalse(safe.contains("12.345678"))
        assertTrue(safe.contains("Main.onCreate"))
    }
}
