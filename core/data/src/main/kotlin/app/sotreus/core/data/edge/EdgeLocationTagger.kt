package app.sotreus.core.data.edge

import app.sotreus.core.data.field.GnssHealthRepository
import app.sotreus.core.data.pipeline.EdgeLiveLocator
import app.sotreus.core.data.pipeline.TaggedLocation
import app.sotreus.core.database.dao.EdgeDao
import app.sotreus.core.database.entity.EdgeDeviceEntity
import app.sotreus.core.database.entity.LocationFixEntity
import app.sotreus.core.model.EdgeDeployment
import app.sotreus.core.model.LocationSource
import app.sotreus.edge.location.EdgeLocationSource
import app.sotreus.edge.location.FixQuality
import app.sotreus.edge.location.GeoFix
import app.sotreus.edge.location.LocationResolver
import app.sotreus.edge.location.QualityLimits
import app.sotreus.edge.location.SightingLocation
import app.sotreus.edge.location.SightingLocator
import app.sotreus.edge.location.TogetherTracker
import app.sotreus.edge.protocol.DeploymentMode
import app.sotreus.edge.protocol.EdgeCapabilities
import app.sotreus.edge.protocol.EdgePlacement
import app.sotreus.edge.protocol.EdgePositionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import app.sotreus.sensing.LocationSource as PhoneFixSource

/**
 * Gives Edge sightings their location (HANDOFF_V2_EDGE.md §4): the Edge's own GNSS, a stationary
 * Edge's set position, or this phone's fixes while phone and Edge are together. Only fixes that
 * pass [FixQuality] are kept; nothing is filled in from a last known fix or interpolated.
 */
@Singleton
class EdgeLocationTagger @Inject constructor(
    private val dao: EdgeDao,
    private val gnss: GnssHealthRepository,
) : EdgeLiveLocator {
    @Volatile var limits: QualityLimits = QualityLimits()
        private set
    @Volatile private var together = TogetherTracker(limits)
    @Volatile private var device: EdgeDeviceEntity? = null
    @Volatile private var caps = EdgeCapabilities()
    private val phoneFixes = ArrayDeque<GeoFix>()
    private val edgeFixes = ArrayDeque<GeoFix>()

    private val _lastFix = MutableStateFlow<GeoFix?>(null)

    /** The last phone fix that passed the checks (for the Edge screen's "Last fix" row). */
    val lastFix: StateFlow<GeoFix?> = _lastFix.asStateFlow()

    private val _wantsPhone = MutableStateFlow(false)

    /** True while this phone's location is in use for Edge data; location updates run only then. */
    val wantsPhoneLocation: StateFlow<Boolean> = _wantsPhone.asStateFlow()

    fun bind(device: EdgeDeviceEntity?, caps: EdgeCapabilities) {
        this.device = device
        this.caps = caps
        _wantsPhone.value = LocationResolver.needsPhoneLocation(source())
    }

    fun configure(maxAccuracyM: Int, togetherMinDbm: Int) {
        val next = limits.copy(maxAccuracyM = maxAccuracyM.toFloat(), togetherMinDbm = togetherMinDbm).sane()
        if (next != limits) {
            limits = next
            together = TogetherTracker(next)
        }
    }

    fun source(now: Long = System.currentTimeMillis()): EdgeLocationSource {
        val d = device ?: return EdgeLocationSource.NONE
        return LocationResolver.resolve(
            caps = caps,
            placement = placementOf(d),
            edgeHasFix = synchronized(edgeFixes) { edgeFixes.lastOrNull()?.let { now - it.atMs <= EDGE_FIX_FRESH_MS } == true },
            placementHeld = d.placementHeldSinceMs != null,
            phoneTagging = d.phoneTagging,
        )
    }

    fun linkRssi(atMs: Long, dbm: Int) = together.linkRssi(atMs, dbm)

    fun linkDown(atMs: Long) = together.linkDown(atMs)

    fun isTogether(atMs: Long): Boolean = together.isTogether(atMs)

    /** A phone fix while tagging. Rejected fixes are dropped, not stored. */
    suspend fun onPhoneFix(fix: PhoneFixSource.Fix) {
        if (source(fix.atMs) != EdgeLocationSource.PHONE_WITH_EDGE) return
        val g = fix.toGeo()
        if (!FixQuality.acceptable(g, together.isTogether(g.atMs), gnss.state.value.notice != null, limits)) return
        synchronized(phoneFixes) {
            phoneFixes.addLast(g)
            while (phoneFixes.isNotEmpty() && g.atMs - phoneFixes.first().atMs > MEMORY_MS) phoneFixes.removeFirst()
        }
        _lastFix.value = g
        dao.insertFix(g.toEntity())
    }

    /** A position record from an Edge with GNSS (record type 6). */
    suspend fun onEdgePosition(p: EdgePositionRecord, atMs: Long) {
        val acc = p.horizontalAccuracyM ?: return
        if (!p.hasFix) return
        val g = GeoFix(atMs, p.lat, p.lon, acc, altitudeM = p.altitudeM, verticalAccuracyM = p.verticalAccuracyM, speedMps = p.speedMps, provider = EDGE_PROVIDER, satellitesUsed = p.satellitesUsed)
        synchronized(edgeFixes) {
            edgeFixes.addLast(g)
            while (edgeFixes.isNotEmpty() && atMs - edgeFixes.first().atMs > MEMORY_MS) edgeFixes.removeFirst()
        }
        dao.insertFix(g.toEntity())
    }

    /** Live samples (pipeline hook): the location at [atMs], or null when it is not known. */
    override fun locate(atMs: Long): TaggedLocation? {
        val d = device ?: return null
        return when (val src = source(atMs)) {
            EdgeLocationSource.EDGE_FIXED -> fixedFor(d, atMs)?.toTagged(atMs, LocationSource.EDGE_FIXED)
            EdgeLocationSource.PHONE_WITH_EDGE -> SightingLocator.live(atMs, synchronized(phoneFixes) { phoneFixes.toList() }, src, limits).toTagged(atMs, LocationSource.PHONE_WITH_EDGE)
            EdgeLocationSource.EDGE_GNSS -> SightingLocator.live(atMs, synchronized(edgeFixes) { edgeFixes.toList() }, src, limits).toTagged(atMs, LocationSource.EDGE_GNSS)
            EdgeLocationSource.NONE -> null
        }
    }

    /**
     * Location for a synced window `[firstMs, lastMs]`. Uses the fixes stored while tagging then
     * (their presence means the phone was with the Edge and tagging was on), or the fixed position
     * for windows after it was set and before any moved check.
     */
    suspend fun window(device: EdgeDeviceEntity, firstMs: Long, lastMs: Long): SightingLocation {
        fixedFor(device, firstMs)?.let { return it }
        val stored = dao.fixes(firstMs - limits.liveJoinMs, lastMs + limits.liveJoinMs).map { it.toGeo() }
        val edge = stored.filter { it.provider == EDGE_PROVIDER }
        if (caps.gnss && edge.isNotEmpty()) {
            val w = SightingLocator.window(firstMs, lastMs, edge, EdgeLocationSource.EDGE_GNSS, limits)
            if (w !is SightingLocation.NotKnown) return w
        }
        return SightingLocator.window(firstMs, lastMs, stored.filter { it.provider != EDGE_PROVIDER }, EdgeLocationSource.PHONE_WITH_EDGE, limits)
    }

    private fun fixedFor(d: EdgeDeviceEntity, atMs: Long): SightingLocation? {
        if (d.deployment != EdgeDeployment.STATIONARY) return null
        val lat = d.placeLat ?: return null
        val lon = d.placeLon ?: return null
        val acc = d.placeAccuracyM ?: return null
        val setAt = d.placeSetMs
        val heldSince = d.placementHeldSinceMs
        if (setAt != null && atMs < setAt) return null
        if (heldSince != null && atMs >= heldSince) return null
        return SightingLocator.fixed(lat, lon, acc)
    }

    companion object {
        const val EDGE_PROVIDER = "edge"
        private const val MEMORY_MS = 10 * 60_000L
        private const val EDGE_FIX_FRESH_MS = 120_000L

        fun placementOf(d: EdgeDeviceEntity): EdgePlacement = EdgePlacement(
            if (d.deployment == EdgeDeployment.STATIONARY) DeploymentMode.STATIONARY else DeploymentMode.TRAVEL,
            d.placeLat, d.placeLon, d.placeAccuracyM, d.placeSetMs, d.placeSource,
        )
    }
}

