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
        val stock = SignatureExchange.pack(DefaultCatalog.fleets(), SignaturePackValidation.STOCK_VERSION, "1.3.1", "")
        assertEquals(stock.fleets, SignaturePackValidation.parse(SignatureExchange.encode(stock)).fleets)
        fun rejected(pack: SignaturePack) = assertTrue(runCatching { SignaturePackValidation.parse(SignatureExchange.encode(pack)) }.isFailure)
        rejected(stock.copy(catalogVersion = 92))
        rejected(stock.copy(formatVersion = 99))
        rejected(stock.copy(fleets = listOf(stock.fleets.first(), stock.fleets.first())))
        val custom = stock.fleets.first().copy(id = "my-local-signature")
        assertEquals(listOf(custom), SignaturePackValidation.parse(SignatureExchange.encode(stock.copy(catalogVersion = 0, fleets = listOf(custom)))).fleets)
        // A pack this phone saved under catalog 96 still loads after the stock catalog moves on.
        val savedOld = stock.copy(catalogVersion = 96, fleets = stock.fleets + custom)
        assertEquals(savedOld.fleets, SignaturePackValidation.parse(SignatureExchange.encode(savedOld), saved = true).fleets)
        rejected(savedOld)
    }
    @Test fun rsiFilesKeepPublishersApartAndNewerOnesStillLoad() {
        val stock = DefaultCatalog.fleets()
        val tile = stock.first { it.rules.isNotEmpty() && it.kind == SignatureClass.FINDER }
        val theirs = tile.copy(name = "Theirs", publisher = "example.com", builtIn = true)
        val own = tile.copy(id = "my-radio", name = "My radio", builtIn = false)
        val rsi = SignaturePack(format = SignaturePack.RSI_FORMAT, catalogVersion = 1, publisher = "example.com", fleets = listOf(theirs, own))
        val parsed = SignaturePackValidation.parsed(SignatureExchange.encode(rsi))
        assertFalse(parsed.newer)
        val merged = SignaturePackValidation.merge(stock, parsed.pack).associateBy { it.id }
        // Another publisher's row sits beside the bundled one and never counts as bundled.
        assertEquals(tile.name, merged.getValue(tile.id).name)
        assertEquals("Theirs", merged.getValue("example.com:${tile.id}").name)
        assertFalse(merged.getValue("example.com:${tile.id}").builtIn)
        assertFalse(merged.getValue("my-radio").builtIn)

        val newer = SignaturePackValidation.parsed(SignatureExchange.encode(rsi.copy(formatVersion = 2)))
        assertTrue(newer.newer)
        // A bundled row named by its publisher is still the bundled row: an older catalog cannot replace it.
        val old = rsi.copy(fleets = listOf(tile.copy(publisher = SignaturePack.PUBLISHER)))
        assertTrue(runCatching { SignaturePackValidation.parsed(SignatureExchange.encode(old)) }.isFailure)
        assertEquals(tile.id, SignaturePackValidation.parsed(SignatureExchange.encode(old.copy(catalogVersion = SignaturePackValidation.STOCK_VERSION))).pack.fleets.single().id)
    }
    @Test fun unknownRulesAndClassesAreKeptButNeverMatch() {
        val text = """{"format":"radio-signatures","formatVersion":1,"fleets":[
            {"id":"theirs-1","name":"Classic speaker","kind":"SUBMARINE","matchAny":true,"rules":[
              {"kind":"NAME_CONTAINS","text":"Boom","radio":"BT_CLASSIC"},{"kind":"SONAR_PING","text":"x"}]}]}"""
        val pack = SignaturePackValidation.parsed(text).pack
        val fleet = pack.fleets.single()
        assertEquals(SignatureClass.OTHER, fleet.kind)
        assertTrue(fleet.rules.all { it.foreign != null })
        assertFalse(fleet.visibleOnPhone())
        val back = SignaturePackValidation.parsed(SignatureExchange.encode(pack)).pack.fleets.single()
        assertEquals("SUBMARINE", back.kindRaw)
        assertEquals(fleet.rules, back.rules)
    }
    @Test fun rowsDroppedFromTheCatalogDoNotComeBackFromASavedPack() {
        val stock = DefaultCatalog.fleets()
        val novatel = stock.first().copy(id = "fleet-novatel", name = "Novatel Wireless", builtIn = true)
        val own = stock.first().copy(id = "my-local-signature", name = "Mine", builtIn = false)
        val saved = SignatureExchange.pack(stock + novatel + own, 98, "1.3.0", "")
        val loaded = SignaturePackValidation.overStock(SignaturePackValidation.parse(SignatureExchange.encode(saved), saved = true)).map { it.id }.toSet()
        assertFalse("fleet-novatel" in loaded)
        assertTrue("my-local-signature" in loaded)
        // The owner's own copy of it stays.
        val mine = SignatureExchange.pack(listOf(novatel.copy(builtIn = false)), 98, "1.3.0", "")
        assertTrue("fleet-novatel" in SignaturePackValidation.overStock(mine).map { it.id })
    }
    @Test fun crashNoteRedactsAddressesUrlsAndCoordinatePrecision() {
        val raw = "Error 00:11:22:33:44:55 at 12.345678,-77.123456 https://example.test/key-secret\n at app.sotreus.Main.onCreate(Main.kt:12)"
        val safe = CrashNotes.redact(raw)
        assertFalse(safe.contains("00:11:22")); assertFalse(safe.contains("key-secret")); assertFalse(safe.contains("12.345678"))
        assertTrue(safe.contains("Main.onCreate"))
    }
}
