package app.sotreus.core.data.field

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import app.sotreus.core.model.AppBuildInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Local recovery notes, offered before observation restarts. Nothing is uploaded. */
object CrashNotes {
    private val writing = AtomicBoolean(false)
    private fun file(c: Context) = File(c.filesDir, "crash-note.txt")
    private fun seen(c: Context) = c.getSharedPreferences("crash-recovery", Context.MODE_PRIVATE)

    fun install(app: Application, build: AppBuildInfo) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (writing.compareAndSet(false, true)) runCatching {
                val text = CrashReport.text(build.versionName, build.versionCode.toInt(), Build.MANUFACTURER, Build.MODEL,
                    Build.VERSION.RELEASE, Build.VERSION.SDK_INT, CrashReport.timeLabel(System.currentTimeMillis()),
                    "Java crash", redact(error.stackTraceToString().take(12_000)))
                file(app).outputStream().use { out -> out.write(text.toByteArray()); out.fd.sync() }
            }
            if (previous != null) previous.uncaughtException(thread, error) else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun pending(context: Context): String? {
        val own = file(context)
        if (own.isFile) runCatching { own.readText().take(16_000).takeIf(String::isNotBlank) }.getOrNull()?.let { return it }
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return@runCatching null
            if (!CrashReport.showExit(exit.reason, exit.status, exit.timestamp, seen(context).getLong("seen", 0))) return@runCatching null
            val trace = exit.traceInputStream?.use { input ->
                val bytes = ByteArray(8_192); val count = input.read(bytes)
                if (count > 0) CrashReport.usableText(bytes.copyOf(count))?.let(CrashReport::traceLines) else null
            }.orEmpty()
            "Sotreus · ${CrashReport.timeLabel(exit.timestamp)}\n${CrashReport.kind(exit.reason, exit.status)}\n${redact(trace)}"
        }.getOrNull()
    }

    fun dismiss(context: Context) { file(context).delete(); seen(context).edit().putLong("seen", System.currentTimeMillis()).apply() }

    internal fun redact(raw: String): String = raw
        .replace(Regex("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}"), "[address]")
        .replace(Regex("https?://[^\\s)]+"), "[URL]")
        .replace(Regex("(?<![0-9])[-+]?[0-9]{1,3}\\.[0-9]{4,}"), "[coordinate]")
        .take(12_000)
}
