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
        kind == RadioKind.WIFI -> Confidence.HIGH
        type == BleAddressType.PUBLIC -> Confidence.HIGH
        type == BleAddressType.RANDOM && randomSubtype(address) == RandomSubtype.STATIC -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    enum class RandomSubtype { STATIC, RESOLVABLE, NON_RESOLVABLE }
}
