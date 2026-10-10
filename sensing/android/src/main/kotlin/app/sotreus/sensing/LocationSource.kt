package app.sotreus.sensing

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Phone location, foreground only (handoff §13): geotagged sessions, picking saved places by
 * location and optional plus-code tags. Uses the platform LocationManager so no Google Play
 * services dependency is needed. Every [Fix] carries the time the fix was taken, never the time it
 * was read, so an old fix is never shown as current.
 */
class LocationSource @Inject constructor(@ApplicationContext private val context: Context) {
    data class Fix(val atMs: Long, val lat: Double, val lon: Double, val accuracyM: Float?)

    private val manager: LocationManager? get() = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Location is switched on in system settings. */
    fun isEnabled(): Boolean = manager?.let { runCatching { LocationManagerCompat.isLocationEnabled(it) }.getOrDefault(false) } == true

    /**
     * One current fix, foreground only. Asks every enabled provider at once and returns the first
     * fix within [goodEnoughM], else the most accurate one that arrived within [timeoutMs]. Falls back
     * to the platform's last known fix only when it is younger than [maxLastKnownAgeMs]. Returns null
     * without the location permission or with location off.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(
        timeoutMs: Long = 15_000,
        goodEnoughM: Float = GOOD_ENOUGH_M,
        maxLastKnownAgeMs: Long = LAST_KNOWN_MAX_AGE_MS,
    ): Fix? {
        val lm = manager ?: return null
        if (!hasPermission()) return null
        val providers = enabledProviders(lm, ONE_SHOT_PROVIDERS)
        if (providers.isEmpty()) return null
        val results = Channel<Location?>(Channel.UNLIMITED)
        val signals = providers.map { CancellationSignal() }
        var best: Location? = null
        try {
            val executor = ContextCompat.getMainExecutor(context)
            providers.forEachIndexed { i, p ->
                runCatching { LocationManagerCompat.getCurrentLocation(lm, p, signals[i], executor) { results.trySend(it) } }
                    .onFailure { results.trySend(null) }
            }
            withTimeoutOrNull(timeoutMs) {
                repeat(providers.size) {
                    val loc = results.receive() ?: return@repeat
                    val b = best
                    if (b == null || loc.accuracyOrMax() < b.accuracyOrMax()) best = loc
                    if (loc.hasAccuracy() && loc.accuracy <= goodEnoughM) return@withTimeoutOrNull
                }
            }
        } finally {
            signals.forEach { it.cancel() }
            results.close()
        }
        best?.let { return it.toFix() }
        return lastKnown()?.takeIf { System.currentTimeMillis() - it.atMs <= maxLastKnownAgeMs }
    }

    /** The platform's most recent fix without starting location, or null without permission. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? {
        val lm = manager ?: return null
        if (!hasPermission()) return null
        return ONE_SHOT_PROVIDERS
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.toFix()
    }

    /**
     * The first fix taken within [maxAgeMs], waiting up to [timeoutMs]. Unlike [currentFix], a cached
     * fix from before the app opened never counts, so the phone's position is known for this moment.
     */
    suspend fun freshFix(timeoutMs: Long, maxAgeMs: Long = 5_000): Fix? = withTimeoutOrNull(timeoutMs) {
        updates(intervalMs = 1_000, minDistanceM = 0f, providers = ONE_SHOT_PROVIDERS)
            .first { System.currentTimeMillis() - it.atMs <= maxAgeMs }
    }

    /** Continuous fixes while collected. Collect only while in the foreground or a location-type service. */
    fun updates(intervalMs: Long = 15_000, minDistanceM: Float = 10f): Flow<Fix> = updates(intervalMs, minDistanceM, UPDATE_PROVIDERS)

    @SuppressLint("MissingPermission")
    private fun updates(intervalMs: Long, minDistanceM: Float, providers: List<String>): Flow<Fix> = callbackFlow {
        val lm = manager
        val listener = LocationListenerCompat { l -> trySend(l.toFix()) }
        if (lm != null && hasPermission()) {
            enabledProviders(lm, providers).forEach { p ->
                runCatching { lm.requestLocationUpdates(p, intervalMs, minDistanceM, listener, Looper.getMainLooper()) }
            }
        }
        awaitClose { runCatching { lm?.removeUpdates(listener) } }
    }

    private fun enabledProviders(lm: LocationManager, candidates: List<String>): List<String> =
        candidates.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    private fun Location.accuracyOrMax(): Float = if (hasAccuracy()) accuracy else Float.MAX_VALUE

    /** Stamps the fix with when it was taken, from the monotonic clock (immune to GPS time quirks). */
    private fun Location.toFix(): Fix {
        val atMs = if (elapsedRealtimeNanos > 0) {
            System.currentTimeMillis() - ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
        } else {
            time
        }
        return Fix(atMs, latitude, longitude, if (hasAccuracy()) accuracy else null)
    }

    private companion object {
        const val GOOD_ENOUGH_M = 50f
        const val LAST_KNOWN_MAX_AGE_MS = 2 * 60_000L
        val ONE_SHOT_PROVIDERS = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val UPDATE_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
