package app.sotreus.intelligence

import app.sotreus.core.model.BleAddressType
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.WifiRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCategoryTest {
    private fun ap(vendor: String?, address: String = "24:2F:D0:BD:0F:10", family: DeviceFamily? = null, signature: String? = null, rid: Boolean = false) =
        CategoryInput(RadioKind.WIFI, address, null, WifiRole.AP, family, signature, Confidence.MEDIUM, vendor, rid)

    @Test fun signatureWinsOverEverything() {
        val g = DeviceCategoryGuess.guess(ap("Ubiquiti Inc", family = DeviceFamily.CAMERA, signature = "Flock Safety"))
        assertEquals(DeviceFamily.CAMERA, g.family)
        assertEquals(CategoryBasis.SIGNATURE, g.basis)
        assertEquals("Ubiquiti Inc", g.vendor)
    }

    @Test fun remoteIdMeansDroneFamily() {
        val g = DeviceCategoryGuess.guess(ap("SZ DJI Technology", rid = true))
        assertEquals(DeviceFamily.DRONE, g.family)
        assertEquals(CategoryBasis.NETWORK, g.basis)
    }

    @Test fun advertisementFamilyForBle() {
        val g = DeviceCategoryGuess.guess(CategoryInput(RadioKind.BLE, "C4:5B:BE:01:02:03", BleAddressType.RANDOM, family = DeviceFamily.AUDIO, guessConfidence = Confidence.HIGH))
        assertEquals(CategoryBasis.ADVERTISEMENT, g.basis)
        assertEquals(Confidence.HIGH, g.confidence)
    }

    @Test fun vendorPrefixMapsCommonMakers() {
        assertEquals(DeviceFamily.ROUTER, DeviceCategoryGuess.guess(ap("Ubiquiti Inc")).family)
        assertEquals(DeviceFamily.ROUTER, DeviceCategoryGuess.guess(ap("TP-LINK TECHNOLOGIES CO.,LTD.")).family)
        assertEquals(DeviceFamily.SMART_HOME, DeviceCategoryGuess.guess(ap("Espressif Inc.")).family)
        assertEquals(CategoryBasis.VENDOR_PREFIX, DeviceCategoryGuess.guess(ap("Espressif Inc.")).basis)
        // A phone maker's access point is a hotspot or router, never "phone or computer".
        assertEquals(DeviceFamily.ROUTER, DeviceCategoryGuess.guess(ap("Xiaomi Communications Co Ltd")).family)
        assertNull(DeviceCategoryGuess.guess(ap("Apple, Inc.")).family)
        assertEquals("Apple, Inc.", DeviceCategoryGuess.guess(ap("Apple, Inc.")).vendor)
        // The same maker as a client is a phone or computer.
        val client = CategoryInput(RadioKind.WIFI, "F0:18:98:01:02:03", null, WifiRole.CLIENT, vendor = "Apple, Inc.")
        assertEquals(DeviceFamily.PHONE_PC, DeviceCategoryGuess.guess(client).family)
        assertEquals(Confidence.LOW, DeviceCategoryGuess.guess(client).confidence)
    }

    @Test fun privateClientAddressesShowNoVendor() {
        val g = DeviceCategoryGuess.guess(CategoryInput(RadioKind.WIFI, "CA:2A:F0:E6:B6:01", null, WifiRole.CLIENT, vendor = "Some Vendor"))
        assertNull(g.vendor)
        assertEquals(CategoryBasis.ASKING_FOR_NETWORKS, g.basis)
    }

    @Test fun virtualBssidKeepsItsBurnedInVendor() {
        // Locally administered BSSID on an access point: the vendor came from the universal prefix.
        val g = DeviceCategoryGuess.guess(ap("Ubiquiti Inc", address = "26:2F:D0:BD:0F:10"))
        assertEquals(DeviceFamily.ROUTER, g.family)
        assertEquals("Ubiquiti Inc", g.vendor)
    }

    @Test fun privateBleAndNothingAtAll() {
        assertEquals(CategoryBasis.PRIVATE_ADDRESS, DeviceCategoryGuess.guess(CategoryInput(RadioKind.BLE, "7A:00:00:00:00:01", BleAddressType.RANDOM)).basis)
        assertEquals(CategoryBasis.NOTHING, DeviceCategoryGuess.guess(CategoryInput(RadioKind.BLE, "00:11:22:33:44:55", BleAddressType.PUBLIC)).basis)
        assertEquals(CategoryBasis.VENDOR_PREFIX, DeviceCategoryGuess.guess(CategoryInput(RadioKind.BLE, "00:11:22:33:44:55", BleAddressType.PUBLIC, vendor = "Unknown Maker Ltd")).basis)
    }
}
