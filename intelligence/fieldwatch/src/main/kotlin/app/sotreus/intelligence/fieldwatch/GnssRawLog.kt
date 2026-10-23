/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit ddc0d8c).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed; adapted to Sotreus modules.
 */
package app.sotreus.intelligence.fieldwatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A GnssLogger text file around one GNSS hit. Raw, Agc, and Fix lines only.
 * The detector is not involved. This only decides when a file is open.
 */
object GnssRawTime {
    /** 00:00:00 UTC on 6 January 1980. */
    const val GPS_UNIX_MS = 315964800000L

    /** Leap seconds since the GPS epoch, when the clock does not say. */
    const val LEAP_SECOND = 18

    fun utcMillis(timeNanos: Long, fullBiasNanos: Long, biasNanos: Double, leapSecond: Int): Long {
        val gpsMs = (timeNanos - fullBiasNanos) / 1_000_000.0 - biasNanos / 1_000_000.0
        return (gpsMs + GPS_UNIX_MS - leapSecond * 1_000.0).toLong()
    }
}

data class GnssRawClock(
    val utcTimeMillis: Long,
    val timeNanos: Long,
    val leapSecond: Int?,
    val timeUncertaintyNanos: Double?,
    val fullBiasNanos: Long,
    val biasNanos: Double?,
    val biasUncertaintyNanos: Double?,
    val driftNanosPerSecond: Double?,
    val driftUncertaintyNanosPerSecond: Double?,
    val hardwareClockDiscontinuityCount: Int,
    val elapsedRealtimeNanos: Long?,
)

data class GnssRawSat(
    val svid: Int,
    val timeOffsetNanos: Double,
    val state: Int,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long,
    val cn0DbHz: Double,
    val pseudorangeRateMps: Double,
    val pseudorangeRateUncMps: Double,
    val adrState: Int,
    val adrMeters: Double,
    val adrUncMeters: Double,
    val carrierFrequencyHz: Double?,
    val carrierCycles: Long?,
    val carrierPhase: Double?,
    val carrierPhaseUnc: Double?,
    val multipathIndicator: Int,
    val snrInDb: Double?,
    val constellationType: Int,
    /** Null when this epoch also has [GnssRawSample.agcs]. */
    val agcDb: Double?,
    val basebandCn0DbHz: Double?,
    val fullInterSignalBiasNanos: Double?,
    val fullInterSignalBiasUncNanos: Double?,
    val satelliteInterSignalBiasNanos: Double?,
    val satelliteInterSignalBiasUncNanos: Double?,
    val codeType: String?,
)

data class GnssRawAgc(
    val agcDb: Double,
    val carrierFrequencyHz: Long,
    val constellationType: Int,
)

data class GnssRawFix(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedMps: Float?,
    val accuracyMeters: Float?,
    val bearingDegrees: Float?,
    val unixTimeMillis: Long,
    val speedAccuracyMps: Float?,
    val bearingAccuracyDegrees: Float?,
    val elapsedRealtimeNanos: Long?,
    val verticalAccuracyMeters: Float?,
    val mock: Boolean,
)

data class GnssRawSample(
    val wallMs: Long,
    val clock: GnssRawClock,
    val sats: List<GnssRawSat>,
    val agcs: List<GnssRawAgc>,
    val fix: GnssRawFix?,
)

enum class GnssRawMode { RECORD, OFF, PRIVACY }

sealed class GnssRawCommand {
    data object None : GnssRawCommand()
    data class Open(val samples: List<GnssRawSample>) : GnssRawCommand()
    data class Append(val sample: GnssRawSample) : GnssRawCommand()
    data object Close : GnssRawCommand()
    data object Drop : GnssRawCommand()
}

/**
 * About a minute before a hit, through 30 seconds after it clears.
 * One open hit is one file. A later hit during the tail stays in that file.
 */
