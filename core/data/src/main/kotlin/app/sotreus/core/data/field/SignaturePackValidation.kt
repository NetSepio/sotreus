package app.sotreus.core.data.field

import app.sotreus.intelligence.StockCatalog
import app.sotreus.intelligence.fieldwatch.*

/** Imports may extend this catalog, but cannot silently restore obsolete stock matches. */
object SignaturePackValidation {
    const val STOCK_VERSION = StockCatalog.VERSION
    const val MAX_BYTES = 4_194_304

    private val stockIds get() = StockCatalog.ids

    fun parse(text: String, saved: Boolean = false): SignaturePack = parsed(text, saved).pack

    /**
     * Reads a `radio-signatures` (RSI-1) or older Fieldwatch file. A newer RSI file is read too, without
     * the parts this version does not know ([ParsedSignaturePack.newer]). [saved] is the pack this phone
     * wrote itself: an older one still loads, keeping its own signatures.
     */
    fun parsed(text: String, saved: Boolean = false): ParsedSignaturePack {
        require(text.toByteArray().size <= MAX_BYTES) { "Signature pack is too large" }
        val parsed = SignatureExchange.parsePack(text)
        val p = parsed.pack
        require(p.formatVersion == SignaturePack.FORMAT_VERSION || (parsed.newer && p.format == SignaturePack.RSI_FORMAT)) { "Unsupported signature pack version" }
        require(p.fleets.size <= 1_000 && p.fleets.map { it.publisher to it.id }.distinct().size == p.fleets.size && p.fleets.all { f ->
            f.id.isNotBlank() && f.id.length <= 200 && f.name.length <= 200 && f.publisher.length <= 64 && f.rules.size in 1..4_096 &&
                f.rules.all { it.text.length <= 1_024 && it.dataPrefixHex.length <= 1_024 && (it.foreign?.toString()?.length ?: 0) <= 4_096 } &&
                (f.decode?.fields?.size ?: 0) <= 128
        }) { "Signature pack exceeds the supported limits or has duplicate identifiers" }
        require(saved || p.catalogVersion >= STOCK_VERSION || p.fleets.none(::isStock)) { "This pack would replace newer bundled signatures" }
        return parsed
    }

    /** [current] with [p]'s signatures added or replaced. Another publisher's row never takes a bundled one's place. */
    fun merge(current: List<Fleet>, p: SignaturePack): List<Fleet> {
        val out = current.associateByTo(LinkedHashMap()) { it.id }
        p.fleets.forEach { f ->
            val id = storageId(f, out)
            out[id] = f.copy(id = id, builtIn = isStock(f))
        }
        return out.values.toList()
    }

    /** The bundled catalog with a saved pack on top: an older pack's copies of bundled rows give way. */
    fun overStock(p: SignaturePack): List<Fleet> {
        val out = StockCatalog.fleets.associateByTo(LinkedHashMap()) { it.id }
        p.fleets.forEach { f ->
            if (p.catalogVersion >= STOCK_VERSION || (f.id !in out && !(f.builtIn && f.id in StockCatalog.RETIRED))) out[f.id] = f
        }
        return out.values.toList()
    }

    /** A bundled row, from its publisher or from a file that names none. */
    private fun isStock(f: Fleet) = f.exportId() in stockIds && (f.publisher.isEmpty() || f.publisher == SignaturePack.PUBLISHER)

    private fun storageId(f: Fleet, taken: Map<String, Fleet>): String {
        val raw = f.exportId()
        val other = taken[raw] ?: return raw
        val owner = other.publisher.ifEmpty { if (other.builtIn) SignaturePack.PUBLISHER else "" }
        return if (f.publisher.isNotEmpty() && owner.isNotEmpty() && owner != f.publisher) "${f.publisher}:$raw" else raw
    }
}
