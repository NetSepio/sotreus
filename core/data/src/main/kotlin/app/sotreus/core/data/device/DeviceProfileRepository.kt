package app.sotreus.core.data.device

import android.content.pm.ApplicationInfo
import android.content.Context
import android.os.Build
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.model.AppCapabilities
import app.sotreus.core.model.DeviceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides at runtime whether this is a Solana Mobile device (Saga, Seeker). That, not the build
 * flavor, selects the wallet/proofs UI. Debuggable builds can force it on from Diagnostics so the
 * Solana screens can be exercised on an emulator.
 */
@Singleton
class DeviceProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
) {
    val hardware: DeviceProfile = DeviceProfile(
        isSolanaMobile = isSolanaMobileHardware(Build.MANUFACTURER, Build.BRAND),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
    )

    val isDebuggable: Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    val profile: Flow<DeviceProfile> = settings.settings.map { s ->
        hardware.copy(isSolanaMobile = hardware.isSolanaMobile || (isDebuggable && s.forceSolanaUi))
    }

    val capabilities: Flow<AppCapabilities> = profile.map(AppCapabilities::forDevice)

    companion object {
        fun isSolanaMobileHardware(manufacturer: String?, brand: String?): Boolean =
            manufacturer.orEmpty().contains("Solana Mobile", ignoreCase = true) ||
                brand.equals("solanamobile", ignoreCase = true)
    }
}
