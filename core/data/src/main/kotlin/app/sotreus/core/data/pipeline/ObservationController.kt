package app.sotreus.core.data.pipeline

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.model.SotreusSettings
import app.sotreus.sensing.RadioHub
import app.sotreus.sensing.RadioPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides when the radios run: while the app is visible, or while a Journey/Sit is active (then
 * under the foreground [app.sotreus.core.data.service.ObservationService]). Never in the background
 * otherwise. Scanning needs the radio permissions unless simulated radios are on.
 */
@Singleton
class ObservationController @Inject constructor(
    private val hub: RadioHub,
    private val pipeline: ObservationPipeline,
    private val settingsRepository: SettingsRepository,
    private val permissions: RadioPermissions,
    private val sessions: SessionDao,
    private val places: PlaceDao,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val foreground = MutableStateFlow(false)

    /** Proximity check asks for the fastest BLE scan mode while it is open. */
    private val boost = MutableStateFlow(false)

    fun setBoost(on: Boolean) {
        boost.value = on
    }
    private val _observing = MutableStateFlow(false)
    val observing: StateFlow<Boolean> = _observing.asStateFlow()
    private var settings = SotreusSettings()
    private var started = false

    /** Call once from Application.onCreate (main thread). */
    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) permissions.refresh()
                foreground.value = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            },
        )
        scope.launch { hub.observations.collect { pipeline.ingest(it, hub.status.value.simulated) } }
        scope.launch {
            settingsRepository.settings.map { it.currentPlaceId }.distinctUntilChanged().collect { id ->
                pipeline.setPlace(id?.let { places.get(it) }, System.currentTimeMillis())
            }
        }
        scope.launch {
            var wasSimulated = false
            combine(settingsRepository.settings, foreground, sessions.observeActive(), permissions.access, boost) { s, fg, session, access, boosted ->
                settings = s
                if (wasSimulated && !s.simulatedRadios) pipeline.clearSimulated()
                wasSimulated = s.simulatedRadios
                val wanted = (fg || session != null) && (s.simulatedRadios || access.canScan)
                Triple(wanted, s.simulatedRadios, if (boosted) app.sotreus.core.model.ScanIntensity.PERFORMANCE else s.scanIntensity)
            }.distinctUntilChanged().collect { (wanted, simulated, intensity) ->
                val now = System.currentTimeMillis()
                if (wanted) {
                    if (!_observing.value) pipeline.onStart(now)
                    hub.start(intensity, simulated)
                } else if (_observing.value) {
                    hub.stop()
                    pipeline.onStop(now)
                }
                _observing.value = wanted
            }
        }
        scope.launch {
            var n = 0
            while (isActive) {
                if (foreground.value && n % 5 == 0) withContext(Dispatchers.Main) { permissions.refresh() }
                pipeline.tick(System.currentTimeMillis(), hub.status.value, permissions.access.value, settings, _observing.value)
                n++
                delay(1_000)
            }
        }
    }
}
