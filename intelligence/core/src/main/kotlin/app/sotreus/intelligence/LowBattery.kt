/*
 * Adapted from Fieldwatch BatteryFloor (https://github.com/OffGridPete/Fieldwatch, commit 60c8ecc).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: the slow-down only; it never stops scanning.
 */
package app.sotreus.intelligence

/**
 * Battery saver at low battery (opt-in): at or below the chosen level, and not charging, this phone's
 * scans use Battery saver. The saved intensity does not change. The slow-down holds through three points
 * above the level and lifts at the fourth, or on charge.
 */
object LowBattery {
    /** Percent; 0 is off. */
    val CHOICES = listOf(0, 10, 15, 20, 30)

    const val LIFT_POINTS = 4

    /** [slowed] is the last answer. A missing reading neither starts nor lifts the slow-down. */
    fun slowed(levelPct: Int, percent: Int?, charging: Boolean, slowed: Boolean): Boolean = when {
        levelPct <= 0 -> false
        percent == null -> slowed
        charging -> false
        slowed -> percent < levelPct + LIFT_POINTS
        else -> percent <= levelPct
    }
}
