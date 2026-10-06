package app.sotreus.core.model

enum class SessionKind { JOURNEY, SIT }

/** Timeline entries for a live session (screen 11). System notes are not observations. */
enum class SessionEventKind {
    STARTED, NEW_FINGERPRINT, FINGERPRINT_LOST, TAGGED_REENCOUNTER, FAMILY_SIGNATURE, ATTENTION,
    WIFI_SCAN_DELAYED, BLE_PAUSED, USER_NOTE, ENDED,
    ;

    val isSystemNote: Boolean get() = this == WIFI_SCAN_DELAYED || this == BLE_PAUSED
    val isSensed: Boolean get() = this == NEW_FINGERPRINT || this == FINGERPRINT_LOST ||
        this == TAGGED_REENCOUNTER || this == FAMILY_SIGNATURE || this == ATTENTION
}
