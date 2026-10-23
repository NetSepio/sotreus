package app.sotreus.core.data.field

import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.data.pipeline.PhoneLocation
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.intelligence.fieldwatch.Geo
import app.sotreus.intelligence.fieldwatch.GpsSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class LivePath(val points: List<GpsSample> = emptyList(), val sessionId: Long? = null, val enabled: Boolean = false)

/** Display-only path. Persistence remains exclusively with the session's route-saving choice. */
@Singleton
class LivePathRepository @Inject constructor(
    location: PhoneLocation, settings: SettingsRepository, sessions: SessionDao,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LivePath())
    val state = _state.asStateFlow()
    init {
        scope.launch {
            val path = mutableListOf<GpsSample>(); val apex = Geo.DriveApex()
            var bound: Long? = null; var last = 0L
            combine(location.latest, settings.settings, sessions.observeActive()) { fix, preferences, session -> Triple(fix, preferences, session) }
                .collect { (fix, preferences, session) ->
                    if (!preferences.livePath || bound != session?.id) { path.clear(); apex.clear(); last = 0; bound = session?.id }
                    if (preferences.livePath && fix != null && !fix.mock && fix.atMs > last && Geo.acceptDriveFix(fix.gps, fix.accuracyM)) {
                        if (path.lastOrNull()?.let { Geo.meters(it.lat, it.lon, fix.lat, fix.lon) / ((fix.atMs - it.at).coerceAtLeast(1) / 1000.0) > 55 } != true) {
                            Geo.extendDrivePath(path, apex, fix.lat, fix.lon, fix.atMs)
                        }
                        last = fix.atMs
                    }
                    if (session == null) {
                        val cut = System.currentTimeMillis() - 15 * 60_000
                        if (path.removeAll { it.at < cut }) apex.clear()
                    }
                    _state.value = LivePath(path.toList(), session?.id, preferences.livePath)
                }
        }
    }
}
