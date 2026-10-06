package app.sotreus.intelligence

/**
 * Copy guardrail for text that is generated at runtime (ported explanations, catalog names) and so
 * never passes through strings.xml or the banned-copy test (HANDOFF_V1_UI.md §8).
 */
object CalmCopy {
    val banned = listOf(
        "threat", "spy", "tracked", "tracking you", "surveillance detected", "area is clean",
        "safe area", "unsafe", "verified location", "you are being",
    )
    private val allowed = listOf("not a threat score.")

    fun isCalm(text: String): Boolean {
        var t = text.lowercase()
        allowed.forEach { t = t.replace(it, "") }
        return banned.none { it in t }
    }

    fun orFallback(text: String, fallback: String): String = if (isCalm(text)) text else fallback
}
