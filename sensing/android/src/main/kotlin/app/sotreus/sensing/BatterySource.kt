package app.sotreus.sensing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** One battery reading. [percent] is null when Android did not say. */
data class BatteryReading(val percent: Int?, val charging: Boolean)

/** This phone's battery level, only while collected. */
@Singleton
class BatterySource @Inject constructor(@ApplicationContext private val context: Context) {
    val readings: Flow<BatteryReading> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let { trySend(read(it)) }
            }
        }
        // The sticky broadcast answers at once, then on every change.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { trySend(read(it)) }
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.conflate().distinctUntilChanged()

    private fun read(i: Intent): BatteryReading {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
        return BatteryReading(percent, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }
}
