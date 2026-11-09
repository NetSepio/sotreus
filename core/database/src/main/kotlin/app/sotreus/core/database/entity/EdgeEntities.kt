package app.sotreus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.sotreus.core.model.EdgeDataLink
import app.sotreus.core.model.EdgeDeployment
import app.sotreus.core.model.LocationSource
import app.sotreus.core.model.WindowLocationKind

// Sotreus Edge (V2). On-device only, like every table here (architecture handoff §23).

/** A paired Edge. `device_id` is `info.device_id`; the Bluetooth address is only how to reach it. */
@Entity(tableName = "edge_devices")
data class EdgeDeviceEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    val name: String,
    val suffix: String,
    val hardware: String,
    val firmware: String,
    val protocol: Int,
    @ColumnInfo(name = "caps_json") val capsJson: String,
    /** Hex. Hashes network names the owner types, to compare with probe records. */
    @ColumnInfo(name = "probe_salt") val probeSalt: String,
    @ColumnInfo(name = "bt_address") val btAddress: String,
    @ColumnInfo(name = "paired_at_ms") val pairedAtMs: Long,
    @ColumnInfo(name = "last_connected_ms") val lastConnectedMs: Long? = null,
    @ColumnInfo(name = "last_sync_ms") val lastSyncMs: Long? = null,
    /** Next seq to fetch; advanced only after a page is stored. */
    @ColumnInfo(name = "sync_cursor") val syncCursor: Long,
    /** Records lost before this phone held them: overwritten by the ring buffer, or lost at an Edge restart. */
    @ColumnInfo(name = "records_lost") val recordsLost: Long = 0,
    @ColumnInfo(name = "data_link") val dataLink: EdgeDataLink = EdgeDataLink.BLUETOOTH,
    val deployment: EdgeDeployment = EdgeDeployment.TRAVEL,
    @ColumnInfo(name = "place_lat") val placeLat: Double? = null,
    @ColumnInfo(name = "place_lon") val placeLon: Double? = null,
    @ColumnInfo(name = "place_accuracy_m") val placeAccuracyM: Float? = null,
    @ColumnInfo(name = "place_set_ms") val placeSetMs: Long? = null,
    @ColumnInfo(name = "place_source") val placeSource: String? = null,
    /** A Place a stationary Edge feeds. */
    @ColumnInfo(name = "linked_place_id") val linkedPlaceId: Long? = null,
    /** Strong Wi-Fi neighbourhood at placement, comma-separated BSSIDs (moved check). */
    @ColumnInfo(name = "wifi_baseline") val wifiBaseline: String? = null,
    @ColumnInfo(name = "baseline_from_ms") val baselineFromMs: Long? = null,
    /** Set while a moved check waits for the owner; the fixed position is not applied after it. */
    @ColumnInfo(name = "placement_held_since_ms") val placementHeldSinceMs: Long? = null,
    /** Travel mode: tag sightings with this phone's location while together. */
    @ColumnInfo(name = "phone_tagging") val phoneTagging: Boolean = true,
    /** The placement above (mode and position) was set on this phone and is not on the Edge yet. */
    @ColumnInfo(name = "placement_pending", defaultValue = "0") val placementPending: Boolean = false,
    /**
     * Full-size Bluetooth packets on this phone's links (protocol "Link-layer packets"). Null
     * follows this phone's default: on when its Bluetooth has Bluetooth 5 features.
     */
    @ColumnInfo(name = "full_size_packets") val fullSizePackets: Boolean? = null,
    /** Set when Sotreus turned full-size packets off after links dropped early; cleared by the owner's choice. */
    @ColumnInfo(name = "full_size_off_ms") val fullSizeOffMs: Long? = null,
    /** The Edge's boot at this phone's last handshake: restarts after it are judged for lost records. */
    @ColumnInfo(name = "last_boot") val lastBoot: Long? = null,
    /** Edge restarts whose lost records are not known (a power loss, the reset pin, older firmware). */
    @ColumnInfo(name = "restarts_not_known", defaultValue = "0") val restartsNotKnown: Long = 0,
)

