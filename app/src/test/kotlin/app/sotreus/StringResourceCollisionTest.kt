package app.sotreus

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android merges every module's resources into one namespace, so two modules defining the same
 * string name with different text silently show the wrong copy. This keeps names unique.
 */
class StringResourceCollisionTest {
    @Test
    fun noStringNameHasTwoDifferentValues() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val seen = HashMap<String, MutableSet<Pair<String, String>>>()
        root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".git", "design", "docs") }
            .filter { it.name == "strings.xml" && it.parentFile.name == "values" }
            .forEach { file ->
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                listOf("string", "plurals").forEach { tag ->
                    val nodes = doc.getElementsByTagName(tag)
                    for (i in 0 until nodes.length) {
                        val el = nodes.item(i) as org.w3c.dom.Element
                        seen.getOrPut(el.getAttribute("name")) { mutableSetOf() } += file.relativeTo(root).path to el.textContent.trim()
                    }
                }
            }
        val conflicts = seen.filterValues { entries -> entries.map { it.second }.toSet().size > 1 }
        assertTrue(conflicts.entries.joinToString("\n") { (k, v) -> "$k: $v" }, conflicts.isEmpty())
    }
}
