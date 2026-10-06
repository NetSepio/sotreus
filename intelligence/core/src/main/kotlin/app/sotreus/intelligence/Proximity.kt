package app.sotreus.intelligence

import app.sotreus.core.model.ProximityCue
import app.sotreus.intelligence.fieldwatch.Hunt
import app.sotreus.intelligence.fieldwatch.HuntCue
import app.sotreus.intelligence.fieldwatch.RssiSample

/**
 * Proximity check (screen 07) on top of Fieldwatch [Hunt]: recent 2 s vs earlier 3.5–8 s,
 * ±3 dB steps, quiet after 8 s, very strong at ≥ −45 dBm. Cues are relative loudness only.
 */
object Proximity {
    fun cue(samples: List<Pair<Long, Int>>, now: Long, lastHeard: Long?, gone: Boolean): ProximityCue {
        val rs = samples.map { (at, rssi) -> RssiSample(at, rssi) }
        return when (Hunt.cue(rs, now, lastHeard, gone)) {
            HuntCue.VERY_CLOSE -> ProximityCue.VERY_STRONG
            HuntCue.CLOSER -> ProximityCue.LOUDER
            HuntCue.FURTHER -> ProximityCue.QUIETER
            HuntCue.SAME -> ProximityCue.SAME
            HuntCue.WAITING -> ProximityCue.LISTENING
            HuntCue.QUIET -> ProximityCue.QUIET
            HuntCue.GONE -> ProximityCue.GONE
        }
    }

    /** −40 dBm → 90 ms, −90 dBm → 1400 ms; null = silent. */
    fun tickIntervalMs(rssi: Int?, cue: ProximityCue): Long? {
        val hunt = when (cue) {
            ProximityCue.QUIET -> HuntCue.QUIET
            ProximityCue.GONE -> HuntCue.GONE
            else -> HuntCue.SAME
        }
        return Hunt.tickIntervalMs(rssi, hunt)
    }

    /** Average of the last [fromMs]..[toMs] window, for the "now vs earlier" line. */
    fun average(samples: List<Pair<Long, Int>>, now: Long, fromMs: Long, toMs: Long): Int? =
        samples.filter { (at, _) -> at in (now - fromMs)..(now - toMs) }
            .map { it.second }.takeIf { it.isNotEmpty() }?.average()?.toInt()
}
