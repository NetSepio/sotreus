package app.sotreus.feature.context.scene

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Earth-centred, Earth-fixed coordinates in Earth radii: x towards 0° E on the equator, y towards
 * 90° E, z towards the North Pole. Everything in the Context scene lives in this one frame, so the
 * camera can fly from the whole globe down to a plate a few kilometres across.
 */
internal data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = V3(x * k, y * k, z * k)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length get() = sqrt(this dot this)
    fun unit(): V3 = length.let { if (it < 1e-12) this else this * (1 / it) }

    companion object {
        val ZERO = V3(0.0, 0.0, 0.0)
    }
}

internal const val EARTH_KM = 6371.0

internal fun ecef(latDeg: Double, lonDeg: Double, r: Double = 1.0): V3 {
    val la = Math.toRadians(latDeg)
    val lo = Math.toRadians(lonDeg)
    return V3(r * cos(la) * cos(lo), r * cos(la) * sin(lo), r * sin(la))
}

/** East, north and up unit vectors at a point. */
internal class Enu(latDeg: Double, lonDeg: Double) {
    private val la = Math.toRadians(latDeg)
    private val lo = Math.toRadians(lonDeg)
    val east = V3(-sin(lo), cos(lo), 0.0)
    val north = V3(-sin(la) * cos(lo), -sin(la) * sin(lo), cos(la))
    val up = V3(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
}

/**
 * Satellite altitudes squeezed so low orbits and geostationary ones both fit on screen
 * (about 400 km → 0.09 R, 36 000 km → 0.29 R). The scene says altitude is not to scale.
 */
internal fun compressedRadius(altKm: Double) = 1.0 + 0.045 * ln(1.0 + altKm.coerceAtLeast(0.0) / 60.0)

/** Unit vector to the sub-solar point (low-precision solar position, plenty for shading). */
internal fun sunDirection(atMs: Long): V3 {
    val d = atMs / 86_400_000.0 - 10_957.5
    val g = Math.toRadians(357.528 + 0.9856003 * d)
    val lambda = Math.toRadians(280.46 + 0.9856474 * d + 1.915 * sin(g) + 0.020 * sin(2 * g))
    val eps = Math.toRadians(23.439 - 4e-7 * d)
    val dec = asin(sin(eps) * sin(lambda))
    val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
    val gmst = Math.toRadians(280.46061837 + 360.98564736629 * d)
    return ecef(Math.toDegrees(dec), Math.toDegrees(ra - gmst))
}

/** Pinhole camera. [project] writes screen x, y and pixels-per-unit at that depth into [out]. */
internal class Camera(val pos: V3, target: V3, upHint: V3, private val focal: Double, private val cx: Double, private val cy: Double) {
    private val f = (target - pos).unit()
    private val r = (f cross upHint).unit()
    private val u = r cross f

    fun project(x: Double, y: Double, z: Double, out: FloatArray): Boolean {
        val vx = x - pos.x
        val vy = y - pos.y
        val vz = z - pos.z
        val depth = vx * f.x + vy * f.y + vz * f.z
        if (depth < 1e-7) return false
        val k = focal / depth
        out[0] = (cx + k * (vx * r.x + vy * r.y + vz * r.z)).toFloat()
        out[1] = (cy - k * (vx * u.x + vy * u.y + vz * u.z)).toFloat()
        out[2] = k.toFloat()
        return true
    }

    fun project(p: V3, out: FloatArray) = project(p.x, p.y, p.z, out)

    /** A point on the unit sphere faces the camera. */
    fun facing(x: Double, y: Double, z: Double) = x * (pos.x - x) + y * (pos.y - y) + z * (pos.z - z) > 0

    /** The Earth is between the camera and [p]. */
    fun hidden(p: V3): Boolean {
        val d = p - pos
        val a = d dot d
        val b = 2 * (pos dot d)
        val c = (pos dot pos) - 1.0
        val disc = b * b - 4 * a * c
        if (disc <= 0) return false
        val t = (-b - sqrt(disc)) / (2 * a)
        return t > 0 && t < 0.9999
    }

    companion object {
        /**
         * Blends the globe view ([blend] 0) into a tilted view of the plate around the user
         * ([blend] 1). Camera altitude is interpolated logarithmically so the fly-down feels even
         * and the camera never enters the Earth.
         */
        fun build(
            blend: Double,
            userLat: Double,
            userLon: Double,
            yawDeg: Double,
            pitchDeg: Double,
            globeDistance: Double,
            headingDeg: Double,
            tiltDeg: Double,
            plateRadiusKm: Double,
            width: Double,
            height: Double,
        ): Camera {
            val viewLat = (userLat - 28 + pitchDeg).coerceIn(-80.0, 80.0)
            val viewLon = userLon + yawDeg
            val globePos = ecef(viewLat, viewLon, globeDistance)
            val globeUp = Enu(viewLat, viewLon).north

            val enu = Enu(userLat, userLon)
            val h = Math.toRadians(headingDeg)
            val t = Math.toRadians(tiltDeg)
            val northH = enu.north * cos(h) + enu.east * sin(h)
            val user = enu.up
            val localPos = user + (enu.up * cos(t) - northH * sin(t)) * (plateRadiusKm * 2.9 / EARTH_KM)

            val altG = globePos.length - 1
            val altL = localPos.length - 1
            val alt = exp(ln(altG) + (ln(altL) - ln(altG)) * blend)
            val dir = (globePos.unit() * (1 - blend) + localPos.unit() * blend).unit()
            val pos = dir * (1 + alt)
            // The target reaches the user early so the user stays in frame on the way down.
            val target = user * smoothstep(0.0, 0.45, blend)
            val up = (globeUp * (1 - blend) + enu.up * blend).unit()
            val focal = width * 1.25
            return Camera(pos, target, up, focal, width / 2, height / 2)
        }
    }
}

internal fun smoothstep(e0: Double, e1: Double, x: Double): Double {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
}
