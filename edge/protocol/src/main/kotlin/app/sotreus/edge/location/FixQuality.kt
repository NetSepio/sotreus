package app.sotreus.edge.location

/**
 * The quality limits for Edge sighting locations (HANDOFF_V2_EDGE.md §4.4). Defaults are the
 * agreed values; Field tools may tighten or loosen them within [sane], never remove them.
 */
data class QualityLimits(
    /** Bluetooth link to the Edge, averaged over [togetherWindowMs], at or above this = together. */
    val togetherMinDbm: Int = -75,
    val togetherWindowMs: Long = 15_000,
    /** How long without a link RSSI sample before "together" lapses. */
    val togetherStaleMs: Long = 12_000,
    val maxAccuracyM: Float = 25f,
    val maxFixAgeMs: Long = 10_000,
    /** A live sample takes the nearest accepted fix within ± this. */
    val liveJoinMs: Long = 5_000,
    /** A window is a point when the located span is at most this. */
    val pointMaxSpanM: Double = 50.0,
    val fullCoverage: Double = 0.8,
    val partialCoverage: Double = 0.2,
    /** Resend set_time beyond this offset. */
    val maxClockOffsetMs: Long = 1_000,
    /** Stationary "use this phone's location" accepts the averaged position at or below this (Field tools). */
    val stationaryAcceptM: Float = 30f,
    val stationaryAverageMs: Long = 30_000,
    /** Moved check: fewer than this share of the baseline access points still heard. */
    val movedOverlap: Double = 0.30,
    val movedBaselineMin: Int = 5,
    /** Moved check: a strongly linked phone with a good fix this far from the set position. */
    val movedCrossCheckM: Double = 200.0,
) {
    /** Clamped to ranges that still mean something. */
    fun sane(): QualityLimits = copy(
        togetherMinDbm = togetherMinDbm.coerceIn(-90, -40),
        maxAccuracyM = maxAccuracyM.coerceIn(5f, 100f),
        maxFixAgeMs = maxFixAgeMs.coerceIn(2_000, 30_000),
        liveJoinMs = liveJoinMs.coerceIn(1_000, 15_000),
        pointMaxSpanM = pointMaxSpanM.coerceIn(10.0, 500.0),
        stationaryAcceptM = stationaryAcceptM.coerceIn(5f, 100f),
    )
}

enum class FixRejection { NOT_TOGETHER, NO_ACCURACY, TOO_INACCURATE, TOO_OLD, IN_FUTURE, MOCK, SPOOFING_WARNING }

object FixQuality {
    /**
     * Why [fix] may not tag a sighting observed at [atMs], or null when it may. [together] is the
     * phone–Edge state at [atMs]; [spoofingWarning] is the GNSS health check's state then.
     */
    fun check(fix: GeoFix, atMs: Long, together: Boolean, spoofingWarning: Boolean, limits: QualityLimits = QualityLimits()): FixRejection? {
        val acc = fix.accuracyM
        return when {
            fix.mock -> FixRejection.MOCK
            spoofingWarning -> FixRejection.SPOOFING_WARNING
            !together -> FixRejection.NOT_TOGETHER
            acc == null || acc.isNaN() -> FixRejection.NO_ACCURACY
            acc > limits.maxAccuracyM -> FixRejection.TOO_INACCURATE
            fix.atMs > atMs + limits.liveJoinMs -> FixRejection.IN_FUTURE
            atMs - fix.atMs > limits.maxFixAgeMs -> FixRejection.TOO_OLD
            else -> null
        }
    }

    /** A fix that may be stored for tagging at all (checked when it arrives). */
    fun acceptable(fix: GeoFix, together: Boolean, spoofingWarning: Boolean, limits: QualityLimits = QualityLimits()): Boolean =
        check(fix, fix.atMs, together, spoofingWarning, limits) == null
}

/**
 * Decides whether this phone is with the Edge, from the Bluetooth link's signal strength
 * (HANDOFF_V2_EDGE.md §4.4). A Wi-Fi link never counts. Holds about 30 minutes of samples so a
 * sighting window can be checked after its record arrives.
 */
class TogetherTracker(private val limits: QualityLimits = QualityLimits(), private val keepMs: Long = 30 * 60_000L) {
    private data class Sample(val atMs: Long, val dbm: Int?)

    private val samples = ArrayDeque<Sample>()

    /** A link RSSI reading (the phone reads it about every 5 s while connected). */
    @Synchronized fun linkRssi(atMs: Long, dbm: Int) = add(Sample(atMs, dbm))

    /** The link dropped. Until the next reading nothing after [atMs] is together. */
    @Synchronized fun linkDown(atMs: Long) = add(Sample(atMs, null))

    private fun add(s: Sample) {
        samples.addLast(s)
        while (samples.isNotEmpty() && s.atMs - samples.first().atMs > keepMs) samples.removeFirst()
    }

    @Synchronized fun isTogether(atMs: Long): Boolean {
        val window = samples.filter { it.atMs in (atMs - limits.togetherWindowMs)..atMs }
        val last = samples.lastOrNull { it.atMs <= atMs } ?: return false
        if (last.dbm == null) return false
        if (atMs - last.atMs > limits.togetherStaleMs) return false
        // A link drop inside the averaging window breaks "together" until the window has passed it.
        if (window.any { it.dbm == null }) return false
        val values = window.mapNotNull { it.dbm }.ifEmpty { listOf(last.dbm) }
        return values.average() >= limits.togetherMinDbm
    }

    @Synchronized fun clear() = samples.clear()
}
