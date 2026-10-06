package app.sotreus.feature.context.scene

import android.content.Context
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A coastline polyline as ECEF unit vectors, with its lat/lon bounds for culling. */
internal class CoastLine(val xyz: DoubleArray, val latMin: Double, val latMax: Double, val lonMin: Double, val lonMax: Double)

/**
 * Offline globe geometry from Natural Earth (public domain), packed by
 * tools/geodata/build_globe_assets.py: land as evenly spaced dots, and 1:50m coastlines.
 */
internal class GlobeData(val land: DoubleArray, val coast: List<CoastLine>) {
    companion object {
        @Volatile private var cached: GlobeData? = null

        fun load(context: Context): GlobeData = cached ?: synchronized(this) {
            cached ?: GlobeData(readLand(context), readCoast(context)).also { cached = it }
        }

        private fun buffer(context: Context, name: String): ByteBuffer =
            ByteBuffer.wrap(DataInputStream(context.assets.open(name)).use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)

        private fun lat(v: Short) = v * 90.0 / 32767
        private fun lon(v: Short) = v * 180.0 / 32767

        private fun readLand(context: Context): DoubleArray {
            val b = buffer(context, "globe/land_dots.bin")
            val n = b.int
            val out = DoubleArray(n * 3)
            repeat(n) { i ->
                val p = ecef(lat(b.short), lon(b.short))
                out[i * 3] = p.x
                out[i * 3 + 1] = p.y
                out[i * 3 + 2] = p.z
            }
            return out
        }

        private fun readCoast(context: Context): List<CoastLine> {
            val b = buffer(context, "globe/coast_50m.bin")
            return List(b.int) {
                val n = b.int
                val xyz = DoubleArray(n * 3)
                var la0 = 90.0
                var la1 = -90.0
                var lo0 = 180.0
                var lo1 = -180.0
                repeat(n) { i ->
                    val la = lat(b.short)
                    val lo = lon(b.short)
                    la0 = minOf(la0, la)
                    la1 = maxOf(la1, la)
                    lo0 = minOf(lo0, lo)
                    lo1 = maxOf(lo1, lo)
                    val p = ecef(la, lo)
                    xyz[i * 3] = p.x
                    xyz[i * 3 + 1] = p.y
                    xyz[i * 3 + 2] = p.z
                }
                CoastLine(xyz, la0, la1, lo0, lo1)
            }
        }
    }
}
