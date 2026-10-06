package app.sotreus.intelligence

import app.sotreus.core.model.ProximityBand
import kotlin.math.PI

/**
 * Proximity Bands placement (HANDOFF_V1_UI.md §6.3). The ring comes from signal strength; the
 * angle is a stable hash of the entity id so dots don't jump. It is never a bearing.
 */
object BandLayout {
    const val MAX_DOTS = 120

    fun angleRadians(entityId: String): Double {
        // FNV-1a: stable across runs and devices, unlike String.hashCode on some inputs.
        var h = 0x811C9DC5.toInt()
        entityId.forEach { h = (h xor it.code) * 0x01000193 }
        return ((h.toLong() and 0xFFFFFFFFL).toDouble() / 0x1_0000_0000L) * 2 * PI
    }

    /** Radial position inside the band, 0 = inner edge, 1 = outer edge, also hash-stable. */
    fun radialJitter(entityId: String): Double {
        var h = 0x01000193
        entityId.reversed().forEach { h = (h xor it.code) * 0x811C9DC5.toInt() }
        return 0.2 + 0.6 * ((h.toLong() and 0xFFFFL).toDouble() / 0xFFFF)
    }

    fun band(avgDbm: Double): ProximityBand = ProximityBand.of(avgDbm)
}
