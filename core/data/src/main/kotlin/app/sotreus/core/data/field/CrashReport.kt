/* Adapted from Fieldwatch ddc0d8c. Copyright 2026 Off Grid Pete LLC. MIT; see licenses/MIT-Fieldwatch.txt. */
package app.sotreus.core.data.field

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What a crash note may say. Ordinary process exits are not crashes.
 * The note is phone model, Android version, and the stack. No radios and no GPS.
 */
object CrashReport {
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5

    /** Null when this exit should not be shown. */
    fun kind(reason: Int, status: Int): String? = when (reason) {
        REASON_CRASH -> "Java crash"
        REASON_CRASH_NATIVE -> if (status > 0) "Native crash, signal $status" else "Native crash"
        else -> null
    }

    fun showExit(reason: Int, status: Int, atMs: Long, seenAtMs: Long): Boolean {
        if (kind(reason, status) == null) return false
        return atMs > seenAtMs
    }

    fun timeLabel(atMs: Long): String =
        TIME.format(Instant.ofEpochMilli(atMs))

    fun text(
        versionName: String,
        versionCode: Int,
        manufacturer: String,
        model: String,
        androidRelease: String,
        sdk: Int,
        atLabel: String,
        reason: String,
        detail: String,
    ): String = buildString {
        append("Sotreus ")
        append(versionName)
        append(" (")
        append(versionCode)
        append(")\n")
        append(manufacturer.trim())
        append(' ')
        append(model.trim())
        append('\n')
        append("Android ")
        append(androidRelease)
        append(" (SDK ")
        append(sdk)
        append(")\n")
        append(atLabel)
        append('\n')
        append(reason)
        val body = detail.trim()
        if (body.isNotEmpty()) {
            append("\n\n")
            append(body)
        }
    }

    /** Stack frames and the signal line. Memory dumps and other tombstone lines stay out. */
    fun traceLines(raw: String): String {
        val kept = ArrayList<String>(64)
        for (line in raw.lineSequence()) {
            if (kept.size >= 80) break
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.length > 220) continue
            if (!keepTraceLine(trimmed)) continue
            kept.add(trimmed)
        }
        return kept.joinToString("\n").take(6_000)
    }

    fun usableText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        var odd = 0
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c == 9 || c == 10 || c == 13 || c in 32..126) continue
            odd++
        }
        if (odd * 10 > bytes.size) return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun keepTraceLine(line: String): Boolean {
        if (line.startsWith("at ")) return true
        if (line.startsWith("Caused by:")) return true
        if (line.startsWith("Suppressed:")) return true
        if (line.startsWith("#") && " pc " in line) return true
        if (line.startsWith("backtrace")) return true
        if (line.startsWith("Abort message")) return true
        if (line.startsWith("signal ")) return true
        if ("Exception" in line || "Error" in line) return true
        return false
    }

    private val TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
}
