package app.sotreus.core.data.edge

import android.content.Context
import app.sotreus.core.data.security.SecretBox
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The Edge's HTTP PIN, fetched over the Bluetooth bond and kept Keystore-wrapped on this phone. */
@Singleton
class EdgeSecrets @Inject constructor(@ApplicationContext context: Context, private val box: SecretBox) {
    private val prefs = context.getSharedPreferences("edge_secrets", Context.MODE_PRIVATE)

    fun savePin(deviceId: String, pin: String) = prefs.edit().putString(deviceId, box.wrap(pin.encodeToByteArray())).apply()

    fun pin(deviceId: String): String? = prefs.getString(deviceId, null)?.let { runCatching { box.unwrap(it).decodeToString() }.getOrNull() }

    fun forget(deviceId: String) = prefs.edit().remove(deviceId).apply()
}
