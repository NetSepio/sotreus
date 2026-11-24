package app.sotreus.core.data.field

import android.content.Context
import app.sotreus.core.model.AppBuildInfo
import app.sotreus.intelligence.SignatureClassifier
import app.sotreus.intelligence.StockCatalog
import app.sotreus.intelligence.fieldwatch.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignatureCatalogRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val classifier: SignatureClassifier,
    private val build: AppBuildInfo,
) {
    private val file = File(context.filesDir, "signature-pack.json")
    private val mutex = Mutex()
    private val _version = MutableStateFlow(STOCK_VERSION)

    /** The catalog in use: the bundled one, or a newer imported one. */
    val version = _version.asStateFlow()
    private val _count = MutableStateFlow(classifier.catalogSize)
    val count = _count.asStateFlow()
    private val _fleets = MutableStateFlow(classifier.fleets)
    val fleets = _fleets.asStateFlow()
    init {
        if (file.length() in 1..MAX_BYTES.toLong()) runCatching {
            val pack = SignaturePackValidation.parse(file.readText(), saved = true)
            apply(pack)
        }
    }

    fun preview(text: String): ParsedSignaturePack = SignaturePackValidation.parsed(text)
    suspend fun import(text: String): Int = withContext(Dispatchers.IO) { mutex.withLock {
        val p = preview(text).pack
        val merged = SignaturePackValidation.merge(classifier.fleets, p)
        val next = SignatureExchange.pack(merged, maxOf(_version.value, p.catalogVersion, STOCK_VERSION), build.versionName, Instant.now().toString())
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(SignatureExchange.encode(next)); check(temp.renameTo(file)) { "Could not save signatures" }
        apply(next); _count.value
    } }
    suspend fun restore() = withContext(Dispatchers.IO) { mutex.withLock {
        check(!file.exists() || file.delete()) { "Could not remove imported signatures" }
        use(StockCatalog.fleets, STOCK_VERSION)
    } }

    /** RSI-1 (`radio-signatures`): bundled rows name their publisher, the owner's own rows stay unnamed. */
    fun export(): String = SignatureExchange.encode(
        SignatureExchange.exportPack(classifier.fleets, _version.value, build.versionName, Instant.now().toString()).copy(publisher = PUBLISHER),
    )

    private fun apply(p: SignaturePack) = use(SignaturePackValidation.overStock(p), maxOf(STOCK_VERSION, p.catalogVersion))
    private fun use(next: List<Fleet>, catalog: Int) {
        _version.value = catalog
        classifier.replaceFleets(next); _fleets.value = classifier.fleets; _count.value = classifier.catalogSize
    }
    companion object {
        const val STOCK_VERSION = SignaturePackValidation.STOCK_VERSION
        const val MAX_BYTES = SignaturePackValidation.MAX_BYTES

        /** Names Sotreus as the publisher of an exported file (RSI-1 §8). */
        const val PUBLISHER = "sotreus"
        const val EXPORT_NAME = "sotreus-signatures.rsi"
    }
}
