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
