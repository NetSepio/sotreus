package app.sotreus.core.data.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.sotreus.core.data.R
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.SessionLocationEntity
import app.sotreus.core.model.SessionKind
import app.sotreus.sensing.LocationSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for an active Journey or Sit. It keeps observation running with a visible
 * notification and, for a geotagged session only, records phone location in the foreground.
 * It stops itself as soon as no session is active.
 */
@AndroidEntryPoint
class ObservationService : LifecycleService() {
    @Inject lateinit var sessions: SessionDao
    @Inject lateinit var location: LocationSource
    @Inject lateinit var phoneLocation: PhoneLocation

    private var locationJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Notifications.ensureChannels(this)
        val kind = intent?.getStringExtra(EXTRA_KIND)?.let(SessionKind::valueOf) ?: SessionKind.SIT
        val geotag = intent?.getBooleanExtra(EXTRA_GEOTAG, false) == true && hasLocation()
        val tap = Notifications.launchIntent(this)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_OBSERVATION)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(if (kind == SessionKind.JOURNEY) R.string.data_notification_journey else R.string.data_notification_sit))
            .setContentText(getString(if (geotag) R.string.data_notification_session_geotag else R.string.data_notification_session_text))
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (geotag) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        ServiceCompat.startForeground(this, Notifications.ID_SESSION, notification, type)

        lifecycleScope.launch {
            sessions.observeActive().collect { active ->
                if (active == null) {
                    stopSelf()
                } else if (geotag && locationJob == null) {
                    locationJob = lifecycleScope.launch {
                        location.updates().collect { fix ->
                            sessions.insertLocation(SessionLocationEntity(sessionId = active.id, atMs = fix.atMs, lat = fix.lat, lon = fix.lon, accuracyM = fix.accuracyM))
                            // Keeps the place following the phone during the session, even off screen.
                            phoneLocation.offer(fix)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_GEOTAG = "geotag"

        fun start(context: Context, kind: SessionKind, geotag: Boolean) {
            val intent = Intent(context, ObservationService::class.java)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_GEOTAG, geotag)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
