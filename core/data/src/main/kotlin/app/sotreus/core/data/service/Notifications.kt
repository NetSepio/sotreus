package app.sotreus.core.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.sotreus.core.data.R

object Notifications {
    const val CHANNEL_OBSERVATION = "observation"
    const val CHANNEL_PRESENCE = "presence"
    const val ID_SESSION = 1001
    const val ID_PRESENCE = 1002

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OBSERVATION, context.getString(R.string.data_channel_observation), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.data_channel_observation_description) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PRESENCE, context.getString(R.string.data_channel_presence), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.data_channel_presence_description) },
        )
    }

    /** The launcher activity, so notification taps open the app without a compile-time dependency. */
    fun launchIntent(context: Context) = context.packageManager.getLaunchIntentForPackage(context.packageName)
}
