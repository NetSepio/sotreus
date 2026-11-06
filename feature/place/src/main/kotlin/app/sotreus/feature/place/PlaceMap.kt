package app.sotreus.feature.place

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Hands the coordinates to the user's own maps app (geo: URI). Nothing is sent by Sotreus. */
internal fun openInMaps(context: Context, lat: Double, lon: Double, label: String, chooserTitle: String) {
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), chooserTitle)) }
}

internal fun formatCoord(v: Double): String = "%.5f".format(v)