class GnssRawSession(
    private val preRollMs: Long = PRE_ROLL_MS,
    private val tailMs: Long = TAIL_MS,
    private val maxBuffered: Int = MAX_BUFFERED,
) {
    private val buffer = ArrayDeque<GnssRawSample>()
    private var writing = false
    private var episodeAt = 0L

    fun onSample(sample: GnssRawSample, episode: Boolean, mode: GnssRawMode): GnssRawCommand {
        if (mode == GnssRawMode.PRIVACY) return stop(drop = true)
        if (mode == GnssRawMode.OFF) return stop(drop = false)
        if (episode) {
            episodeAt = sample.wallMs
            if (!writing) {
                trim(sample.wallMs)
                val prior = buffer.toList()
                buffer.clear()
                writing = true
                return GnssRawCommand.Open(prior + sample)
            }
            return GnssRawCommand.Append(sample)
        }
        if (writing) {
            if (sample.wallMs > episodeAt + tailMs) {
                writing = false
                buffer.clear()
                buffer.add(sample)
                return GnssRawCommand.Close
            }
            return GnssRawCommand.Append(sample)
        }
        trim(sample.wallMs)
        buffer.add(sample)
        while (buffer.size > maxBuffered) buffer.removeFirst()
        return GnssRawCommand.None
    }

    /** Scan stopped, switch off, or privacy. Privacy deletes the open file. */
    fun stop(drop: Boolean): GnssRawCommand {
        buffer.clear()
        if (!writing) return GnssRawCommand.None
        writing = false
        episodeAt = 0L
        return if (drop) GnssRawCommand.Drop else GnssRawCommand.Close
    }

    /** The file could not be opened. Forget the hit so a later epoch can try again. */
    fun failOpen() {
        writing = false
        episodeAt = 0L
        buffer.clear()
    }

    private fun trim(now: Long) {
        val cutoff = now - preRollMs
        while (buffer.isNotEmpty() && buffer.first().wallMs < cutoff) buffer.removeFirst()
    }

    companion object {
        const val PRE_ROLL_MS = 60_000L
        const val TAIL_MS = 30_000L
        const val MAX_BUFFERED = 240
        const val CAP_BYTES = 20L * 1024L * 1024L
    }
}

object GnssRawFormat {
    const val RAW_HEADER =
        "Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos," +
            "BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond," +
            "HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos," +
            "ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond," +
            "PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState," +
            "AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz," +
            "CarrierCycles,CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb," +
            "ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos," +
            "FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos," +
            "SatelliteInterSignalBiasUncertaintyNanos,CodeType,ChipsetElapsedRealtimeNanos"

    const val FIX_HEADER =
        "Fix,Provider,LatitudeDegrees,LongitudeDegrees,AltitudeMeters,SpeedMps,AccuracyMeters," +
            "BearingDegrees,UnixTimeMillis,SpeedAccuracyMps,BearingAccuracyDegrees," +
            "elapsedRealtimeNanos,VerticalAccuracyMeters,MockLocation"

    const val AGC_HEADER =
        "Agc,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos," +
            "BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond," +
            "HardwareClockDiscontinuityCount,AgcDb,CarrierFrequencyHz,ConstellationType"

    private val NAME = Regex("gnss-(\\d{8})-(\\d{6})\\.txt")
    private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun header(): String = buildString {
        append("# Fieldwatch raw GNSS, recorded around a GNSS interference hit.\n")
        append("# Gain is on Agc lines when this phone reports it that way. Otherwise it is on the Raw line.\n")
        append("# \n")
        append("# ")
        append(RAW_HEADER)
        append('\n')
        append("# \n")
        append("# ")
        append(FIX_HEADER)
        append('\n')
        append("# \n")
        append("# ")
        append(AGC_HEADER)
        append('\n')
    }

    fun fileName(startedMs: Long): String = synchronized(STAMP) {
        "gnss-${STAMP.format(Date(startedMs))}.txt"
    }

    fun startedMs(name: String): Long? {
        val match = NAME.matchEntire(name) ?: return null
        return runCatching {
            synchronized(STAMP) {
                STAMP.parse("${match.groupValues[1]}-${match.groupValues[2]}")
            }?.time
        }.getOrNull()
    }

    fun acceptableName(name: String): Boolean = NAME.matches(name)

