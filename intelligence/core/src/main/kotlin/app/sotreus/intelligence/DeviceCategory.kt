package app.sotreus.intelligence

import app.sotreus.core.model.BleAddressType
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.WifiRole

/** What a category guess rests on, shown with it ("from vendor prefix (Ubiquiti)"). */
enum class CategoryBasis {
    /** A signature catalog match. */
    SIGNATURE,

    /** The BLE advertisement: company ID, service UUIDs, Fast Pair, Apple Continuity. */
    ADVERTISEMENT,

    /** The Wi-Fi network's own elements (Remote ID, SSID patterns). */
    NETWORK,

    /** The address's vendor prefix (OUI), including a virtual BSSID's burned-in prefix. */
    VENDOR_PREFIX,

    /** A private (locally administered or random) address: no vendor to read. */
    PRIVATE_ADDRESS,

    /** A Wi-Fi device asking for networks, with nothing else to go on. */
    ASKING_FOR_NETWORKS,

    NOTHING,
}

/** A category guess for the List tab. An observation, never an identity. */
data class CategoryGuess(
    val family: DeviceFamily?,
    val basis: CategoryBasis,
    /** The vendor the prefix names, when that is the basis or worth showing. */
    val vendor: String?,
    val confidence: Confidence,
)

data class CategoryInput(
    val radio: RadioKind,
    val address: String,
    val addressType: BleAddressType?,
    val wifiRole: WifiRole? = null,
    /** From [SignatureClassifier]: family and catalog row, if any. */
    val family: DeviceFamily? = null,
    val signatureName: String? = null,
    val guessConfidence: Confidence = Confidence.LOW,
    /** Offline OUI vendor (RadioDb). */
    val vendor: String? = null,
    val hasRemoteId: Boolean = false,
)

/**
 * Category guesses (plan §8): first match wins. Built on the signature catalog and Fieldwatch's
 * advertisement decoding, then the vendor prefix through [VendorFamilies], then what the address
 * itself says.
 */
object DeviceCategoryGuess {
    fun guess(i: CategoryInput): CategoryGuess {
        val privateAddress = isPrivate(i)
        // A client's private MAC with the local bit cleared names no real vendor.
        val vendor = i.vendor?.trim()?.ifBlank { null }?.takeUnless { privateAddress && i.wifiRole != WifiRole.AP }
        if (i.family != null && i.signatureName != null) return CategoryGuess(i.family, CategoryBasis.SIGNATURE, vendor, i.guessConfidence)
        if (i.hasRemoteId) return CategoryGuess(DeviceFamily.DRONE, CategoryBasis.NETWORK, vendor, Confidence.HIGH)
        if (i.family != null) {
            val basis = if (i.radio == RadioKind.BLE) CategoryBasis.ADVERTISEMENT else CategoryBasis.NETWORK
            return CategoryGuess(i.family, basis, vendor, i.guessConfidence)
        }
        if (vendor != null) {
            val f = VendorFamilies.familyFor(vendor, i.radio, i.wifiRole)
            return CategoryGuess(f?.first, CategoryBasis.VENDOR_PREFIX, vendor, f?.second ?: Confidence.LOW)
        }
        if (i.radio == RadioKind.WIFI && i.wifiRole == WifiRole.CLIENT) {
            return CategoryGuess(null, CategoryBasis.ASKING_FOR_NETWORKS, null, Confidence.LOW)
        }
        if (privateAddress) return CategoryGuess(null, CategoryBasis.PRIVATE_ADDRESS, null, Confidence.LOW)
        return CategoryGuess(null, CategoryBasis.NOTHING, null, Confidence.LOW)
    }

    private fun isPrivate(i: CategoryInput): Boolean = when (i.radio) {
        RadioKind.BLE -> i.addressType == BleAddressType.RANDOM || i.addressType == BleAddressType.ANONYMOUS
        RadioKind.WIFI -> (i.address.take(2).toIntOrNull(16) ?: 0) and 0x02 != 0
    }
}

/**
 * Common vendors (as the offline OUI table names them) mapped to a likely family. Matching is a
 * lower-case substring test. A vendor that makes several kinds of device maps by role: an access
 * point from a phone maker is still most likely a router or hotspot, so ambiguous makers only map
 * for the role named.
 */
object VendorFamilies {
    private data class Rule(val needles: List<String>, val family: DeviceFamily, val confidence: Confidence, val radios: Set<RadioKind>?, val role: WifiRole?)

    private fun r(family: DeviceFamily, confidence: Confidence, vararg needles: String, radios: Set<RadioKind>? = null, role: WifiRole? = null) =
        Rule(needles.toList(), family, confidence, radios, role)

    private val WIFI = setOf(RadioKind.WIFI)

    private val rules = listOf(
        // Network gear: routers and access points.
        r(DeviceFamily.ROUTER, Confidence.MEDIUM, "ubiquiti", "tp-link", "tp link", "netgear", "cisco", "meraki", "aruba", "ruckus", "mikrotik",
            "routerboard", "juniper", "zyxel", "d-link", "linksys", "eero", "fortinet", "edgecore", "cambium", "tenda", "arris", "sagemcom",
            "technicolor", "askey", "actiontec", "commscope", "extreme networks", "aerohive", "draytek", "peplink", "huawei technologies",
            "zte corporation", "nokia shanghai bell", "fiberhome", "sercomm", "avm gmbh", radios = WIFI),
        r(DeviceFamily.ROUTER, Confidence.LOW, "asustek", "xiaomi", "google", radios = WIFI, role = WifiRole.AP),
        // Smart home modules and devices.
        r(DeviceFamily.SMART_HOME, Confidence.MEDIUM, "espressif", "tuya", "shelly", "allterco", "itead", "wyze", "signify", "philips lighting", "lifx",
            "ecobee", "nest labs", "ring llc", "belkin", "sonoff", "broadlink", "lumi united", "aqara", "yeelight"),
        // Cameras (vendor only: calm, low confidence; the catalog handles signatures).
        r(DeviceFamily.CAMERA, Confidence.LOW, "hikvision", "dahua", "axis communications", "hanwha", "uniview", "reolink", "arlo"),
        // Audio.
        r(DeviceFamily.AUDIO, Confidence.MEDIUM, "bose", "sonos", "harman", "jbl", "skullcandy", "gn audio", "jabra", "sennheiser", "bang & olufsen", "marshall"),
        // Wearables.
        r(DeviceFamily.WEARABLE, Confidence.MEDIUM, "garmin", "fitbit", "huami", "zepp", "polar electro", "whoop", "amazfit", "suunto"),
        // Phones and computers (ambiguous makers only as clients or BLE).
        r(DeviceFamily.PHONE_PC, Confidence.LOW, "apple", "samsung", "oneplus", "guangdong oppo", "vivo mobile", "motorola", "xiaomi", "huawei device",
            "honor device", "google", "intel corporate", "liteon", "azurewave", "hon hai", "realtek", "dell", "lenovo", "microsoft", "hewlett packard", "asustek"),
    )

    /** (family, confidence) for [vendor], or null when the vendor says nothing about the kind of device. */
    fun familyFor(vendor: String, radio: RadioKind, role: WifiRole?): Pair<DeviceFamily, Confidence>? {
        val v = vendor.lowercase()
        val rule = rules.firstOrNull { rule ->
            (rule.radios == null || radio in rule.radios) &&
                (rule.role == null || rule.role == role) &&
                !(rule.family == DeviceFamily.PHONE_PC && role == WifiRole.AP) &&
                rule.needles.any { it in v }
        } ?: return null
        return rule.family to rule.confidence
    }
}
