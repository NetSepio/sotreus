package app.sotreus.core.model

enum class RetentionPolicy { KEEP_ALL, KEEP_30_DAYS, KEEP_TAGGED_EXPIRE_REST }

enum class GeotagMode { ASK_EACH_TIME, ALWAYS, NEVER }

enum class StampingMode { OFF, MANUAL_ONLY, ASK_END_JOURNEY, ASK_END_SIT }

enum class NowView { BANDS, LIST }

/** How much of the phone's location an aircraft query reveals (architecture handoff §14.1). */
enum class AircraftMode { OFF, COARSE_AREA, EXACT_AREA }

/** CelesTrak groups Sotreus can predict. [celestrakGroup] is the CelesTrak GROUP name. */
enum class SatelliteGroup(val celestrakGroup: String) {
    EARTH_OBSERVATION("resource"),
    WEATHER("weather"),
    SPACE_STATIONS("stations"),
}

/** User preferences. Stored on the device only. */
data class SotreusSettings(
    val onboardingDone: Boolean = false,
    val currentPlaceId: Long? = null,
    /** The current place was picked by phone location (inside its radius), not by hand. */
    val currentPlaceByLocation: Boolean = false,
    /** Pick saved places by phone location while Sotreus is open. Uses location; stores no coordinates. */
    val placeByLocation: Boolean = true,
    /** Tag each observation with a ~14 m plus code of the phone's location while Sotreus is open. */
    val plusCodeTags: Boolean = false,
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
    /** Master switch for every context source. Off means no context network requests at all. */
    val contextEnabled: Boolean = true,
    val aircraftMode: AircraftMode = AircraftMode.OFF,
    val aircraftRadiusKm: Int = 25,
    val satellitesEnabled: Boolean = true,
    val satelliteGroups: Set<SatelliteGroup> = SatelliteGroup.entries.toSet(),
    /** Local Remote ID decoding (beta until validated across devices and regions). */
    val remoteIdEnabled: Boolean = true,
)
