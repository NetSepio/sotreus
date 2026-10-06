package app.sotreus.social.presence

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.sotreus.core.crypto.Presence
import app.sotreus.core.data.R as DataR
import app.sotreus.core.data.repository.FriendRepository
import app.sotreus.core.data.service.Notifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Advertises one rotating token per approved friend, cycling every few seconds, while Nearby
 * presence is on. BLE advertising is active transmission, so this always runs as a visible
 * foreground service and stops the moment the user turns presence off.
 */
@AndroidEntryPoint
class PresenceService : LifecycleService() {
    @Inject lateinit var friends: FriendRepository

    private var advertising = false
    private val callback = object : AdvertiseCallback() {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Notifications.ensureChannels(this)
        val tap = Notifications.launchIntent(this)?.let { PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_PRESENCE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(DataR.string.data_notification_presence))
            .setContentText(getString(DataR.string.data_notification_presence_text))
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
        ServiceCompat.startForeground(this, Notifications.ID_PRESENCE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        lifecycleScope.launch {
            var i = 0
            while (isActive) {
                val secrets = friends.pairwiseSecrets()
                if (secrets.isNotEmpty()) {
                    val (_, secret) = secrets[i % secrets.size]
                    advertise(Presence.token(secret, Presence.epoch(System.currentTimeMillis() / 1000)))
                    i++
                } else {
                    stopAdvertising()
                }
                delay(ROTATE_MS)
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun advertise(token: ByteArray) {
        val adv = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser ?: return
        stopAdvertising()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(PresenceFrame.COMPANY_ID, PresenceFrame.encode(token))
            .build()
        runCatching { adv.startAdvertising(settings, data, callback) }.onSuccess { advertising = true }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (!advertising) return
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        advertising = false
    }

    override fun onDestroy() {
        stopAdvertising()
        super.onDestroy()
    }

    companion object {
        private const val ROTATE_MS = 3_000L

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, PresenceService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, PresenceService::class.java))
        }
    }
}
