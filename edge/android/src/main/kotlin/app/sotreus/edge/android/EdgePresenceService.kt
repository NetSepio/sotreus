package app.sotreus.edge.android

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Android binds this while a paired Edge is in range (Companion Device Manager presence). It only
 * reports; the data layer decides whether to reconnect, sync or resume tagging.
 */
@RequiresApi(31)
class EdgePresenceService : CompanionDeviceService() {
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < 33) EdgePresence.report(address, present = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT < 33) EdgePresence.report(address, present = false)
    }

    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let { EdgePresence.report(it, present = true) }
    }

    @RequiresApi(33)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let { EdgePresence.report(it, present = false) }
    }
}
