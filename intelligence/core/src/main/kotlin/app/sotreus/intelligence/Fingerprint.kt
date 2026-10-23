package app.sotreus.intelligence

import app.sotreus.core.model.BleAddressType
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.RadioKind

/**
 * Entity keys. Like Fieldwatch, an entity is keyed by radio kind + address, so a BLE address
 * rotation starts a new entity. The link confidence says how stable that address is.
 */
object Fingerprint {
    fun key(kind: RadioKind, address: String): String =
        (if (kind == RadioKind.WIFI) "wifi:" else "ble:") + address.uppercase()

    fun addressType(kind: RadioKind, reported: String?, address: String): BleAddressType = when {
        kind == RadioKind.WIFI -> BleAddressType.PUBLIC
        reported.equals("Public", true) -> BleAddressType.PUBLIC
        reported.equals("Anonymous", true) -> BleAddressType.ANONYMOUS
        reported.equals("Random", true) -> BleAddressType.RANDOM
        else -> BleAddressType.UNKNOWN
    }

    /** Random-address subtype from the top two bits: 11 static, 01 resolvable, 00 non-resolvable. */
    fun randomSubtype(address: String): RandomSubtype? {
        val first = address.take(2).toIntOrNull(16) ?: return null
        return when (first ushr 6) {
            0b11 -> RandomSubtype.STATIC
            0b01 -> RandomSubtype.RESOLVABLE
            0b00 -> RandomSubtype.NON_RESOLVABLE
            else -> null
        }
    }

    fun linkConfidence(kind: RadioKind, type: BleAddressType, address: String): Confidence = when {
        kind == RadioKind.WIFI -> if (isUniversal(address)) Confidence.HIGH else Confidence.LOW
        type == BleAddressType.PUBLIC -> Confidence.HIGH
        type == BleAddressType.RANDOM && randomSubtype(address) == RandomSubtype.STATIC -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    enum class RandomSubtype { STATIC, RESOLVABLE, NON_RESOLVABLE }

    /** The IEEE local/group bits describe Wi-Fi addresses, not BLE random-address subtypes. */
    fun isUniversal(address: String): Boolean = address.take(2).toIntOrNull(16)?.let { it and 3 == 0 } == true

    fun vendorLookupPrefix(kind: RadioKind, type: BleAddressType, address: String): String? {
        val hex = address.replace(":", "").replace("-", "").uppercase()
        if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" } || hex.all { it == '0' }) return null
        if (kind == RadioKind.BLE && type != BleAddressType.PUBLIC) return null
        if (!isUniversal(hex)) return null
        return hex.take(6)
    }
}
