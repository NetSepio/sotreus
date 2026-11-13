package app.sotreus.core.data.mesh

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sotreus.core.data.R
import app.sotreus.core.data.service.Notifications
import app.sotreus.core.database.entity.MeshMessageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a notification for a new mesh message (HANDOFF_V3_MESH.md §1): the sender and the text,
 * one notification per conversation, content hidden on the lock screen. Nothing when the owner
 * has not allowed notifications.
 */
@Singleton
class MeshNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    /** [channelName] is set for a channel message: the title then reads "sender · channel". */
    fun message(m: MeshMessageEntity, senderName: String?, channelName: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        Notifications.ensureChannels(context)
        val sender = senderName ?: app.sotreus.edge.protocol.mesh.MeshNodeId.format(m.fromNode)
        val title = channelName?.let { context.getString(R.string.data_notification_mesh_channel, sender, it) } ?: sender
        val tap = Notifications.launchIntent(context)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val public = NotificationCompat.Builder(context, Notifications.CHANNEL_MESH)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.data_notification_mesh_public))
            .build()
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_MESH)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { nm.notify(Notifications.ID_MESH_BASE + (m.conversation.hashCode() and 0xFFFF), n) }
    }
}
