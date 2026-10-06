package app.sotreus.sensing

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * Phone location for geotagged sessions only, foreground only (handoff §13). Uses the platform
 * LocationManager so no Google Play services dependency is needed.
 */
class LocationSource @Inject constructor(@ApplicationContext private val context: Context) {
    data class Fix(val atMs: Long, val lat: Double, val lon: Double, val accuracyM: Float?)

    /**
     * One current fix, foreground only, for saving a place's location. Returns null without the
     * location permission, with location off, or if no fix arrives within [timeoutMs].
     */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(timeoutMs: Long = 15_000): Fix? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        val fresh = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine<Location?> { cont ->
                val signal = androidx.core.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                androidx.core.location.LocationManagerCompat.getCurrentLocation(
                    lm, provider, signal, androidx.core.content.ContextCompat.getMainExecutor(context),
                ) { loc -> if (cont.isActive) cont.resume(loc) { _, _, _ -> } }
            }
        }
        val loc = fresh ?: runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: return null
        return Fix(System.currentTimeMillis(), loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null)
    }

    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = 15_000): Flow<Fix> = callbackFlow {
        val lm = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { l: Location ->
            trySend(Fix(System.currentTimeMillis(), l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null))
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm?.isProviderEnabled(it) == true }.getOrDefault(false) }
        providers.forEach { p ->
            runCatching { lm?.requestLocationUpdates(p, intervalMs, 10f, listener, Looper.getMainLooper()) }
        }
        awaitClose { runCatching { lm?.removeUpdates(listener) } }
    }
}
