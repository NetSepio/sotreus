package app.sotreus.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.sotreus.intelligence.fieldwatch.HuntStepDetector
import app.sotreus.intelligence.fieldwatch.HuntTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Steps, heading and tilt for the signal map, while collected (foreground only). Steps come from
 * the walking bounce on the accelerometer, so no activity-recognition permission is needed. Nothing
 * here is stored; the readings only move the dot on the map.
 */
class WalkSensors @Inject constructor(@ApplicationContext private val context: Context) {
    sealed interface Event {
        val atMs: Long
        data class Step(override val atMs: Long) : Event
        /** [compassDeg] clockwise from north; [gameDeg] is the gyro yaw when the phone has one. */
        data class Heading(override val atMs: Long, val compassDeg: Double, val gameDeg: Double?, val magHealthy: Boolean) : Event
        data class Tilt(override val atMs: Long, val degFromFlat: Double) : Event
    }

    private val manager: SensorManager? get() = context.getSystemService(SensorManager::class.java)

    /** True when the phone can follow a walk: an accelerometer and a compass. */
    fun available(): Boolean = manager?.let {
        it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null && it.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
    } == true

    fun events(): Flow<Event> = callbackFlow {
        val sm = manager
        val steps = HuntStepDetector()
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        var gameDeg: Double? = null
        var magHealthy = true
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val now = System.currentTimeMillis()
                when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                        val g = sqrt(x * x + y * y + z * z)
                        if (steps.sample(g, now)) trySend(Event.Step(now))
                        if (g > 0.1f) trySend(Event.Tilt(now, Math.toDegrees(acos((z / g).coerceIn(-1f, 1f).toDouble()))))
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                        magHealthy = sqrt(x * x + y * y + z * z).toDouble() in HuntTrack.MAG_MIN_UT..HuntTrack.MAG_MAX_UT
                    }
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> gameDeg = yaw(e.values, rotation, orientation)
                    Sensor.TYPE_ROTATION_VECTOR -> trySend(Event.Heading(now, yaw(e.values, rotation, orientation), gameDeg, magHealthy))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) magHealthy = false
            }
        }
        if (sm != null) {
            listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR)
                .mapNotNull { sm.getDefaultSensor(it) }
                .forEach { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        awaitClose { sm?.unregisterListener(listener) }
    }

    private fun yaw(vector: FloatArray, rotation: FloatArray, orientation: FloatArray): Double {
        SensorManager.getRotationMatrixFromVector(rotation, vector)
        SensorManager.getOrientation(rotation, orientation)
        return (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
    }
}
