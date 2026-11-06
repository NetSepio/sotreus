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
import app.sotreus.core.data.edge.EdgeHub
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * V2 travel mode (HANDOFF_V2_EDGE.md §4.3): keeps the Edge link, and this phone's location for
 * tagging, running with the screen off, always with a visible notification. Started while the app
 * is visible (or when Android reports the Edge back in range). Stops two minutes after the link
 * drops, so the phone's location stops when phone and Edge are apart. No background location
 * permission is used.
 */
@AndroidEntryPoint
class EdgeTravelService : LifecycleService() {
    @Inject lateinit var edge: EdgeHub

    override fun onCreate() { super.onCreate(); running.value = true }
    override fun onDestroy() { running.value = false; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Notifications.ensureChannels(this)
        val tap = Notifications.launchIntent(this)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_OBSERVATION)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.data_notification_edge_travel))
            .setContentText(getString(R.string.data_notification_edge_travel_text))
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_EDGE, notification, type) }.onFailure {
            stopSelf()
            return START_NOT_STICKY
        }
        lifecycleScope.launch {
            edge.state.map { it.connected }.distinctUntilChanged().collectLatest { connected ->
                if (!connected) {
                    delay(LOST_STOP_MS)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        val running = MutableStateFlow(false)
        private const val LOST_STOP_MS = 2 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EdgeTravelService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EdgeTravelService::class.java))
        }
    }
}