/** Unix ms at which an Edge boot started, seen by this phone while connected. */
@Entity(tableName = "edge_boot_anchors", primaryKeys = ["device_id", "boot"])
data class EdgeBootAnchorEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    /** Low 16 bits, as records carry it. */
    val boot: Int,
    @ColumnInfo(name = "unix_at_boot_ms") val unixAtBootMs: Long,
)

/** A record with no wall time and no anchor: kept apart, shown only as a count ("time not known"). */
@Entity(tableName = "edge_untimed_records", primaryKeys = ["device_id", "seq"])
data class EdgeUntimedRecordEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    val seq: Long,
    val boot: Int,
    val type: Int,
    @ColumnInfo(name = "first_uptime_ms") val firstUptimeMs: Long,
    @ColumnInfo(name = "last_uptime_ms") val lastUptimeMs: Long,
    @ColumnInfo(name = "record_hex") val recordHex: String,
    @ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
)

/** One Edge sighting window (record types 1–3) with its location result. */
@Entity(
    tableName = "edge_sightings",
    primaryKeys = ["device_id", "seq"],
    indices = [Index("entity_id"), Index("last_ms")],
)
data class EdgeSightingEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    val seq: Long,
    @ColumnInfo(name = "entity_id") val entityId: String,
    /** 1 BLE, 2 Wi-Fi AP, 3 Wi-Fi probe. */
    val type: Int,
    @ColumnInfo(name = "first_ms") val firstMs: Long,
    @ColumnInfo(name = "last_ms") val lastMs: Long,
    @ColumnInfo(name = "rssi_last") val rssiLast: Int,
    @ColumnInfo(name = "rssi_max") val rssiMax: Int,
    @ColumnInfo(name = "rssi_min") val rssiMin: Int,
    val frames: Long,
    val continues: Boolean,
    /** The phone was receiving this Edge live during the window. */
    @ColumnInfo(name = "covered_live") val coveredLive: Boolean,
    @ColumnInfo(name = "loc_kind") val locKind: WindowLocationKind,
    @ColumnInfo(name = "loc_source") val locSource: LocationSource? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "radius_m") val radiusM: Double? = null,
    @ColumnInfo(name = "end_lat") val endLat: Double? = null,
    @ColumnInfo(name = "end_lon") val endLon: Double? = null,
    @ColumnInfo(name = "path_m") val pathM: Double? = null,
    val coverage: Double? = null,
    @ColumnInfo(name = "altitude_m") val altitudeM: Double? = null,
    @ColumnInfo(name = "vertical_accuracy_m") val verticalAccuracyM: Float? = null,
)

/**
 * This phone's fixes used to tag Edge sightings (travel mode). Written only while tagging and only
 * fixes that passed the quality checks (HANDOFF_V2_EDGE.md §4.4). Same retention as observations.
 */
@Entity(tableName = "location_fixes", indices = [Index("at_ms")])
data class LocationFixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float,
    @ColumnInfo(name = "altitude_m") val altitudeM: Double? = null,
    @ColumnInfo(name = "vertical_accuracy_m") val verticalAccuracyM: Float? = null,
    @ColumnInfo(name = "msl_altitude_m") val mslAltitudeM: Double? = null,
    @ColumnInfo(name = "speed_mps") val speedMps: Float? = null,
    @ColumnInfo(name = "speed_accuracy_mps") val speedAccuracyMps: Float? = null,
    val provider: String? = null,
    @ColumnInfo(name = "satellites_used") val satellitesUsed: Int? = null,
    @ColumnInfo(name = "satellites_in_view") val satellitesInView: Int? = null,
    @ColumnInfo(name = "pressure_hpa") val pressureHpa: Float? = null,
)

/**
 * A record kept raw because nothing else reads it yet (mesh text, Edge events, positions, unknown
 * types). Delete after sync erases records once the phone confirms them, so every record received
 * is stored somewhere before it is confirmed.
 */
@Entity(tableName = "edge_kept_records", primaryKeys = ["device_id", "seq"], indices = [Index("received_at_ms")])
data class EdgeKeptRecordEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    val seq: Long,
    val type: Int,
    val boot: Int,
    val flags: Int,
    @ColumnInfo(name = "first_ms") val firstMs: Long,
    @ColumnInfo(name = "last_ms") val lastMs: Long,
    @ColumnInfo(name = "record_hex") val recordHex: String,
    @ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
)
