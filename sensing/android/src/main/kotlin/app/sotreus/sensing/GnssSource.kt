/*
 * Adapted from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit ddc0d8c).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: integrated with observation ownership and Android 9 compatibility.
 */
package app.sotreus.sensing

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurement
import android.os.Build
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import app.sotreus.intelligence.fieldwatch.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/** Measurements are collected only while this flow has an active, visible owner or session. */
class GnssSource @Inject constructor(@ApplicationContext private val context: Context) {
    sealed interface Event {
        data class Epoch(val value: GnssEpoch) : Event
        data class Fix(val value: Location) : Event
        data class State(val text: String, val paused: Boolean = false) : Event
    }

    @SuppressLint("MissingPermission")
    fun events() = callbackFlow<Event> {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null) { trySend(Event.State("GNSS unavailable")); close(); return@callbackFlow }
        val handler = Handler(Looper.getMainLooper())
        var lastEpoch = SystemClock.elapsedRealtime()
        val measurement = object : GnssMeasurementsEvent.Callback() {
            @Suppress("DEPRECATION")
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                runCatching {
                    lastEpoch = SystemClock.elapsedRealtime()
                    val rows = event.measurements.groupBy { BandKey(it.constellationType,
                        GnssBands.ofHz(if (it.hasCarrierFrequencyHz()) it.carrierFrequencyHz.toDouble() else null)) }
                    val eventAgc = if (Build.VERSION.SDK_INT >= 33) event.gnssAutomaticGainControls
                        .filter { it.levelDb.isFinite() && it.levelDb >= -80.0 }
                        .associate { BandKey(it.constellationType, GnssBands.ofHz(it.carrierFrequencyHz.toDouble())) to it.levelDb }
                        else emptyMap()
                    val bands = (rows.keys + eventAgc.keys).associateWith { key ->
                        val group = rows[key].orEmpty()
                        val locked = group.filter { it.cn0DbHz.isFinite() && it.cn0DbHz > 0 && it.state and GnssMeasurement.STATE_CODE_LOCK != 0 }
                        val agc = group.filter { it.hasAutomaticGainControlLevelDb() }.map { it.automaticGainControlLevelDb }
                            .filter { it.isFinite() && it >= -80.0 }.sorted()
                        val median = if (agc.isEmpty()) null else if (agc.size % 2 == 0) (agc[agc.size / 2 - 1] + agc[agc.size / 2]) / 2 else agc[agc.size / 2]
                        BandEpoch(eventAgc[key] ?: median, key in eventAgc,
                            locked.takeIf { it.isNotEmpty() }?.map { it.cn0DbHz }?.sortedDescending()?.take(3)?.average(),
                            locked.size, locked.associate { it.svid to it.cn0DbHz })
                    }
                    trySend(Event.Epoch(GnssEpoch(lastEpoch, System.currentTimeMillis(), event.clock.hardwareClockDiscontinuityCount, bands)))
                }.onFailure { trySend(Event.State("GNSS measurement unavailable", paused = true)) }
            }
        }
        val status = object : GnssStatusCompat.Callback() {
            override fun onStopped() { trySend(Event.State("GNSS paused by the phone", paused = true)) }
            override fun onStarted() { trySend(Event.State("GNSS receiver started")) }
        }
        val listener = LocationListenerCompat { fix -> trySend(Event.Fix(Location(fix))) }
        val registered = runCatching {
            LocationManagerCompat.registerGnssMeasurementsCallback(lm, ContextCompat.getMainExecutor(context), measurement)
        }.getOrDefault(false)
        if (!registered) trySend(Event.State("Raw GNSS measurements are unavailable on this phone"))
        runCatching { LocationManagerCompat.registerGnssStatusCallback(lm, ContextCompat.getMainExecutor(context), status) }
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching { lm.requestLocationUpdates(provider, 1_000L, 0f, listener, handler.looper) }
        }
        val heartbeat = launch {
            while (isActive) {
                delay(2_000)
                if (registered && SystemClock.elapsedRealtime() - lastEpoch > 5_000) {
                    trySend(Event.State("GNSS measurements paused by the phone", paused = true))
                }
            }
        }
        awaitClose {
            heartbeat.cancel()
            runCatching { LocationManagerCompat.unregisterGnssMeasurementsCallback(lm, measurement) }
            runCatching { LocationManagerCompat.unregisterGnssStatusCallback(lm, status) }
            runCatching { lm.removeUpdates(listener) }
        }
    }
}
