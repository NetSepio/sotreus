package app.sotreus.feature.settings.diagnostics

import android.os.Build
import javax.inject.Inject

/** Platform facts for Diagnostics. Model names are not identifiers; nothing here is sent anywhere. */
data class DeviceInfo(
    val androidRelease: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
)

class DeviceInfoSource @Inject constructor() {
    fun current() = DeviceInfo(
        androidRelease = Build.VERSION.RELEASE,
        apiLevel = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
    )
}
