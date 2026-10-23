package app.sotreus.core.data.field

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.intelligence.fieldwatch.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SotreusLanguageFile(
    val format: String = "sotreus-language", val formatVersion: Int = 1,
    val language: String, val languageTag: String = "en", val writtenFor: String = "1.1.1",
    val strings: Map<String, String>,
)
data class AppliedLanguage(val tag: String = "en", val name: String = "English", val count: Int = 0, val revision: Int = 0, val localeTag: String = "en")

/** Partial packs retain English for unknown keys and incompatible format arguments. */
@Singleton
class LanguageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val custom = File(context.filesDir, "language-custom.json")
    private val _state = MutableStateFlow(AppliedLanguage())
    val state = _state.asStateFlow()
    @Volatile private var overlay: Map<String, String> = emptyMap()
    private val english: Map<String, String> by lazy {
        json.decodeFromString<SotreusLanguageFile>(context.assets.open("languages/english.json").bufferedReader().use { it.readText() }).strings
    }

    init {
        scope.launch(Dispatchers.IO) {
            settings.settings.map { it.languageTag }.distinctUntilChanged().collect { tag ->
                runCatching { load(tag) }.onFailure { apply("en", "English", emptyMap()) }
            }
        }
    }

    suspend fun select(tag: String) { require(tag == "en" || tag == "custom" || BundledLanguages.all.any { it.tag == tag }); settings.setLanguageTag(tag) }

    suspend fun import(text: String): Int = withContext(Dispatchers.IO) {
        require(text.toByteArray().size <= 524_288) { "Language pack is too large" }
        val pack = json.decodeFromString<SotreusLanguageFile>(text)
        require(pack.format == "sotreus-language" && pack.formatVersion == 1 && pack.language.isNotBlank() && pack.language.length <= 80) { "Use a Sotreus language template" }
        val lines = validate(pack.strings)
        require(lines.isNotEmpty()) { "No compatible translations were found" }
        val safe = pack.copy(language = pack.language.filterNot(Char::isISOControl), strings = lines)
        val temp = File(custom.parentFile, custom.name + ".tmp")
        temp.writeText(json.encodeToString(safe)); check(temp.renameTo(custom)) { "Could not save language pack" }
        settings.setLanguageTag("custom"); apply("custom", safe.language, lines, safe.languageTag)
        lines.size
    }

    fun template(): String = json.encodeToString(SotreusLanguageFile(language = "English template — replace with your language", strings = english))

    fun text(resources: Resources, id: Int, raw: CharSequence, quantity: Int? = null): CharSequence {
        if (overlay.isEmpty()) return raw
        val name = runCatching { resources.getResourceEntryName(id) }.getOrNull() ?: return raw
        val key = if (quantity == null) name else {
            val tag = state.value.localeTag
            val form = android.icu.text.PluralRules.forLocale(Locale.forLanguageTag(tag)).select(quantity.toDouble())
            "$name#$form"
        }
        return overlay[key] ?: if (quantity != null) overlay["$name#other"] ?: raw else raw
    }

    private fun load(tag: String) {
        if (tag == "en") { apply(tag, "English", emptyMap()); return }
        require(tag == "custom" || BundledLanguages.all.any { it.tag == tag })
        val pack = if (tag == "custom") json.decodeFromString<SotreusLanguageFile>(custom.readText()) else
            json.decodeFromString<SotreusLanguageFile>(context.assets.open("languages/$tag.json").bufferedReader().use { it.readText() })
        apply(tag, pack.language, validate(pack.strings), pack.languageTag)
    }
    private fun validate(lines: Map<String, String>) = TranslationValidation.validate(english, lines)
    private fun apply(tag: String, name: String, lines: Map<String, String>, localeTag: String = tag) {
        overlay = lines
        _state.value = AppliedLanguage(tag, name, lines.size, _state.value.revision + 1, localeTag.takeIf { it.length <= 35 && it.matches(Regex("[A-Za-z0-9-]+")) } ?: "en")
    }
}

/** Resource overlay keeps every existing stringResource call and native screen accessible. */
@Suppress("DEPRECATION")
fun languageContext(base: Context, languages: LanguageRepository): Context {
    val original = base.resources
    val config = Configuration(original.configuration)
    val tag = languages.state.value.localeTag
    config.setLocale(Locale.forLanguageTag(tag))
    val localized = object : Resources(original.assets, original.displayMetrics, config) {
        override fun getText(id: Int): CharSequence = languages.text(original, id, original.getText(id))
        override fun getQuantityText(id: Int, quantity: Int): CharSequence = languages.text(original, id, original.getQuantityText(id, quantity), quantity)
    }
    return object : ContextWrapper(base) { override fun getResources(): Resources = localized }
}
