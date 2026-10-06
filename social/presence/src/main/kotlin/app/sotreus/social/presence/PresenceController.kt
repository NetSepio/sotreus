package app.sotreus.social.presence

import android.content.Context
import app.sotreus.core.crypto.Presence
import app.sotreus.core.crypto.hexToBytes
import app.sotreus.core.data.di.ApplicationScope
import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.repository.FriendRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.entity.FriendEntity
import app.sotreus.intelligence.fieldwatch.RadioKind
import app.sotreus.sensing.PermissionGroup
import app.sotreus.sensing.RadioHub
import app.sotreus.sensing.RadioPermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts/stops presence advertising with the user's switch, and resolves friends' tokens from the
 * radios already being observed. Resolution is local: tokens are only compared with the pairwise
 * secrets on this phone.
 */
@Singleton
class PresenceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val friends: FriendRepository,
    private val permissions: RadioPermissions,
    private val hub: RadioHub,
    private val pipeline: ObservationPipeline,
    @ApplicationScope private val scope: CoroutineScope,
) {
    @Volatile private var secrets: List<Pair<FriendEntity, ByteArray>> = emptyList()
    private val lastMarked = HashMap<Long, Long>()

    fun start() {
        scope.launch {
            combine(settings.settings, friends.all, permissions.access) { s, f, a ->
                s.nearbyPresence && f.isNotEmpty() && PermissionGroup.ADVERTISE in a.granted
            }.distinctUntilChanged().collect { on -> if (on) PresenceService.start(context) else PresenceService.stop(context) }
        }
        scope.launch { friends.all.collect { secrets = friends.pairwiseSecrets() } }
        scope.launch {
            hub.observations.collect { obs ->
                if (obs.kind != RadioKind.BLE || obs.manufacturerId != PresenceFrame.COMPANY_ID) return@collect
                val data = runCatching { obs.manufacturerDataHex.hexToBytes() }.getOrNull() ?: return@collect
                val token = PresenceFrame.decode(obs.manufacturerId, data) ?: return@collect
                val now = obs.at
                secrets.firstOrNull { (_, secret) -> Presence.matches(secret, token, now / 1000) }?.let { (friend, _) ->
                    if (now - (lastMarked[friend.id] ?: 0) > MARK_SPACING_MS) {
                        lastMarked[friend.id] = now
                        friends.markNearby(friend.id, now, pipeline.snapshot.value.placeName)
                    }
                }
            }
        }
    }

    companion object {
        private const val MARK_SPACING_MS = 60_000L
    }
}
