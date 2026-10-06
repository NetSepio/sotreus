package app.sotreus.core.model

enum class RetentionPolicy { KEEP_ALL, KEEP_30_DAYS, KEEP_TAGGED_EXPIRE_REST }

enum class GeotagMode { ASK_EACH_TIME, ALWAYS, NEVER }

enum class StampingMode { OFF, MANUAL_ONLY, ASK_END_JOURNEY, ASK_END_SIT }

enum class NowView { BANDS, LIST }

/** User preferences. Stored on the device only. */
data class SotreusSettings(
    val onboardingDone: Boolean = false,
    val currentPlaceId: Long? = null,
    val nowView: NowView = NowView.BANDS,
    val retention: RetentionPolicy = RetentionPolicy.KEEP_30_DAYS,
    val maskCoordinates: Boolean = true,
    val geotagMode: GeotagMode = GeotagMode.ASK_EACH_TIME,
    val geotagNextSession: Boolean = false,
    val scanIntensity: ScanIntensity = ScanIntensity.BALANCED,
    val staleHoldSeconds: Int = SotreusDefaults.BLE_STALE_HOLD_SECONDS_DEFAULT,
    val stampingMode: StampingMode = StampingMode.ASK_END_JOURNEY,
    val nearbyPresence: Boolean = false,
    val tickSound: Boolean = true,
    val simulatedRadios: Boolean = false,
    val forceSolanaUi: Boolean = false,
    /** Now › List shows each radio's address, to spot your own devices. */
    val showAddresses: Boolean = false,
)
