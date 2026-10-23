package app.sotreus.core.data.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.sotreus.core.data.R
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.SessionLocationEntity
import app.sotreus.core.model.SessionKind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for an active Journey or Sit. It keeps observation running with a visible
 * notification. Journey movement uses location independently of optional route storage.
 * It stops itself as soon as no session is active.
 */
@AndroidEntryPoint
class ObservationService : LifecycleService() {
    @Inject lateinit var sessions: SessionDao
    @Inject lateinit var phoneLocation: PhoneLocation
    @Inject lateinit var settings: SettingsRepository

    private var sessionJob: Job? = null

    override fun onCreate() { super.onCreate(); running.value = true }
    override fun onDestroy() { running.value = false; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Notifications.ensureChannels(this)
        val kind = intent?.getStringExtra(EXTRA_KIND)?.let(SessionKind::valueOf) ?: SessionKind.SIT
        val geotag = intent?.getBooleanExtra(EXTRA_GEOTAG, false) == true && hasLocation()
        val moving = kind == SessionKind.JOURNEY && intent?.getBooleanExtra(EXTRA_MOVEMENT, false) == true && hasLocation()
        showNotification(kind, geotag, moving)

        sessionJob?.cancel()
        sessionJob = lifecycleScope.launch {
            combine(sessions.observeActive(), settings.settings) { active, s -> Triple(active, s.journeyMovement, s.livePath) }
                .distinctUntilChanged().collectLatest { (active, detectMovement, livePath) ->
                    if (active == null) {
                        stopSelf()
                        return@collectLatest
                    }
                    val recordRoute = active.geotag && hasLocation()
                    val detect = active.kind == SessionKind.JOURNEY && detectMovement && hasLocation()
                    showNotification(active.kind, recordRoute, detect)
                    if (recordRoute || detect) {
                        phoneLocation.updates(fast = livePath).collect { fix ->
                            if (recordRoute && !fix.mock) sessions.insertLocation(SessionLocationEntity(sessionId = active.id, atMs = fix.atMs, lat = fix.lat, lon = fix.lon, accuracyM = fix.accuracyM))
                            phoneLocation.offer(fix)
                        }
                    }
                }
        }
        return START_NOT_STICKY
    }

    private fun showNotification(kind: SessionKind, geotag: Boolean, moving: Boolean) {
        val tap = Notifications.launchIntent(this)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_OBSERVATION)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(if (kind == SessionKind.JOURNEY) R.string.data_notification_journey else R.string.data_notification_sit))
            .setContentText(getString(when {
                geotag -> R.string.data_notification_session_geotag
                moving -> R.string.data_notification_session_movement
                else -> R.string.data_notification_session_text
            }))
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (geotag || moving) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0 // Android 9 has foreground services without service types.
        ServiceCompat.startForeground(this, Notifications.ID_SESSION, notification, type)
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        val running = kotlinx.coroutines.flow.MutableStateFlow(false)
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_GEOTAG = "geotag"
        private const val EXTRA_MOVEMENT = "movement"

        fun start(context: Context, kind: SessionKind, geotag: Boolean, movement: Boolean) {
            val intent = Intent(context, ObservationService::class.java)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_GEOTAG, geotag)
                .putExtra(EXTRA_MOVEMENT, movement)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
