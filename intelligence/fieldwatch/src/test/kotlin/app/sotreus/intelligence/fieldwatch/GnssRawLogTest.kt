/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit ddc0d8c).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed; adapted to Sotreus modules.
 */
package app.sotreus.intelligence.fieldwatch

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssRawLogTest {
    @Test
    fun utcMillisMatchesAPublishedGnssLoggerRow() {
        val utc = GnssRawTime.utcMillis(
            timeNanos = 32_083_712_000_000L,
            fullBiasNanos = -1_347_710_845_896_978_382L,
            biasNanos = 0.800237774848938,
            leapSecond = 18,
        )
        assertEquals(1_663_707_711_608L, utc)
    }

    @Test
    fun quietEpochsStayInMemoryUntilAHit() {
        val session = GnssRawSession()
        assertTrue(session.onSample(sample(0), episode = false, GnssRawMode.RECORD) is GnssRawCommand.None)
        assertTrue(session.onSample(sample(30_000), episode = false, GnssRawMode.RECORD) is GnssRawCommand.None)
        val open = session.onSample(sample(70_000), episode = true, GnssRawMode.RECORD) as GnssRawCommand.Open
        assertEquals(listOf(30_000L, 70_000L), open.samples.map { it.wallMs })
    }

    @Test
    fun theTailLastsThirtySecondsAndTheNextQuietEpochClosesIt() {
        val session = GnssRawSession()
        session.onSample(sample(70_000), episode = true, GnssRawMode.RECORD)
        assertTrue(session.onSample(sample(100_000), episode = false, GnssRawMode.RECORD) is GnssRawCommand.Append)
        assertTrue(session.onSample(sample(100_001), episode = false, GnssRawMode.RECORD) is GnssRawCommand.Close)
        assertTrue(session.onSample(sample(101_000), episode = false, GnssRawMode.RECORD) is GnssRawCommand.None)
    }

    @Test
    fun aHitDuringTheTailStaysInTheSameFile() {
        val session = GnssRawSession()
        session.onSample(sample(70_000), episode = true, GnssRawMode.RECORD)
        assertTrue(session.onSample(sample(90_000), episode = false, GnssRawMode.RECORD) is GnssRawCommand.Append)
        assertTrue(session.onSample(sample(95_000), episode = true, GnssRawMode.RECORD) is GnssRawCommand.Append)
        assertTrue(session.onSample(sample(125_000), episode = false, GnssRawMode.RECORD) is GnssRawCommand.Append)
        assertTrue(session.onSample(sample(125_001), episode = false, GnssRawMode.RECORD) is GnssRawCommand.Close)
    }

    @Test
    fun privacyDropsTheOpenFileAndTheSwitchOffKeepsIt() {
        val privacy = GnssRawSession()
        privacy.onSample(sample(1_000), episode = true, GnssRawMode.RECORD)
        assertTrue(privacy.onSample(sample(2_000), episode = true, GnssRawMode.PRIVACY) is GnssRawCommand.Drop)
        assertTrue(privacy.onSample(sample(3_000), episode = false, GnssRawMode.RECORD) is GnssRawCommand.None)

        val off = GnssRawSession()
        off.onSample(sample(1_000), episode = true, GnssRawMode.RECORD)
        assertTrue(off.onSample(sample(2_000), episode = true, GnssRawMode.OFF) is GnssRawCommand.Close)
        assertTrue(off.stop(drop = false) is GnssRawCommand.None)
    }

    @Test
    fun agcRowsLeaveRawGainBlankAndAPhoneWithoutThemKeepsIt() {
        val withAgc = sample(1_000, agcRows = true, rawAgc = 12.5)
        val agcBody = GnssRawFormat.body(withAgc)
        assertTrue(agcBody.contains("\nAgc,") || agcBody.startsWith("Agc,"))
        val raw = agcBody.lineSequence().first { it.startsWith("Raw,") }
        assertEquals("", fields(raw)[agcIndex()])

        val plain = sample(1_000, agcRows = false, rawAgc = 12.5)
        val plainBody = GnssRawFormat.body(plain)
        assertFalse(plainBody.contains("Agc,"))
        assertEquals("12.5", fields(plainBody.trim())[agcIndex()])
    }

    @Test
    fun rowWidthsMatchTheHeaders() {
        val body = GnssRawFormat.body(sample(1_000, agcRows = true, rawAgc = 4.0, fix = true))
        val raw = body.lineSequence().first { it.startsWith("Raw,") }
        val agc = body.lineSequence().first { it.startsWith("Agc,") }
        val fix = body.lineSequence().first { it.startsWith("Fix,") }
        assertEquals(fields(GnssRawFormat.RAW_HEADER).size, fields(raw).size)
        assertEquals(fields(GnssRawFormat.AGC_HEADER).size, fields(agc).size)
        assertEquals(fields(GnssRawFormat.FIX_HEADER).size, fields(fix).size)
        assertEquals("1", fields(fix).last())
        assertTrue(GnssRawFormat.header().contains("# ${GnssRawFormat.RAW_HEADER}"))
        assertFalse(GnssRawFormat.acceptableName("../gnss-20261019-180000.txt"))
        assertTrue(GnssRawFormat.acceptableName("gnss-20261019-180000.txt"))
    }

    @Test
    fun theCapDeletesTheOldestAndKeepsTheNewest() {
        val files = listOf("a" to 10L, "b" to 10L, "c" to 10L)
        assertEquals(listOf("a"), GnssRawFormat.victims(files, capBytes = 20L))
        assertEquals(emptyList<String>(), GnssRawFormat.victims(listOf("only" to 50L), capBytes = 20L))
    }



    private fun fields(line: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (index in line.indices) {
            if (line[index] == ',') {
                out += line.substring(start, index)
                start = index + 1
            }
        }
        out += line.substring(start)
        return out
    }

    private fun agcIndex(): Int = fields(GnssRawFormat.RAW_HEADER).indexOf("AgcDb")

    private fun sample(
        wall: Long,
        agcRows: Boolean = false,
        rawAgc: Double? = null,
        fix: Boolean = false,
    ): GnssRawSample {
        val clock = GnssRawClock(
            utcTimeMillis = wall,
            timeNanos = 1L,
            leapSecond = 18,
            timeUncertaintyNanos = null,
            fullBiasNanos = -2L,
            biasNanos = null,
            biasUncertaintyNanos = null,
            driftNanosPerSecond = null,
            driftUncertaintyNanosPerSecond = null,
            hardwareClockDiscontinuityCount = 0,
            elapsedRealtimeNanos = 3L,
        )
        val sat = GnssRawSat(
            svid = 5,
            timeOffsetNanos = 0.0,
            state = 1,
            receivedSvTimeNanos = 4L,
            receivedSvTimeUncertaintyNanos = 6L,
            cn0DbHz = 30.0,
            pseudorangeRateMps = 1.0,
            pseudorangeRateUncMps = 0.2,
            adrState = 0,
            adrMeters = 0.0,
            adrUncMeters = 0.0,
            carrierFrequencyHz = 1_575_420_030.0,
            carrierCycles = null,
            carrierPhase = null,
            carrierPhaseUnc = null,
            multipathIndicator = 0,
            snrInDb = null,
            constellationType = 1,
            agcDb = rawAgc,
            basebandCn0DbHz = null,
            fullInterSignalBiasNanos = null,
            fullInterSignalBiasUncNanos = null,
            satelliteInterSignalBiasNanos = null,
            satelliteInterSignalBiasUncNanos = null,
            codeType = "C,1",
        )
        return GnssRawSample(
            wallMs = wall,
            clock = clock,
            sats = listOf(sat),
            agcs = if (agcRows) listOf(GnssRawAgc(9.0, 1_575_420_030L, 1)) else emptyList(),
            fix = if (!fix) null else GnssRawFix(
                provider = "GPS",
                latitude = 40.0,
                longitude = -74.0,
                altitudeMeters = null,
                speedMps = null,
                accuracyMeters = 5f,
                bearingDegrees = null,
                unixTimeMillis = wall,
                speedAccuracyMps = null,
                bearingAccuracyDegrees = null,
                elapsedRealtimeNanos = null,
                verticalAccuracyMeters = null,
                mock = true,
            ),
        )
    }
}
