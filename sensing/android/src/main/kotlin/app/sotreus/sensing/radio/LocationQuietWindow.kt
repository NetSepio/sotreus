/*
 * Adapted from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit ddc0d8c).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: integrated with observation ownership and Android 9 compatibility.
 */
package app.sotreus.sensing.radio

/**
 * Quiet period after the phone's Location switch turns on.
 * Scans stay stopped while Location is off. The off-to-on edge holds the radios for
 * [RADIO_QUIET_MS] before they start again. The GPS listener waits [LOCATION_LAG_MS] longer
 * so it is not requested on the BLE restart frame.
 */
class LocationQuietWindow(
    private val nowMs: () -> Long,
    private val radioQuietMs: Long = RADIO_QUIET_MS,
    private val locationLagMs: Long = LOCATION_LAG_MS,
) {
    private val gate = Any()
    private var wasOn: Boolean? = null
    private var radioUntil = 0L
    private var locationUntil = 0L
    private var radioResume = false
    private var locationResume = false

    /** Record the switch as it is now, without treating that reading as a change. */
    fun seed(locationOn: Boolean) {
        synchronized(gate) {
            if (wasOn == null) wasOn = locationOn
        }
    }

    /**
     * True only on the off-to-on edge, which is when the quiet window starts.
     * Turning Location off cancels a pending restart so the scans stay down.
     */
    fun observe(locationOn: Boolean): Boolean {
        synchronized(gate) {
            val previous = wasOn
            wasOn = locationOn
            if (previous == true && !locationOn) {
                radioUntil = 0L
                locationUntil = 0L
                radioResume = false
                locationResume = false
                return false
            }
            if (previous != false || !locationOn) return false
            val t = nowMs()
            radioUntil = t + radioQuietMs
            locationUntil = radioUntil + locationLagMs
            radioResume = true
            locationResume = true
            return true
        }
    }

    fun radioHoldMs(): Long = synchronized(gate) {
        (radioUntil - nowMs()).coerceAtLeast(0L)
    }

    fun radiosHeld(): Boolean = radioHoldMs() > 0L

    fun locationHeld(): Boolean = synchronized(gate) {
        nowMs() < locationUntil
    }

    /** True once, after the radio hold has elapsed. */
    fun takeRadioResume(): Boolean = synchronized(gate) {
        if (!radioResume || nowMs() < radioUntil) return false
        radioResume = false
        true
    }

    /** True once, after the radio hold and the extra GPS lag have both elapsed. */
    fun takeLocationResume(): Boolean = synchronized(gate) {
        if (!locationResume || nowMs() < locationUntil) return false
        locationResume = false
        true
    }

    companion object {
        const val RADIO_QUIET_MS = 1_500L
        const val LOCATION_LAG_MS = 500L
    }
}
