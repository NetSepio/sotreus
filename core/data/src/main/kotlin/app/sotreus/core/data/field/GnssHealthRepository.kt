package app.sotreus.core.data.field

import android.content.Context
import android.location.Location
import android.os.SystemClock
import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.SessionEventEntity
import app.sotreus.core.model.SessionEventKind
import app.sotreus.intelligence.fieldwatch.*
import app.sotreus.sensing.GnssSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class GnssHealth(
    val profile: GnssPhoneProfile? = null,
    val calibration: GnssCalUi = GnssCalUi(),
    val status: String = "Off",
    val bands: List<String> = emptyList(),
    val notice: GnssNotice? = null,
)

/** Calibration never retains fixes; the saved profile contains receiver quality statistics only. */
@Singleton
class GnssHealthRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val source: GnssSource,
    private val sessions: SessionDao,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "gnss-profile.json")
    private val json = Json { ignoreUnknownKeys = true }
    private var profile = runCatching { json.decodeFromString<GnssPhoneProfile>(file.readText()) }.getOrNull()
    private val _state = MutableStateFlow(GnssHealth(profile = profile))
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var active = false
    private var enabled = false
    private var runningMonitor = false
    private var spoofChecks = false
    private var sensitivity = GnssSensitivity.MEDIUM

    @Synchronized fun sync(active: Boolean, enabled: Boolean, spoof: Boolean = spoofChecks, level: String = sensitivity.name) {
        val nextLevel = runCatching { GnssSensitivity.valueOf(level) }.getOrDefault(GnssSensitivity.MEDIUM)
        val changed = spoofChecks != spoof || sensitivity != nextLevel
        spoofChecks = spoof; sensitivity = nextLevel
        this.active = active; this.enabled = enabled
        if (_state.value.calibration.running) {
            if (!active) stopCalibration("Calibration stopped when observation ended")
            return
        }
        val wanted = active && enabled && profile != null
        if (wanted == runningMonitor && !changed) return
        runningMonitor = wanted
        val previous = job; previous?.cancel()
        if (!wanted) { _state.update { it.copy(status = "Off", notice = null) }; return }
        job = scope.launch {
            previous?.join()
            val p = profile ?: return@launch
            val detector = GnssDetector(GnssConfig(sensitivity = sensitivity, spoofChecks = spoofChecks, cn0FloorDb = p.cn0FloorDb, agcFloorDb = p.agcFloorDb))
            val alerts = GnssAlerts()
            var fix: Location? = null
            var network: Location? = null
            var positionSince = 0L
            var hopsLeft = 0
            var discontinuity = 0
            var lastNotice = 0L
            source.events().catch { _state.update { it.copy(status = "GNSS unavailable", notice = null) } }.collect { event ->
                when (event) {
                    is GnssSource.Event.Fix -> {
                        val value = event.value
                        if (value.provider == "gps") {
                            val previous = fix
                            if (previous != null && !Geo.hopPlausible(GpsSample(previous.time, previous.latitude, previous.longitude), value.latitude, value.longitude, value.time)) hopsLeft = 8
                            fix = value
                        } else network = value
                    }
                    is GnssSource.Event.State -> {
                        if (event.paused) detector.onEpoch(GnssEpoch(SystemClock.elapsedRealtime(), System.currentTimeMillis(), discontinuity, emptyMap()), GnssSide(engineStopped = true))
                        _state.update { it.copy(status = event.text, notice = if (event.paused) null else it.notice) }
                    }
                    is GnssSource.Event.Epoch -> {
                        @Suppress("DEPRECATION") val mock = fix?.isFromMockProvider == true
                        discontinuity = event.value.clockDiscontinuity
                        val gps = fix; val net = network
                        fun age(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
                        val mismatch = gps != null && net != null && GnssPositionWitness.rawDisagree(gps.accuracy.toDouble(), net.accuracy.toDouble(),
                            Geo.meters(gps.latitude, gps.longitude, net.latitude, net.longitude), age(gps), age(net))
                        val hold = GnssPositionWitness.hold(mismatch, positionSince, event.value.wallMs); positionSince = hold.sinceMs
                        val clockMismatch = if (android.os.Build.VERSION.SDK_INT >= 33 && gps != null) runCatching {
                            kotlin.math.abs(android.os.SystemClock.currentNetworkTimeClock().millis() - (gps.time + age(gps))) > 1_000
                        }.getOrDefault(false) else false
                        val snap = detector.onEpoch(event.value, GnssSide(mock = mock, positionDisagree = hold.on,
                            timeDisagree = clockMismatch, impossibleHop = hopsLeft > 0, fixLost = gps != null && age(gps) > 15_000))
                        if (hopsLeft > 0) hopsLeft--
                        val notice = if (p.suppressAlerts) null else alerts.onLives(snap.lives, GnssConfidence.MEDIUM, event.value.wallMs).notice
                        _state.update { it.copy(status = snap.statusLine, bands = snap.bandLines, notice = notice) }
                        if (notice != null && notice.showDialog && event.value.wallMs - lastNotice > 60_000) {
                            lastNotice = event.value.wallMs
                            sessions.active()?.let { s -> sessions.insertEvent(SessionEventEntity(sessionId = s.id, atMs = event.value.wallMs,
                                kind = SessionEventKind.SYSTEM_NOTE, text = "GNSS: ${notice.line}")) }
                        }
                    }
                }
            }
        }
    }

    @Synchronized fun calibrate() {
        if (!active) { _state.update { it.copy(calibration = GnssCalUi(rejection = "Turn on Location and grant observation permissions first")) }; return }
        if (_state.value.calibration.running) return
        val previous = job; previous?.cancel(); runningMonitor = false
        _state.update { it.copy(calibration = GnssCalUi(running = true, leftSec = 60), status = "Calibrating") }
        job = scope.launch {
            previous?.join()
            val epochs = ArrayList<GnssEpoch>(); val fixes = ArrayList<GnssCalFix>()
            val start = SystemClock.elapsedRealtime()
            val countdown = launch {
                while (isActive) {
                    delay(1_000)
                    _state.update { it.copy(calibration = it.calibration.copy(leftSec = ((60_000 - (SystemClock.elapsedRealtime() - start)) / 1000).toInt().coerceAtLeast(0))) }
                }
            }
            try {
                withTimeoutOrNull(GnssCalibration.DURATION_MS) {
                    source.events().collect { e ->
                        _state.update { it.copy(calibration = GnssCalUi(true, ((60_000 - (SystemClock.elapsedRealtime() - start)) / 1000).toInt().coerceAtLeast(0))) }
                        when (e) {
                            is GnssSource.Event.Epoch -> if (epochs.size < 180) epochs += e.value
                            is GnssSource.Event.Fix -> if (e.value.provider == "gps" && fixes.size < 180) {
                                val l = e.value
                                @Suppress("DEPRECATION") val mock = l.isFromMockProvider
                                if (mock) return@collect
                                fixes += GnssCalFix(SystemClock.elapsedRealtime(), l.accuracy, if (l.hasSpeed()) l.speed else 0f, l.latitude, l.longitude)
                            }
                            else -> Unit
                        }
                    }
                }
                when (val result = GnssCalibration.grade(GnssCalAttempt(epochs, fixes, start, SystemClock.elapsedRealtime()), System.currentTimeMillis())) {
                    is GnssCalOutcome.Saved -> {
                        val temp = File(file.parentFile, file.name + ".tmp")
                        temp.writeText(json.encodeToString(result.profile))
                        check(temp.renameTo(file)) { "Could not save calibration" }
                        profile = result.profile
                        _state.value = GnssHealth(profile = profile, status = result.profile.summary())
                    }
                    is GnssCalOutcome.Rejected -> _state.update { it.copy(calibration = GnssCalUi(rejection = result.reason), status = result.reason) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(calibration = GnssCalUi(rejection = "Could not save calibration")) } }
            finally { countdown.cancel(); _state.update { it.copy(calibration = it.calibration.copy(running = false)) } }
            sync(active, enabled)
        }
    }

    @Synchronized fun stopCalibration(reason: String = "Calibration cancelled") {
        job?.cancel(); job = null; runningMonitor = false
        _state.update { it.copy(calibration = GnssCalUi(rejection = reason), status = reason) }
    }

    @Synchronized fun clearProfile() {
        stopCalibration("Calibration cleared"); profile = null; file.delete()
        _state.value = GnssHealth()
    }
}
