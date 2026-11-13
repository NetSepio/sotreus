package app.sotreus.core.data.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.sotreus.core.data.R
import app.sotreus.core.data.edge.EdgeHub
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * V2: keeps the Edge link running while Sotreus is not on screen, whenever the Edge is the sensor
 * (owner decision 2026-11-08), always with a visible notification that says whether the Edge is
 * connected. Started while the app is visible, or when Android reports the Edge back in range.
 * Stays running while the Edge is away, so the link comes back by itself.
 *
 * This phone's location is part of the service only in travel mode with tagging on
 * (HANDOFF_V2_EDGE.md §4.3), and is requested only while phone and Edge are connected. No
 * background location permission is used.
 */
@AndroidEntryPoint
class EdgeLinkService : LifecycleService() {
    @Inject lateinit var edge: EdgeHub

    override fun onCreate() { super.onCreate(); running.value = true }
    override fun onDestroy() { running.value = false; super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Notifications.ensureChannels(this)
        if (!promote(edge.state.value.connected, travelTagging.value, meshSharing.value)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            lifecycleScope.launch {
                combine(edge.state.map { it.connected }.distinctUntilChanged(), travelTagging, meshSharing) { connected, travel, sharing -> Triple(connected, travel, sharing) }
                    .distinctUntilChanged()
                    .collect { (connected, travel, sharing) -> if (!promote(connected, travel, sharing)) stopSelf() }
            }
        }
        return START_STICKY
    }

    @Volatile private var watching = false

    /** (Re)states the foreground notification and service type. False when Android refuses it. */
    private fun promote(connected: Boolean, travel: Boolean, sharing: Boolean): Boolean {
        val location = (travel || sharing) && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        return runCatching { ServiceCompat.startForeground(this, Notifications.ID_EDGE, notification(connected, travel, sharing), type) }.isSuccess
    }

    private fun notification(connected: Boolean, travel: Boolean, sharing: Boolean): Notification {
        val tap = Notifications.launchIntent(this)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val (title, text) = when {
            !connected -> R.string.data_notification_edge_waiting to R.string.data_notification_edge_waiting_text
            // Mesh mode (HANDOFF_V3_MESH.md §3.1): sharing this phone's location is always said.
            sharing -> R.string.data_notification_edge_mesh_sharing to R.string.data_notification_edge_mesh_sharing_text
            edge.state.value.status?.mode == app.sotreus.edge.protocol.EdgeMode.MESH -> R.string.data_notification_edge_mesh to R.string.data_notification_edge_mesh_text
            travel -> R.string.data_notification_edge_travel to R.string.data_notification_edge_travel_text
            else -> R.string.data_notification_edge to R.string.data_notification_edge_text
        }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_OBSERVATION)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(title))
            .setContentText(getString(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .build()
    }

    companion object {
        val running = MutableStateFlow(false)

        /** Travel mode with this phone's location tagging: set by the observation controller. */
        val travelTagging = MutableStateFlow(false)

        /** This phone's location handed to the mesh (HANDOFF_V3_MESH.md §3.1): set by the mesh hub. */
        val meshSharing = MutableStateFlow(false)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EdgeLinkService::class.java))
        }

        /** Posts the notification again if the service runs, e.g. once Android lets it show. */
        fun refresh(context: Context) {
            if (running.value) runCatching { start(context) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EdgeLinkService::class.java))
        }
    }
}
