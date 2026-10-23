package app.sotreus.core.data.field

/** Translation text cannot invent keys or alter printf argument types. */
object TranslationValidation {
    fun validate(english: Map<String, String>, lines: Map<String, String>): Map<String, String> {
        require(lines.size <= 10_000)
        val tokens = Regex("%(?:[0-9]+\\$)?[-+ 0,(]*[0-9]*(?:\\.[0-9]+)?[a-zA-Z%]")
        return lines.filter { (key, value) ->
            val source = english[key] ?: english[key.substringBefore('#') + "#other"]
            source != null && value.isNotBlank() && value.length <= 4_096 && value.none { it.isISOControl() && it != '\n' && it != '\t' } &&
                tokens.findAll(source).map { it.value }.sorted().toList() == tokens.findAll(value).map { it.value }.sorted().toList() &&
                tokens.replace(source, "").count { it == '%' } == tokens.replace(value, "").count { it == '%' }
        }
    }
}
