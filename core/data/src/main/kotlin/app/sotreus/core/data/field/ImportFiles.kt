package app.sotreus.core.data.field

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Treat document providers as untrusted; never read an unbounded import into memory. */
suspend fun readImport(context: Context, uri: Uri, maxBytes: Int = 1_048_576): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream(); val chunk = ByteArray(8_192)
        while (true) {
            val n = input.read(chunk); if (n < 0) break
            require(output.size() + n <= maxBytes) { "File is too large" }
            output.write(chunk, 0, n)
        }
        output.toString(Charsets.UTF_8.name())
    } ?: error("Could not open this file")
}
