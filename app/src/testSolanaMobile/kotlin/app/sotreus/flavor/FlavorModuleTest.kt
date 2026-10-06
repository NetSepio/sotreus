package app.sotreus.flavor

import app.sotreus.BuildConfig
import app.sotreus.core.data.device.DeviceProfileRepository
import app.sotreus.core.model.Distribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlavorModuleTest {
    @Test
    fun bindsDistributionMatchingBuildConfig() {
        assertEquals(Distribution.SOLANA_MOBILE, FlavorModule.provideDistribution())
        assertEquals(BuildConfig.DISTRIBUTION, FlavorModule.provideDistribution().flavorName)
    }

    @Test
    fun applicationIdIsSharedAcrossDistributions() {
        assertEquals("com.sotreus.app", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun solanaUiFollowsTheDeviceNotTheFlavor() {
        assertTrue(DeviceProfileRepository.isSolanaMobileHardware("Solana Mobile Inc.", "solanamobile"))
        assertTrue(DeviceProfileRepository.isSolanaMobileHardware("Solana Mobile", "saga"))
        assertFalse(DeviceProfileRepository.isSolanaMobileHardware("Google", "google"))
    }
}
