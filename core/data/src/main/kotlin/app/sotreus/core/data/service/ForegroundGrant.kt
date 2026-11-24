/*
 * Adapted from Fieldwatch ScanService.chooseForeground (https://github.com/OffGridPete/Fieldwatch, commit 60c8ecc).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: shared by the session and Edge link services, which ask for location only when they use it.
 */
package app.sotreus.core.data.service

import android.content.pm.ServiceInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What Android let a foreground service run as. */
internal enum class ForegroundGrant {
    /** With this phone's location, as asked. */
    WITH_LOCATION,

    /** Without location: not asked for, or refused to a start Android counts as background (Android 14+). */
    RADIOS,

    /** Not at all. */
    REFUSED,
    ;

    companion object {
        /**
         * Asks for the location type first when [wantsLocation], then without it, so a background start
         * keeps the service up. [accept] is handed a service type and says whether Android took it.
         */
        @android.annotation.SuppressLint("InlinedApi") // The types are asked for from API 29 only.
        fun request(sdkInt: Int, wantsLocation: Boolean, accept: (Int) -> Boolean): ForegroundGrant {
            // Android 9 has foreground services without service types.
            if (sdkInt < 29) return if (!accept(0)) REFUSED else if (wantsLocation) WITH_LOCATION else RADIOS
            val radios = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (wantsLocation && accept(radios or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)) return WITH_LOCATION
            return if (accept(radios)) RADIOS else REFUSED
        }

        /** Sotreus is on screen: Android then grants the location type it refused in the background. */
        fun visible(): Flow<Boolean> =
            ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged()
    }
}
