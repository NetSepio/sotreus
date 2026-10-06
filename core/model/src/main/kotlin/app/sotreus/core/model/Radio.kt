package app.sotreus.core.model

enum class RadioKind { BLE, WIFI }

enum class Confidence { LOW, MEDIUM, HIGH }

/** The label the user gives an entity (handoff §10). */
enum class UserEntityState { UNCLASSIFIED, MINE, EXPECTED, TAGGED, WATCH, IGNORE }

/**
 * Sotreus device families. Mapped from Fieldwatch signature classes; the UI names them calmly
 * ("Camera-family signature"), never as a verdict.
 */
enum class DeviceFamily {
    FINDER_TAG, BEACON, SIGNAGE, WEARABLE, CAMERA, DRONE, TEST_TOOL, PUBLIC_SAFETY, VEHICLE,
    GLASSES, AUDIO, THERMOSTAT, ACCESS_CONTROL, HEALTH, SMART_HOME, ROUTER, MESH_RADIO, PHONE_PC, OTHER,
    ;

    /** Families that feed the attention engine's "known signature" input. */
    val attentionRelevant: Boolean
        get() = this == FINDER_TAG || this == CAMERA || this == PUBLIC_SAFETY || this == TEST_TOOL ||
            this == GLASSES || this == DRONE
}

/** Proximity Bands ring. Signal strength only: never distance, never direction. */
enum class ProximityBand {
    NEAR, MID, FAR;

    companion object {
        fun of(avgDbm: Double): ProximityBand = when {
            avgDbm >= SotreusDefaults.NEAR_MIN_DBM -> NEAR
            avgDbm >= SotreusDefaults.MID_MIN_DBM -> MID
            else -> FAR
        }
    }
}

/** How an entity relates to the current place right now; drives glyphs and state chips. */
enum class PresenceState { NEW, FAMILIAR, SEEN_ELSEWHERE, UNKNOWN }

enum class ScanIntensity { SAVER, BALANCED, PERFORMANCE }

/** Relative-loudness cue for the proximity check (screen 07). No distance words. */
enum class ProximityCue { VERY_STRONG, LOUDER, SAME, QUIETER, QUIET, GONE, LISTENING }

enum class BleAddressType { PUBLIC, RANDOM, ANONYMOUS, UNKNOWN }