internal fun PhoneFixSource.Fix.toGeo() = GeoFix(
    atMs = atMs, lat = lat, lon = lon, accuracyM = accuracyM, altitudeM = altitudeM, verticalAccuracyM = verticalAccuracyM,
    mslAltitudeM = mslAltitudeM, speedMps = speedMps, speedAccuracyMps = speedAccuracyMps, provider = provider, satellitesUsed = satellitesUsed, mock = mock,
)

internal fun GeoFix.toEntity() = LocationFixEntity(
    atMs = atMs, lat = lat, lon = lon, accuracyM = accuracyM ?: Float.NaN, altitudeM = altitudeM, verticalAccuracyM = verticalAccuracyM,
    mslAltitudeM = mslAltitudeM, speedMps = speedMps, speedAccuracyMps = speedAccuracyMps, provider = provider,
    satellitesUsed = satellitesUsed, satellitesInView = satellitesInView, pressureHpa = pressureHpa,
)

internal fun LocationFixEntity.toGeo() = GeoFix(
    atMs = atMs, lat = lat, lon = lon, accuracyM = accuracyM, altitudeM = altitudeM, verticalAccuracyM = verticalAccuracyM,
    mslAltitudeM = mslAltitudeM, speedMps = speedMps, speedAccuracyMps = speedAccuracyMps, provider = provider,
    satellitesUsed = satellitesUsed, satellitesInView = satellitesInView, pressureHpa = pressureHpa,
)

internal fun EdgeLocationSource.toModel(): LocationSource? = when (this) {
    EdgeLocationSource.EDGE_GNSS -> LocationSource.EDGE_GNSS
    EdgeLocationSource.EDGE_FIXED -> LocationSource.EDGE_FIXED
    EdgeLocationSource.PHONE_WITH_EDGE -> LocationSource.PHONE_WITH_EDGE
    EdgeLocationSource.NONE -> null
}

/** A located result as stored on observations, encounters and entities; null when not known. */
internal fun SightingLocation.toTagged(atMs: Long, fallback: LocationSource): TaggedLocation? {
    val src = source.toModel() ?: fallback
    return when (this) {
        is SightingLocation.Point -> TaggedLocation(atMs, lat, lon, radiusM.toFloat(), src, altitudeM)
        is SightingLocation.AlongRoute -> TaggedLocation(atMs, startLat, startLon, accuracyM.toFloat(), src, endLat = endLat, endLon = endLon)
        is SightingLocation.Partial -> TaggedLocation(atMs, lat, lon, radiusM.toFloat(), src)
        is SightingLocation.NotKnown -> null
    }
}
