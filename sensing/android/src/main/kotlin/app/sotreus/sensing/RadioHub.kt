package app.sotreus.sensing

import android.content.Context
import app.sotreus.core.model.ScanIntensity
import app.sotreus.intelligence.fieldwatch.Observation
import app.sotreus.intelligence.fieldwatch.ScanIntensity as FwIntensity
import app.sotreus.sensing.radio.BleRadio
import app.sotreus.sensing.radio.WifiRadio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/** Live scanner health for Now, Sensors and Diagnostics. Times are wall-clock ms; 0 = never. */
data class RadioStatus(
    val running: Boolean = false,
    val simulated: Boolean = false,
    val intensity: ScanIntensity = ScanIntensity.BALANCED,
    val bleRunning: Boolean = false,
    val bleLastResultMs: Long = 0,
    val bleResultsPerMinute: Int = 0,
    val bleRetrying: Boolean = false,
    /** Last scan whose results Android reported as new. */
    val wifiLastFreshMs: Long = 0,
    val wifiScanRequested: Boolean = false,
    val wifiNextScanMs: Long = 0,
    /** The last startScan() was refused (Android's 4-per-2-minutes limit or OS busy). */
    val wifiLastRequestRejected: Boolean = false,
    val wifiScanIntervalMs: Long = 30_000,
)

/**
 * Runs the phone's BLE and Wi-Fi radios (Fieldwatch BleRadio / WifiRadio) or the simulated source,
 * and publishes every advertisement / AP result as a Fieldwatch [Observation].
 */
@Singleton
class RadioHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val simulator: SimulatedRadios,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _observations = MutableSharedFlow<Observation>(extraBufferCapacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val observations: SharedFlow<Observation> = _observations.asSharedFlow()

    private val _status = MutableStateFlow(RadioStatus())
    val status: StateFlow<RadioStatus> = _status.asStateFlow()

    private val bleTimes = ArrayDeque<Long>()
    private var loop: Job? = null
    private var ble: BleRadio? = null
    private var wifi: WifiRadio? = null

    @Synchronized
    fun start(intensity: ScanIntensity, simulated: Boolean) {
        val current = _status.value
        if (current.running && current.simulated == simulated && current.intensity == intensity) return
        stop()
        val interval = if (intensity == ScanIntensity.SAVER) 60_000L else 30_000L
        _status.value = RadioStatus(running = true, simulated = simulated, intensity = intensity, wifiScanIntervalMs = interval)
        loop = if (simulated) {
            scope.launch { simulator.run(::emitBle, ::emitWifiScan) }
        } else {
            startRadios(intensity, interval)
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        ble?.stop()
        wifi?.stop()
        ble = null
        wifi = null
        _status.update { it.copy(running = false, bleRunning = false, wifiScanRequested = false) }
    }

    private fun startRadios(intensity: ScanIntensity, interval: Long): Job {
        val fw = FwIntensity.valueOf(intensity.name)
        val b = BleRadio(context, onObservation = ::emitBle, onError = { _status.update { it.copy(bleRetrying = true) } })
        val w = WifiRadio(
            context,
            onObservation = { obs -> _observations.tryEmit(obs) },
            onScanFinished = { _, fresh ->
                if (fresh) _status.update { it.copy(wifiLastFreshMs = System.currentTimeMillis(), wifiScanRequested = false) }
            },
        )
        ble = b
        wifi = w
        b.start(fw)
        w.start()
        return scope.launch {
            var nextWifi = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (b.needsRestart()) {
                    delay(b.restartBackoffMs())
                    b.start(fw)
                }
                if (now >= nextWifi) {
                    val ok = w.requestScan(minIntervalMs = interval)
                    nextWifi = now + if (ok) interval else 10_000L
                    _status.update {
                        it.copy(
                            wifiScanRequested = ok || it.wifiScanRequested,
                            wifiLastRequestRejected = !ok,
                            wifiNextScanMs = nextWifi,
                        )
                    }
                }
                _status.update { it.copy(bleRunning = b.isRunning(), bleRetrying = b.statusHint().isNotEmpty() && !b.isRunning()) }
                delay(1_000)
            }
        }
    }

    private fun emitBle(obs: Observation) {
        _observations.tryEmit(obs)
        val now = obs.at
        val perMinute = synchronized(bleTimes) {
            bleTimes.addLast(now)
            while (bleTimes.isNotEmpty() && now - bleTimes.first() > 60_000) bleTimes.removeFirst()
            bleTimes.size
        }
        _status.update { it.copy(bleLastResultMs = now, bleResultsPerMinute = perMinute, bleRunning = true, bleRetrying = false) }
    }

    private fun emitWifiScan(results: List<Observation>, fresh: Boolean, rejected: Boolean) {
        results.forEach { _observations.tryEmit(it) }
        val now = System.currentTimeMillis()
        _status.update {
            it.copy(
                wifiLastFreshMs = if (fresh) now else it.wifiLastFreshMs,
                wifiLastRequestRejected = rejected,
                wifiScanRequested = !fresh,
                wifiNextScanMs = now + it.wifiScanIntervalMs,
            )
        }
    }
}
