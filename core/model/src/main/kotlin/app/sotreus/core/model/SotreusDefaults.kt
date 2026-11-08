package app.sotreus.core.model

/**
 * Non-visual values from `design/tokens.json` that sensing and intelligence code depend on.
 * Kept here, not in the design system, so Android-free modules can use them.
 */
object SotreusDefaults {
    /** V2: a phone fix tags an Edge sighting only at or below this horizontal accuracy (m). */
    const val EDGE_MAX_ACCURACY_M = 25

    /** V2: phone and Edge are together when the Bluetooth link averages at or above this. */
    const val EDGE_TOGETHER_MIN_DBM = -75

    /** V2: "Set from this phone" accepts a stationary Edge's averaged position at or below this (m). */
    const val EDGE_STATIONARY_LIMIT_M = 30

    /** Field tools choices for [EDGE_STATIONARY_LIMIT_M]. */
    val EDGE_STATIONARY_LIMITS_M = listOf(15, 30, 50)

    /** Automatic places (docs/plans/auto-places.md): radius choices for a new place, in metres; 50 is the default. */
    val AUTO_PLACE_RADII_M = listOf(25, 50, 100, 200)

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
