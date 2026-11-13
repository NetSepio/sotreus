package app.sotreus.core.data.mesh

import android.content.Context
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This phone's country, from the mobile network it is on, else its SIM. Read on the phone with no
 * location and no network request, so nothing leaves the device. Null when neither is known.
 */
@Singleton
class PhoneCountry @Inject constructor(@ApplicationContext private val context: Context) {
    fun iso(): String? {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        return runCatching { tm.networkCountryIso }.getOrNull()?.takeIf { it.length == 2 }
            ?: runCatching { tm.simCountryIso }.getOrNull()?.takeIf { it.length == 2 }
    }
}
