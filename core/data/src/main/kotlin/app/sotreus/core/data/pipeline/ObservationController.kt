package app.sotreus.core.data.pipeline

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.model.SessionKind
import app.sotreus.sensing.PermissionGroup
import app.sotreus.sensing.RadioHub
import app.sotreus.sensing.RadioPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val hub: RadioHub,
    private val pipeline: ObservationPipeline,
    private val settingsRepository: SettingsRepository,
    private val permissions: RadioPermissions,
    private val sessions: SessionDao,
    private val places: PlaceDao,
    private val location: PhoneLocation,
    private val insights: app.sotreus.core.data.field.FieldInsights,
    private val livePath: app.sotreus.core.data.field.LivePathRepository,
    private val gnss: app.sotreus.core.data.field.GnssHealthRepository,
    private val edgeDao: app.sotreus.core.database.dao.EdgeDao,
    private val edge: app.sotreus.core.data.edge.EdgeHub,
    private val edgeTagger: app.sotreus.core.data.edge.EdgeLocationTagger,
    private val autoPlaces: AutoPlaceTracker,
    private val autoPlaceSorter: AutoPlaceSorter,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** What runs the sensing: the phone's radios, the Edge, or nothing. */
    private data class Plan(val wanted: Boolean, val simulated: Boolean, val intensity: app.sotreus.core.model.ScanIntensity, val useEdge: Boolean)

    @Volatile private var edgeMode = false

    /** A travel-mode Edge with tagging on is paired; the travel service may keep it running in the background. */
    private val travelTagging = MutableStateFlow(false)
    /** The Edge is paired and chosen as the sensor. */
    private val edgeSource = MutableStateFlow(false)

    // Replayed: the receiver can fire before the controller's collector is running.
    private val appUpdated = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1)

    /** Sotreus was just updated (`MY_PACKAGE_REPLACED`): resume the Edge link if the Edge is the sensor. */
    fun onAppUpdated() {
        appUpdated.tryEmit(Unit)
    }

    /** The proximity check's radio, watched on the Edge at up to 4 Hz while it is open. */
    fun setWatch(entityId: String?) = edge.setWatch(entityId)

    private val foreground = MutableStateFlow(false)

    /** Proximity check asks for the fastest BLE scan mode while it is open. */
    private val boost = MutableStateFlow(false)

    fun setBoost(on: Boolean) {
        boost.value = on
    }
    private val _observing = MutableStateFlow(false)
    val observing: StateFlow<Boolean> = _observing.asStateFlow()
    private var settings = SotreusSettings()
    @Volatile private var sessionLocates = false
    private var started = false
    private val locationQuiet = app.sotreus.sensing.radio.LocationQuietWindow(System::currentTimeMillis)

    /**
     * The Edge's link seen through [app.sotreus.sensing.RadioStatus], so freshness and stale rules
     * work unchanged: a Wi-Fi sighting is stale only once the Edge has stopped hearing it (its
     * windows close after 60 s of quiet), never because of a phone scan schedule.
     */
    private fun edgeStatus(): app.sotreus.sensing.RadioStatus {
        val e = edge.state.value
        return app.sotreus.sensing.RadioStatus(
            running = e.connected,
            bleRunning = e.listening,
            bleLastResultMs = maxOf(e.lastLiveMs, e.lastHeardMs),
            wifiLastFreshMs = 0,
            wifiScanIntervalMs = 30_000,
        )
    }

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
        scope.launch {
            combine(foreground, sessions.observeActive(), app.sotreus.core.data.service.ObservationService.running) { visible, session, running ->
                Triple(visible, session, running)
            }.collect { (visible, session, running) ->
                if (visible && session != null && !running) {
                    runCatching { app.sotreus.core.data.service.ObservationService.start(context, session.kind, session.geotag, settingsRepository.current().journeyMovement) }
                }
            }
        }
        scope.launch { hub.freshWifiScans.collect { insights.wifiScan(it, hub.status.value.simulated) } }
        scope.launch { hub.observations.collect { pipeline.ingest(it, hub.status.value.simulated) } }
        // V2: while the Edge is the sensor, a foreground service keeps its link running when the app is
        // not visible (owner decision 2026-11-08). Started while the app is visible (Android restricts
        // starting it from the background) or when the Edge is reported back in range; stopped when the
        // Edge is no longer the sensor.
        scope.launch {
            combine(edgeDao.observeDevices(), settingsRepository.settings.map { it.sensingSource }) { devices, source ->
                val d = devices.firstOrNull()
                val onEdge = source == app.sotreus.core.model.SensingSource.EDGE && d != null
                onEdge to (onEdge && d!!.deployment == app.sotreus.core.model.EdgeDeployment.TRAVEL && d.phoneTagging)
            }.distinctUntilChanged().collect { (onEdge, travel) ->
                edgeSource.value = onEdge
                travelTagging.value = travel
                app.sotreus.core.data.service.EdgeLinkService.travelTagging.value = travel
            }
        }
        scope.launch {
            combine(foreground, edgeSource) { fg, onEdge -> fg to onEdge }
                .collect { (fg, onEdge) ->
                    val svc = app.sotreus.core.data.service.EdgeLinkService
                    when {
                        !onEdge && svc.running.value -> svc.stop(context)
                        onEdge && fg && !svc.running.value -> runCatching { svc.start(context) }
                    }
                }
        }
        scope.launch {
            appUpdated.collect {
                // Sotreus was just updated: Android stopped the Edge service, and companion presence
                // never reports an Edge that stayed in range. Start it again as if the Edge appeared.
                val onEdge = settingsRepository.current().sensingSource == app.sotreus.core.model.SensingSource.EDGE && edgeDao.current() != null
                if (onEdge && !app.sotreus.core.data.service.EdgeLinkService.running.value) {
                    runCatching { app.sotreus.core.data.service.EdgeLinkService.start(context) }
                }
            }
        }
        scope.launch {
            app.sotreus.edge.android.EdgePresence.events.collect { e ->
                if (e.present && edgeSource.value && !app.sotreus.core.data.service.EdgeLinkService.running.value) {
                    runCatching { app.sotreus.core.data.service.EdgeLinkService.start(context) }
                }
            }
        }
        // Automatic places (docs/plans/auto-places.md): a stationary Edge is filed under the place at
        // its position, made there if none contains it; its sightings follow that place.
        scope.launch {
            combine(
                edgeDao.observeDevices(),
                settingsRepository.settings.map { (it.autoPlaces == true) to (it.sensingSource == app.sotreus.core.model.SensingSource.EDGE) }.distinctUntilChanged(),
            ) { devices, flags -> Triple(devices.firstOrNull(), flags.first, flags.second) }
                .collect { (d, on, edgeSensing) ->
                    val stationary = d?.takeIf { it.deployment == app.sotreus.core.model.EdgeDeployment.STATIONARY }
                    val lat = stationary?.placeLat
                    val lon = stationary?.placeLon
                    if (on && stationary != null && lat != null && lon != null) {
                        val linked = stationary.linkedPlaceId?.let { places.get(it) }
                        val holds = linked?.let { p ->
                            val pl = p.lat
                            val po = p.lon
                            pl != null && po != null && app.sotreus.intelligence.PlaceGeofence.distanceM(pl, po, lat, lon) <= (p.radiusM ?: app.sotreus.intelligence.PlaceGeofence.DEFAULT_RADIUS_M)
                        } == true
                        if (!holds) {
                            val id = runCatching { autoPlaces.placeAt(lat, lon) }.getOrNull()
                            if (id != null && id != stationary.linkedPlaceId) edgeDao.upsertDevice(stationary.copy(linkedPlaceId = id))
                        }
                    }
                    pipeline.edgeFixedPlaceId = stationary?.linkedPlaceId
                    // With a stationary Edge as the sensor, Now's place is the Edge's place; a hand pick still wins.
                    val pin = stationary?.linkedPlaceId?.takeIf { on && edgeSensing }
                    autoPlaces.pinnedPlaceId = pin
                    if (pin != null) {
                        val selection = settingsRepository.placeSelection()
                        if (selection.auto && selection.placeId != pin) settingsRepository.selectPlaceByLocation(pin, System.currentTimeMillis(), selection)
                    }
                }
        }
        // Past located rows: sorted once when automatic places come on, then every 10 minutes.
        scope.launch {
            settingsRepository.settings.map { it.autoPlaces == true }.distinctUntilChanged().collectLatest { on ->
                while (on) {
                    runCatching { autoPlaceSorter.sort() }
                    kotlinx.coroutines.delay(AUTO_PLACE_SORT_MS)
                }
            }
        }
        // V2: the Edge's live sightings go through the same pipeline as the phone's radios.
        scope.launch {
            edge.observations.collect { obs ->
                if (app.sotreus.core.data.edge.EdgeHub.isProbe(obs)) pipeline.markWifiClient(app.sotreus.intelligence.Fingerprint.key(app.sotreus.core.model.RadioKind.WIFI, obs.mac))
                pipeline.ingest(obs, false)
            }
        }
        scope.launch {
            settingsRepository.settings.map { it.currentPlaceId }.distinctUntilChanged().collect { id ->
                pipeline.setPlace(id?.let { places.get(it) }, System.currentTimeMillis())
            }
        }
        scope.launch {
            var wasSimulated = false
            combine(settingsRepository.settings, combine(foreground, app.sotreus.core.data.service.ObservationService.running, app.sotreus.core.data.service.EdgeLinkService.running, travelTagging) { fg, service, _, _ -> fg to service }, sessions.observeActive(), permissions.access, boost) { s, visibility, session, access, boosted ->
                val (fg, service) = visibility
                settings = s
                sessionLocates = session != null && (session.geotag || (session.kind == SessionKind.JOURNEY && s.journeyMovement))
                if (wasSimulated && !s.simulatedRadios) pipeline.clearSimulated()
                wasSimulated = s.simulatedRadios
                locationQuiet.observe(access.locationOn)
                edgeTagger.configure(s.edgeMaxAccuracyM, s.edgeTogetherMinDbm)
                // V2: with an Edge as the sensor, this phone's BLE and Wi-Fi scanning stays off; the link needs
                // only Nearby devices (Bluetooth connect) and Bluetooth on, not location.
                val useEdge = s.sensingSource == app.sotreus.core.model.SensingSource.EDGE && !s.simulatedRadios
                val canSense = when {
                    s.simulatedRadios -> true
                    useEdge -> access.canConnectEdge && access.bluetoothOn
                    else -> access.canScan && access.locationOn
                }
                val wanted = (fg || (session != null && service) || (useEdge && app.sotreus.core.data.service.EdgeLinkService.running.value)) && canSense
                Plan(wanted, s.simulatedRadios, if (boosted) app.sotreus.core.model.ScanIntensity.PERFORMANCE else s.scanIntensity, useEdge)
            }.distinctUntilChanged().collectLatest { (wanted, simulated, intensity, useEdge) ->
                // Location first: the place must reflect where the phone is before anything is tagged.
                // Cancelled (by collectLatest) if the app leaves the foreground while waiting for a fix.
                if (wanted && !_observing.value) {
                    if (!useEdge) delay(locationQuiet.radioHoldMs())
                    location.settleBeforeScan()
                }
                withContext(NonCancellable) {
                    val now = System.currentTimeMillis()
                    if (wanted) {
                        if (!_observing.value) {
                            pipeline.setPlace(settingsRepository.current().currentPlaceId?.let { places.get(it) }, now)
                            pipeline.onStart(now)
                        }
                        if (useEdge) {
                            hub.stop()
                            pipeline.edgeLocator = edgeTagger
                            edge.start()
                        } else {
                            edge.stop()
                            pipeline.edgeLocator = null
                            hub.start(intensity, simulated)
                        }
                        edgeMode = useEdge
                    } else if (_observing.value) {
                        hub.stop()
                        edge.stop()
                        pipeline.edgeLocator = null
                        pipeline.onStop(now)
                    }
                    _observing.value = wanted
                }
            }
        }
        // Foreground location while observing, so the place follows the phone between saved places.
        scope.launch {
            combine(
                foreground,
                _observing,
                settingsRepository.settings,
                permissions.access,
                sessions.observeActive(),
            ) { fg, observing, s, access, session ->
                val serviceLocates = session != null && (session.geotag || (session.kind == SessionKind.JOURNEY && s.journeyMovement))
                val wanted = fg && observing && !serviceLocates && (s.placeByLocation || s.plusCodeTags || s.trackerStopHistory || s.livePath) &&
                    PermissionGroup.LOCATION in access.granted && access.locationOn
                wanted to s.livePath
            }.distinctUntilChanged().collectLatest { (on, fast) ->
                if (on) {
                    if (locationQuiet.locationHeld()) delay(500)
                    location.updates(fast).collect { location.offer(it) }
                }
            }
        }
        // A place's location or radius changed, or picking by location was turned on: check again.
        scope.launch {
            combine(places.observeAll(), settingsRepository.settings.map { it.placeByLocation }.distinctUntilChanged()) { _, _ -> }
                .collect { location.recheck() }
        }
        scope.launch {
            var n = 0
            while (isActive) {
                if (foreground.value && n % 5 == 0) withContext(Dispatchers.Main) { permissions.refresh() }
                gnss.sync((foreground.value || sessionLocates) && _observing.value && permissions.access.value.locationOn && !settings.simulatedRadios, settings.gnssMonitoring, settings.gnssSpoofChecks, settings.gnssSensitivity)
                pipeline.tick(System.currentTimeMillis(), if (edgeMode) edgeStatus() else hub.status.value, permissions.access.value, settings, _observing.value)
                n++
                delay(1_000)
            }
        }
    }
}

private const val AUTO_PLACE_SORT_MS = 10 * 60_000L
