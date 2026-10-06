package app.sotreus.feature.now

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sotreus.core.data.live.LiveRadio
import app.sotreus.core.data.live.LiveSnapshot
import app.sotreus.core.data.repository.AttentionListItem
import app.sotreus.core.data.repository.AttentionRepository
import app.sotreus.core.data.repository.PlaceRepository
import app.sotreus.core.data.repository.PlaceSummary
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.model.NowView
import app.sotreus.core.model.PresenceState
import app.sotreus.core.model.SotreusSettings
import app.sotreus.core.model.UserEntityState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class NowFilter { ALL, NEW, TAGGED, FAMILIAR, BY_CLASS }

enum class NowSort { STRONGEST, NEWEST, NAME, SIGNATURES }

/** Now (screens 03/04): one route, Bands and List share this state. */
data class NowUiState(
    val snapshot: LiveSnapshot = LiveSnapshot(),
    val settings: SotreusSettings = SotreusSettings(),
    val view: NowView = NowView.BANDS,
    val filter: NowFilter = NowFilter.ALL,
    val sort: NowSort = NowSort.STRONGEST,
    val openAttention: List<AttentionListItem> = emptyList(),
    val places: List<PlaceSummary> = emptyList(),
) {
    val live: List<LiveRadio> get() = snapshot.radios.filterNot { it.stale }
    val families: Int get() = live.mapNotNull { it.family }.distinct().size
    val familiar: Int get() = live.count { it.presence == PresenceState.FAMILIAR }
    val changed: Int get() = live.count { it.presence == PresenceState.NEW || it.presence == PresenceState.SEEN_ELSEWHERE }
    val seenElsewhere: Int get() = live.count { it.presence == PresenceState.SEEN_ELSEWHERE }
    val tagged: Int get() = live.count { it.userState == UserEntityState.TAGGED || it.userState == UserEntityState.WATCH }

    /** No baseline yet for a saved place (first visit). Unsaved places compare with history. */
    val learning: Boolean get() = snapshot.placeId != null && snapshot.baselineVisits == 0

    val needsPermissions: Boolean get() = !settings.simulatedRadios && snapshot.access?.canScan == false

    val wifiAgeMs: Long? get() = snapshot.status.wifiLastFreshMs.takeIf { it > 0 }?.let { snapshot.atMs - it }

    /** Wi-Fi data older than one scan interval, or Android refused the last scan. */
    val wifiThrottled: Boolean
        get() = snapshot.status.running && (snapshot.status.wifiLastRequestRejected || (wifiAgeMs ?: 0) > snapshot.status.wifiScanIntervalMs)

    fun rows(): List<LiveRadio> {
        val base = when (filter) {
            NowFilter.ALL, NowFilter.BY_CLASS -> snapshot.radios
            NowFilter.NEW -> snapshot.radios.filter { it.presence == PresenceState.NEW || it.presence == PresenceState.SEEN_ELSEWHERE }
            NowFilter.TAGGED -> snapshot.radios.filter { it.userState == UserEntityState.TAGGED || it.userState == UserEntityState.WATCH }
            NowFilter.FAMILIAR -> snapshot.radios.filter { it.presence == PresenceState.FAMILIAR }
        }
        val sorted = when (sort) {
            // 3 dB buckets with a stable tie-break, so rows don't jump under a finger as RSSI jitters.
            NowSort.STRONGEST -> base.sortedWith(compareByDescending<LiveRadio> { Math.floorDiv(it.avgRssi30.toInt(), 3) }.thenBy { it.entityId })
            NowSort.NEWEST -> base.sortedWith(compareByDescending<LiveRadio> { it.lastHeardMs / 10_000 }.thenBy { it.entityId })
            NowSort.NAME -> base.sortedBy { (it.userName ?: it.advertisedName ?: "￿").lowercase() }
            NowSort.SIGNATURES -> base.sortedWith(compareByDescending<LiveRadio> { it.family != null }.thenBy { it.family?.ordinal ?: 99 }.thenBy { it.entityId })
        }
        // Stale rows sink to the bottom but stay visible.
        return sorted.sortedBy { it.stale }
    }
}

@HiltViewModel
class NowViewModel @Inject constructor(
    pipeline: ObservationPipeline,
    private val settings: SettingsRepository,
    private val placeRepository: PlaceRepository,
    attention: AttentionRepository,
    private val location: app.sotreus.sensing.LocationSource,
) : ViewModel() {
    /** Set when a place was created but no location fix was available. */
    val locationFailed = MutableStateFlow(false)

    private val filter = MutableStateFlow(NowFilter.ALL)
    private val sort = MutableStateFlow(NowSort.STRONGEST)

    val state: StateFlow<NowUiState> = combine(
        pipeline.snapshot,
        settings.settings,
        combine(filter, sort) { f, s -> f to s },
        attention.observeOpen(),
        placeRepository.observePlaces(),
    ) { snap, s, (f, so), att, places ->
        NowUiState(snapshot = snap, settings = s, view = s.nowView, filter = f, sort = so, openAttention = att, places = places)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowUiState())

    fun setView(v: NowView) = viewModelScope.launch { settings.setNowView(v) }
    fun setFilter(f: NowFilter) { filter.value = f }
    fun setSort(s: NowSort) { sort.value = s }
    fun selectPlace(id: Long?) = viewModelScope.launch { placeRepository.select(id) }
    fun createPlace(name: String, withLocation: Boolean) = viewModelScope.launch {
        val fix = if (withLocation) location.currentFix() else null
        placeRepository.createWithLocation(name, fix?.lat, fix?.lon, select = true)
        locationFailed.value = withLocation && fix == null
    }
}
