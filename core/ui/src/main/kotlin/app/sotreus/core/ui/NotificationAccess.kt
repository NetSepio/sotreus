package app.sotreus.core.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Whether this app's notifications can show: Android's permission, the app's switch, and [channelId] if given. */
fun notificationsShown(context: Context, channelId: String? = null): Boolean {
    val nm = NotificationManagerCompat.from(context)
    if (!nm.areNotificationsEnabled()) return false
    val channel = channelId?.let { nm.getNotificationChannelCompat(it) } ?: return true
    return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
}

/** [shown] is re-read whenever the screen resumes; [request] asks Android, or opens the app's notification settings. */
class NotificationAccess(val shown: Boolean, val request: () -> Unit)

/**
 * Notification access for a screen; [onShown] runs when notifications go from hidden to shown. [NotificationAccess.request] shows Android's own prompt while
 * Android still offers it; once Android stops asking (denied twice, or switched off in settings),
 * it opens this app's notification settings instead.
 */
@Composable
fun rememberNotificationAccess(channelId: String? = null, onShown: () -> Unit = {}): NotificationAccess {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(notificationsShown(context, channelId)) }
    // A notification posted while hidden doesn't reappear by itself: [onShown] lets its owner post it again.
    val update = { now: Boolean -> if (now && !shown) onShown(); shown = now }
    LifecycleResumeEffect(Unit) {
        update(notificationsShown(context, channelId))
        onPauseOrDispose { }
    }
    val openSettings = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        Unit
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        update(notificationsShown(context, channelId))
        val activity = context.findActivity()
        // Android no longer shows its prompt: the switch is in the app's settings.
        if (!granted && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) openSettings()
    }
    return NotificationAccess(shown) {
        val ask = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ask) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else openSettings()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
