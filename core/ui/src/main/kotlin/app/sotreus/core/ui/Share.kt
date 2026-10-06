package app.sotreus.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Hands an exported file to the system share sheet. Nothing is sent by Sotreus itself. */
fun shareFile(context: Context, uri: Uri, title: String, mime: String = "application/json") {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
