package app.sotreus.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Inline icons built from the SVG paths in `design/screens` (24-unit viewport, 1.6 stroke).
 * Drawn in black and tinted by `Icon`. No icon fonts, no emoji.
 */
object SotreusIcons {
    val Now: ImageVector by lazy {
        icon("Now") {
            stroke(circle(12f, 12f, 9f))
            stroke(circle(12f, 12f, 4.5f))
            fill(circle(12f, 12f, 1.2f))
        }
    }

    val History: ImageVector by lazy {
        icon("History") {
            stroke(circle(12f, 12f, 9f))
            stroke("M12 7v5l3 2")
        }
    }

    val Journey: ImageVector by lazy {
        icon("Journey") {
            stroke(circle(6f, 18f, 2f))
            stroke(circle(18f, 6f, 2f))
            stroke("M8 18h3a3 3 0 0 0 3-3V9a3 3 0 0 1 3-3")
        }
    }

    /** Context: a body with an orbit. */
    val Context: ImageVector by lazy {
        icon("Context") {
            stroke(circle(12f, 12f, 3.2f))
            stroke("M5.6 18.4C3.3 16.1 5.6 10 10.8 4.8S18.4 3.3 18.4 5.6 18 10.8 14.4 14.4 7.9 20.7 5.6 18.4z")
            fill(circle(18.6f, 9.2f, 1.3f))
        }
    }

    val Settings: ImageVector by lazy {
        icon("Settings") {
            stroke("M4 7h10M18 7h2M4 17h4M12 17h8")
            stroke(circle(16f, 7f, 2f))
            stroke(circle(10f, 17f, 2f))
        }
    }

    val Back: ImageVector by lazy {
        icon("Back") { stroke("M15 5l-7 7 7 7") }
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") { stroke("M9 5l7 7-7 7") }
    }

    val Clock: ImageVector by lazy {
        icon("Clock") {
            stroke(circle(12f, 12f, 9f))
            stroke("M12 7v5l3 2")
        }
    }

    val Close: ImageVector by lazy { icon("Close") { stroke("M6 6l12 12M18 6L6 18") } }

    val Check: ImageVector by lazy { icon("Check") { stroke("M5 12.5l4.5 4.5L19 7.5") } }

    val More: ImageVector by lazy {
        icon("More") {
            stroke(circle(12f, 5f, 1.6f))
            stroke(circle(12f, 12f, 1.6f))
            stroke(circle(12f, 19f, 1.6f))
        }
    }

    val Edit: ImageVector by lazy { icon("Edit") { stroke("M4 20h4L19 9l-4-4L4 16v4z") } }

    val Search: ImageVector by lazy {
        icon("Search") {
            stroke(circle(11f, 11f, 6.5f))
            stroke("M20 20l-4.2-4.2")
        }
    }

    val Bluetooth: ImageVector by lazy { icon("Bluetooth") { stroke("M7 7l10 10-5 5V2l5 5L7 17") } }

    val Wifi: ImageVector by lazy {
        icon("Wifi") {
            stroke("M2 9a15 15 0 0 1 20 0M5.5 12.5a10 10 0 0 1 13 0M9 16a5 5 0 0 1 6 0")
            fill(circle(12f, 19f, 1f))
        }
    }

    val Location: ImageVector by lazy {
        icon("Location") {
            stroke("M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z")
            stroke(circle(12f, 9.5f, 2.5f))
        }
    }

    val Bell: ImageVector by lazy { icon("Bell") { stroke("M6 10a6 6 0 0 1 12 0c0 6 2 7 2 7H4s2-1 2-7M10 20a2 2 0 0 0 4 0") } }

    /** Sit: a held position. */
    val Sit: ImageVector by lazy {
        icon("Sit") {
            stroke(circle(12f, 12f, 3f))
            addPath(
                pathData = addPathNodes(circle(12f, 12f, 8f)),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
            )
        }
    }

    val Broadcast: ImageVector by lazy {
        icon("Broadcast") {
            stroke("M5 12a7 7 0 0 1 14 0M8.5 12a3.5 3.5 0 0 1 7 0")
            fill(circle(12f, 12f, 1f))
            stroke("M12 13v7")
        }
    }

    val Person: ImageVector by lazy {
        icon("Person") {
            stroke(circle(12f, 8f, 4f))
            stroke("M4 20c1.5-4 4.5-6 8-6s6.5 2 8 6")
        }
    }

    val Lock: ImageVector by lazy {
        icon("Lock") {
            stroke("M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z")
            stroke("M8 11V8a4 4 0 0 1 8 0v3")
        }
    }

    val ShieldCheck: ImageVector by lazy {
        icon("ShieldCheck") {
            stroke("M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z")
            stroke("M9 12l2 2 4-4")
        }
    }
}

private const val VIEWPORT = 24f
private const val STROKE = 1.6f

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "Sotreus.$name",
        defaultWidth = VIEWPORT.dp,
        defaultHeight = VIEWPORT.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply(block).build()

private fun ImageVector.Builder.stroke(pathData: String) {
    addPath(
        pathData = addPathNodes(pathData),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

private fun ImageVector.Builder.fill(pathData: String) {
    addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
}

/** SVG `<circle>` as path data: two half arcs. */
private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
