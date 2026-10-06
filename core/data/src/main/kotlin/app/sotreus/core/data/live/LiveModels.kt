package app.sotreus.core.data.live

import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.model.PresenceState
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.UserEntityState
import app.sotreus.sensing.RadioAccess
import app.sotreus.sensing.RadioStatus

/** One radio as Now sees it this second. Signal strength is proximity only. */
data class LiveRadio(
    val entityId: String,
    val kind: RadioKind,
    /** BLE MAC or Wi-Fi BSSID as received. */
    val address: String = "",
    val advertisedName: String?,
    val userName: String?,
    val family: DeviceFamily?,
    val signatureName: String?,
    val guess: String?,
    val userState: UserEntityState,
    val presence: PresenceState,
    /** Distinct places (other than the current one) where it was encountered. */
    val otherPlaces: Int,
    val avgRssi30: Double,
    val lastRssi: Int,
    val lastHeardMs: Long,
    val stale: Boolean,
    val security: String?,
    val frequencyMhz: Int?,
    val randomAddress: Boolean,
    val needsAttention: Boolean,
    val simulated: Boolean,
)

data class AttentionSummary(val eventId: Long, val count: Int, val headlineName: String)

data class LiveSnapshot(
    val atMs: Long = 0,
    val observing: Boolean = false,
    val simulated: Boolean = false,
    val radios: List<LiveRadio> = emptyList(),
    val status: RadioStatus = RadioStatus(),
    val access: RadioAccess? = null,
    val placeId: Long? = null,
    val placeName: String? = null,
    /** Completed visits behind the current place's baseline. 0 = still learning. */
    val baselineVisits: Int = 0,
    val activeSessionId: Long? = null,
) {
    val live: List<LiveRadio> get() = radios.filterNot { it.stale }
}
