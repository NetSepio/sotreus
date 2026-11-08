package app.sotreus.core.model

/** Which radios sense (HANDOFF_V2_EDGE.md §1). EDGE keeps this phone's BLE and Wi-Fi scanning off. */
enum class SensingSource { PHONE, EDGE }

/** Who heard an observation. Null in rows written before V2 means [PHONE]. */
enum class ObservationOrigin { PHONE, EDGE }

/**
 * Where a stored location came from (HANDOFF_V2_EDGE.md §4.2). A location is the listener's
 * (this phone's, or the Edge's) at the time of observation, never the radio's.
 */
enum class LocationSource {
    /** This phone heard it, inside a geotagged session (V1 rule). */
    PHONE,

    /** The Edge heard it while this phone was with it (travel). */
    PHONE_WITH_EDGE,

    /** A stationary Edge's set position. */
    EDGE_FIXED,

    /** The Edge's own GNSS fix. */
    EDGE_GNSS,
}

/** A Wi-Fi entity is an access point, or a client device asking for networks (probe requests). */
enum class WifiRole { AP, CLIENT }

enum class EdgeDeployment { TRAVEL, STATIONARY }

/** The data link the owner chose (HANDOFF_V2_EDGE.md §3). Pairing is always Bluetooth. */
enum class EdgeDataLink { BLUETOOTH, BLUETOOTH_WIFI_BURST, SHARED_NETWORK }

/** How a sighting window was located (HANDOFF_V2_EDGE.md §4.6). */
enum class WindowLocationKind { POINT, ALONG_ROUTE, PARTIAL, NOT_KNOWN }
