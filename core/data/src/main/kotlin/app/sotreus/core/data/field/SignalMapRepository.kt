package app.sotreus.core.data.field

import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.intelligence.fieldwatch.GpsSample
import app.sotreus.intelligence.fieldwatch.HuntHeat
import app.sotreus.intelligence.fieldwatch.HuntHeatField
import app.sotreus.intelligence.fieldwatch.HuntTrack
import app.sotreus.intelligence.fieldwatch.rasterHuntHeat
import app.sotreus.sensing.LocationSource
import app.sotreus.sensing.WalkSensors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signal map for the proximity check: where this phone walked, colored by how loud one radio was
 * at each spot (Fieldwatch Hunt). It never places the other radio; the loudest patch is only where
 * this phone heard it loudest. Held in memory for one radio at a time; nothing is written to disk.
 */
data class SignalMap(
    val entityId: String? = null,
    val marks: List<GpsSample> = emptyList(),
    /** The dot. On the plain picture (no GPS yet) these are local coordinates, not a place. */
    val hereLat: Double? = null,
    val hereLon: Double? = null,
    val onGlobe: Boolean = false,
    val uncertaintyM: Double = HuntTrack.UNCERTAINTY_FLOOR_M,
    val holdFlat: Boolean = false,
    val field: HuntHeatField? = null,
    val sensors: Boolean = true,
)

@Singleton
class SignalMapRepository @Inject constructor(
    private val pipeline: ObservationPipeline,
    private val location: PhoneLocation,
    private val walk: WalkSensors,
) {
    private val track = HuntTrack()
    private val _state = MutableStateFlow(SignalMap())
    val state: StateFlow<SignalMap> = _state.asStateFlow()
    private var lastNoted = 0L
    private var drawnGen = -1

    private sealed interface Input {
        data class Walk(val e: WalkSensors.Event) : Input
        data class Fix(val f: LocationSource.Fix) : Input
        data object Tick : Input
    }

    /** Follows the walk for [entityId] until cancelled. The same radio continues its map; another starts over. */
    suspend fun follow(entityId: String) {
        if (_state.value.entityId != entityId) reset(entityId)
        val ticks: Flow<Input> = flow { while (currentCoroutineContext().isActive) { emit(Input.Tick); delay(TICK_MS) } }
        merge(walk.events().map { Input.Walk(it) }, location.updates(fast = true).map { Input.Fix(it) }, ticks)
            .collect { input ->
                when (input) {
                    is Input.Walk -> when (val e = input.e) {
                        is WalkSensors.Event.Step -> track.step(e.atMs)
                        is WalkSensors.Event.Heading -> track.heading(e.compassDeg, e.gameDeg, e.magHealthy, e.atMs)
                        is WalkSensors.Event.Tilt -> track.tilt(e.degFromFlat, e.atMs)
                    }
                    // Network fixes and mock fixes stay out; a fix only places or nudges the walk.
                    is Input.Fix -> input.f.takeIf { it.gps && !it.mock }?.let { f ->
                        track.gps(f.lat, f.lon, f.accuracyM ?: Float.MAX_VALUE, f.atMs, fresh = System.currentTimeMillis() - f.atMs <= FRESH_MS)
                    }
                    Input.Tick -> publish(entityId)
                }
            }
    }

    /** Clears the map. The next [follow] starts a new walk. */
    fun reset(entityId: String? = _state.value.entityId) {
        track.clear()
        lastNoted = System.currentTimeMillis()
        drawnGen = -1
        _state.value = SignalMap(entityId = entityId, sensors = walk.available())
    }

    private suspend fun publish(entityId: String) {
        val now = System.currentTimeMillis()
        pipeline.trail(entityId).filter { (at, _) -> at > lastNoted }.forEach { (at, rssi) ->
            if (HuntHeat.paintable(rssi, at, now)) track.note(rssi, at)
            lastNoted = maxOf(lastNoted, at)
        }
        val (lat, lon) = track.display()
        val gen = track.hearGen() + track.attachGen() * 1_000_003
        val marks = if (gen != drawnGen) track.marks() else _state.value.marks
        val field = if (gen != drawnGen) withContext(Dispatchers.Default) { rasterHuntHeat(marks) } else _state.value.field
        drawnGen = gen
        _state.value = SignalMap(entityId, marks, lat, lon, track.onGlobe(), track.uncertaintyM(), track.holdingFlat(), field, walk.available())
    }

    private companion object {
        const val TICK_MS = 250L
        const val FRESH_MS = 10_000L
    }
}