    /** Rows for one epoch. Empty when the epoch has nothing to write. */
    fun body(sample: GnssRawSample): String = buildString {
        sample.fix?.let { append(fixLine(it)) }
        for (agc in sample.agcs) append(agcLine(sample.clock, agc))
        val sats = if (sample.agcs.isEmpty()) sample.sats else sample.sats.map { it.copy(agcDb = null) }
        for (sat in sats) append(rawLine(sample.clock, sat))
    }

    fun rawLine(clock: GnssRawClock, sat: GnssRawSat): String = csv(
        "Raw",
        clock.utcTimeMillis,
        clock.timeNanos,
        clock.leapSecond,
        clock.timeUncertaintyNanos,
        clock.fullBiasNanos,
        clock.biasNanos,
        clock.biasUncertaintyNanos,
        clock.driftNanosPerSecond,
        clock.driftUncertaintyNanosPerSecond,
        clock.hardwareClockDiscontinuityCount,
        sat.svid,
        sat.timeOffsetNanos,
        sat.state,
        sat.receivedSvTimeNanos,
        sat.receivedSvTimeUncertaintyNanos,
        sat.cn0DbHz,
        sat.pseudorangeRateMps,
        sat.pseudorangeRateUncMps,
        sat.adrState,
        sat.adrMeters,
        sat.adrUncMeters,
        hz(sat.carrierFrequencyHz),
        sat.carrierCycles,
        sat.carrierPhase,
        sat.carrierPhaseUnc,
        sat.multipathIndicator,
        sat.snrInDb,
        sat.constellationType,
        sat.agcDb,
        sat.basebandCn0DbHz,
        sat.fullInterSignalBiasNanos,
        sat.fullInterSignalBiasUncNanos,
        sat.satelliteInterSignalBiasNanos,
        sat.satelliteInterSignalBiasUncNanos,
        code(sat.codeType),
        clock.elapsedRealtimeNanos,
    )

    fun agcLine(clock: GnssRawClock, agc: GnssRawAgc): String = csv(
        "Agc",
        clock.utcTimeMillis,
        clock.timeNanos,
        clock.leapSecond,
        clock.timeUncertaintyNanos,
        clock.fullBiasNanos,
        clock.biasNanos,
        clock.biasUncertaintyNanos,
        clock.driftNanosPerSecond,
        clock.driftUncertaintyNanosPerSecond,
        clock.hardwareClockDiscontinuityCount,
        agc.agcDb,
        agc.carrierFrequencyHz,
        agc.constellationType,
    )

    fun fixLine(fix: GnssRawFix): String = csv(
        "Fix",
        fix.provider,
        fix.latitude,
        fix.longitude,
        fix.altitudeMeters,
        fix.speedMps,
        fix.accuracyMeters,
        fix.bearingDegrees,
        fix.unixTimeMillis,
        fix.speedAccuracyMps,
        fix.bearingAccuracyDegrees,
        fix.elapsedRealtimeNanos,
        fix.verticalAccuracyMeters,
        if (fix.mock) 1 else 0,
    )

    /**
     * Oldest files to delete so the rest fit under [capBytes].
     * [files] is oldest first. The newest file is kept even when it is the one over the cap.
     */
    fun victims(files: List<Pair<String, Long>>, capBytes: Long): List<String> {
        if (files.size < 2) return emptyList()
        var total = files.sumOf { it.second }
        val drop = ArrayList<String>()
        var index = 0
        while (total > capBytes && index < files.lastIndex) {
            drop += files[index].first
            total -= files[index].second
            index++
        }
        return drop
    }

    private fun hz(value: Double?): Long? =
        if (value == null || !value.isFinite()) null else value.toLong()

    private fun code(value: String?): String? =
        value?.replace(',', ' ')?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.ifEmpty { null }

    private fun csv(vararg cells: Any?): String = cells.joinToString(",") { cell(it) } + "\n"

    private fun cell(value: Any?): String = when (value) {
        null -> ""
        is Double -> if (value.isFinite()) value.toString() else ""
        is Float -> if (value.isFinite()) value.toDouble().toString() else ""
        else -> value.toString()
    }
}
