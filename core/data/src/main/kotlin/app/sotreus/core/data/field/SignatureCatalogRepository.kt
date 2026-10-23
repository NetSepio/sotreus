package app.sotreus.core.data.field

import android.content.Context
import app.sotreus.intelligence.SignatureClassifier
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
class SignatureCatalogRepository @Inject constructor(@ApplicationContext context: Context, private val classifier: SignatureClassifier) {
    private val file = File(context.filesDir, "signature-pack.json")
    private val mutex = Mutex()
    @Volatile private var catalogVersion = STOCK_VERSION
    private val _count = MutableStateFlow(classifier.catalogSize)
    val count = _count.asStateFlow()
    private val _fleets = MutableStateFlow(classifier.fleets)
    val fleets = _fleets.asStateFlow()
    init {
        if (file.length() in 1..MAX_BYTES.toLong()) runCatching {
            val pack = preview(file.readText())
            apply(pack)
        }
    }

    fun preview(text: String): SignaturePack = SignaturePackValidation.parse(text)
    suspend fun import(text: String): Int = withContext(Dispatchers.IO) { mutex.withLock {
        val p = preview(text)
        val current = classifier.fleets.associateBy { it.id }.toMutableMap()
        p.fleets.forEach { current[it.id] = it }
        val next = SignatureExchange.pack(current.values.toList(), maxOf(catalogVersion, p.catalogVersion, STOCK_VERSION), "1.1.1", Instant.now().toString())
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(SignatureExchange.encode(next)); check(temp.renameTo(file)) { "Could not save signatures" }
        apply(next); _count.value
    } }
    suspend fun restore() = withContext(Dispatchers.IO) { mutex.withLock { check(!file.exists() || file.delete()) { "Could not remove imported signatures" }; catalogVersion = STOCK_VERSION; classifier.replaceFleets(DefaultCatalog.fleets()); _fleets.value = classifier.fleets; _count.value = classifier.catalogSize } }
    fun export(): String = SignatureExchange.encode(SignatureExchange.pack(classifier.fleets, catalogVersion, "1.1.1", Instant.now().toString()))
    private fun apply(p: SignaturePack) {
        catalogVersion = maxOf(STOCK_VERSION, p.catalogVersion)
        val stock = DefaultCatalog.fleets().associateBy { it.id }.toMutableMap()
        p.fleets.forEach { if (p.catalogVersion >= STOCK_VERSION || it.id !in stock) stock[it.id] = it }
        classifier.replaceFleets(stock.values.toList()); _fleets.value = classifier.fleets; _count.value = classifier.catalogSize
    }
    companion object { const val STOCK_VERSION = SignaturePackValidation.STOCK_VERSION; const val MAX_BYTES = SignaturePackValidation.MAX_BYTES }
}
