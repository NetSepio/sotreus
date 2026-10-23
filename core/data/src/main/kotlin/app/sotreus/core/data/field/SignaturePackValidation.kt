package app.sotreus.core.data.field

import app.sotreus.intelligence.fieldwatch.*

/** Imports may extend this catalog, but cannot silently restore obsolete stock matches. */
object SignaturePackValidation {
    const val STOCK_VERSION = 96
    const val MAX_BYTES = 4_194_304

    fun parse(text: String): SignaturePack {
        require(text.toByteArray().size <= MAX_BYTES) { "Signature pack is too large" }
        val p = SignatureExchange.parsePack(text).pack
        require(p.formatVersion == SignaturePack.FORMAT_VERSION) { "Unsupported signature pack version" }
        require(p.fleets.size <= 1_000 && p.fleets.map { it.id }.distinct().size == p.fleets.size && p.fleets.all { f ->
            f.id.isNotBlank() && f.id.length <= 200 && f.name.length <= 200 && f.rules.size in 1..4_096 &&
                f.rules.all { it.text.length <= 1_024 && it.dataPrefixHex.length <= 1_024 } &&
                (f.decode?.fields?.size ?: 0) <= 128
        }) { "Signature pack exceeds the supported limits or has duplicate identifiers" }
        val stockIds = DefaultCatalog.fleets().map { it.id }.toSet()
        require(p.catalogVersion >= STOCK_VERSION || p.fleets.none { it.id in stockIds }) { "This pack would replace newer bundled signatures" }
        return p
    }
}
