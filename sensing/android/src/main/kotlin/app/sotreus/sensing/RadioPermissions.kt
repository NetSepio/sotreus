package app.sotreus.sensing

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Which runtime permission group a row on screen 02 stands for. */
enum class PermissionGroup { NEARBY_DEVICES, NEARBY_WIFI, LOCATION, NOTIFICATIONS, ADVERTISE, CAMERA }

data class RadioAccess(
    val granted: Set<PermissionGroup>,
    val bluetoothOn: Boolean,
    val wifiOn: Boolean,
    val locationOn: Boolean,
    val bleSupported: Boolean,
) {
    /** Everything Now needs to receive scan results. Notifications are optional. */
    val canScan: Boolean
        get() = PermissionGroup.NEARBY_DEVICES in granted && PermissionGroup.NEARBY_WIFI in granted &&
            PermissionGroup.LOCATION in granted

    val radiosOff: List<String>
        get() = buildList {
            if (!bluetoothOn) add("bluetooth")
            if (!wifiOn) add("wifi")
            if (!locationOn) add("location")
        }
}

/**
 * The single place that knows Android's API-level permission sets (handoff §13, ported from
 * Fieldwatch Permissions.kt). Screens ask for groups; this maps them to manifest permissions.
 */
@Singleton
class RadioPermissions @Inject constructor(@ApplicationContext private val context: Context) {
    private val _access = MutableStateFlow(read())
    val access: StateFlow<RadioAccess> = _access.asStateFlow()

    fun permissionsFor(group: PermissionGroup): List<String> = when (group) {
        PermissionGroup.NEARBY_DEVICES -> if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }
        PermissionGroup.NEARBY_WIFI -> if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            emptyList()
        }
        PermissionGroup.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        PermissionGroup.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
        PermissionGroup.ADVERTISE -> if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyList()
        }
        PermissionGroup.CAMERA -> listOf(Manifest.permission.CAMERA)
    }

    /** Screen 02's "Grant permissions": scanning groups plus optional notifications. */
    fun scanRequestSet(): List<String> = listOf(
        PermissionGroup.NEARBY_DEVICES, PermissionGroup.NEARBY_WIFI, PermissionGroup.LOCATION, PermissionGroup.NOTIFICATIONS,
    ).flatMap(::permissionsFor)

    fun isGranted(group: PermissionGroup): Boolean = permissionsFor(group).all {
        // Coarse-only location still lets BLE/Wi-Fi scans through on most devices; require fine.
        if (it == Manifest.permission.ACCESS_COARSE_LOCATION) return@all true
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun refresh(): RadioAccess = read().also { _access.value = it }

    private fun read(): RadioAccess {
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val loc = context.getSystemService(LocationManager::class.java)
        return RadioAccess(
            granted = PermissionGroup.entries.filter(::isGranted).toSet(),
            bluetoothOn = bt?.isEnabled == true,
            wifiOn = wifi?.isWifiEnabled == true || (wifi?.isScanAlwaysAvailable == true),
            locationOn = loc != null && LocationManagerCompat.isLocationEnabled(loc),
            bleSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
        )
    }
}
