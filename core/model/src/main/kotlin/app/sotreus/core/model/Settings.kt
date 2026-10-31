package app.sotreus.core.model

enum class RetentionPolicy { KEEP_ALL, KEEP_30_DAYS, KEEP_TAGGED_EXPIRE_REST }

enum class GeotagMode { ASK_EACH_TIME, ALWAYS, NEVER }

enum class StampingMode { OFF, MANUAL_ONLY, ASK_END_JOURNEY, ASK_END_SIT }

enum class NowView { BANDS, LIST }

/** How much of the phone's location an aircraft query reveals (architecture handoff §14.1). */
enum class AircraftMode { OFF, COARSE_AREA, EXACT_AREA }

/** How precisely the phone's location is rounded before an air-quality request (architecture handoff §14.5). */
enum class AirQualityMode { OFF, COARSE, FINE }

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
    /** Use location during an active Journey; retain movement summaries even without saving a route. */
    val journeyMovement: Boolean = true,
    /** Manual vendor-prefix requests only. No radio addresses or location are uploaded. */
    val onlineVendorLookup: Boolean = false,
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
    /** USGS earthquakes: the world list is downloaded and filtered on the phone. Sends no location. */
    val earthquakesEnabled: Boolean = false,
    val earthquakeRadiusKm: Int = 300,
    /** GDACS disasters: the world list is downloaded and filtered on the phone. Sends no location. */
    val hazardsEnabled: Boolean = false,
    val airQualityMode: AirQualityMode = AirQualityMode.OFF,
    /**
     * Official weather warnings for where this phone is: NWS in the US, MeteoAlarm in covered European
     * countries. The region is looked up on the phone; only its state or country code is sent.
     */
    val officialWarnings: Boolean = false,
    /** OpenAQ measured air quality. Needs the user's own API key; sends it and one 0.5° cell's bounds. */
    val openAqEnabled: Boolean = false,
    /** Independent opt-ins: stopping location storage does not disable Journey detection. */
    val trackerStopHistory: Boolean = false,
    val gnssMonitoring: Boolean = false,
    val gnssSpoofChecks: Boolean = false,
    val gnssSensitivity: String = "MEDIUM",
    val livePath: Boolean = false,
    val onlineRouteMaps: Boolean = false,
    /** Draw saved plate readers on path maps. The list is downloaded separately; nothing is sent about you. */
    val cameraPlates: Boolean = false,
    /** Draw saved speed, red-light, gunshot and other cameras on path maps. */
    val cameraOthers: Boolean = false,
    val languageTag: String = "en",

)

/** Spatial exports use the same explicit privacy choices as JSON. */
enum class ExportFormat { JSON, GEOJSON, ATAK }
