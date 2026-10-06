package app.sotreus

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Copy guardrail (HANDOFF_V1_UI.md §8): scans every module's `strings.xml` for alarmist phrases.
 * The literal "Not a threat score." is the one allowed exception.
 */
class BannedCopyTest {

    private val banned = listOf(
        "threat", "spy", "tracked", "tracking you", "surveillance detected", "area is clean",
        "safe area", "unsafe", "verified location", "you are being",
    )
    private val allowed = listOf("not a threat score.")

    @Test
    fun noBannedPhrasesInUserFacingStrings() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val files = root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".git", "design", "docs") }
            .filter { it.name == "strings.xml" && it.parentFile.name.startsWith("values") }
            .toList()
        assertTrue("found no strings.xml under $root", files.isNotEmpty())

        val violations = files.flatMap { file ->
            stringValues(file).mapNotNull { (name, value) ->
                var text = value.lowercase()
                allowed.forEach { text = text.replace(it, "") }
                banned.firstOrNull { it in text }
                    ?.let { "${file.relativeTo(root)}: $name contains \"$it\": \"$value\"" }
            }
        }
        assertTrue(violations.joinToString(separator = "\n", prefix = "Banned copy:\n"), violations.isEmpty())
    }

    private fun stringValues(file: File): List<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableListOf<Pair<String, String>>()
        for (tag in listOf("string", "item")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                val name = el.getAttribute("name").ifEmpty { (el.parentNode as Element).getAttribute("name") }
                out += name to el.textContent
            }
        }
        return out
    }
}
