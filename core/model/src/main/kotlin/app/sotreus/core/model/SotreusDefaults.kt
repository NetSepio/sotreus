package app.sotreus.core.model

/**
 * Non-visual values from `design/tokens.json` that sensing and intelligence code depend on.
 * Kept here, not in the design system, so Android-free modules can use them.
 */
object SotreusDefaults {
    /** Proximity Bands: NEAR is at or above this 30 s average. */
    const val NEAR_MIN_DBM = -60

    /** Proximity Bands: MID is at or above this and below [NEAR_MIN_DBM]; FAR is below it. */
    const val MID_MIN_DBM = -75

    const val RSSI_AVERAGE_WINDOW_SECONDS = 30

    /** A BLE entity is shown as stale after this long without a packet. */
    const val BLE_STALE_HOLD_SECONDS_DEFAULT = 30

    /** Android 9+ foreground Wi-Fi scan budget: [WIFI_SCANS_PER_WINDOW] per [WIFI_SCAN_WINDOW_SECONDS]. */
    const val WIFI_SCANS_PER_WINDOW = 4
    const val WIFI_SCAN_WINDOW_SECONDS = 120
}
