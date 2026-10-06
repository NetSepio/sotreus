package app.sotreus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.sotreus.core.model.AttentionHeadline
import app.sotreus.core.model.BleAddressType
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.ContextKind
import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.model.LinkedIdentityKind
import app.sotreus.core.model.ProofState
import app.sotreus.core.model.Provenance
import app.sotreus.core.model.RadioKind
import app.sotreus.core.model.SessionEventKind
import app.sotreus.core.model.SessionKind
import app.sotreus.core.model.SolanaCluster
import app.sotreus.core.model.UserEntityState

// Every table here is on-device only (architecture handoff §23). Timestamps are UTC epoch ms.
// Network clients never accept these classes; they take narrow DTOs (handoff §26).

/** An inferred radio entity: kind + address, with classification and the user's label. */
@Entity(tableName = "entities", indices = [Index("last_seen_ms"), Index("user_state")])
data class EntityEntity(
    @PrimaryKey val id: String,
    val radio: RadioKind,
    val address: String,
    @ColumnInfo(name = "address_type") val addressType: BleAddressType,
    @ColumnInfo(name = "link_confidence") val linkConfidence: Confidence,
    @ColumnInfo(name = "advertised_name") val advertisedName: String?,
    @ColumnInfo(name = "user_name") val userName: String? = null,
    @ColumnInfo(name = "user_state") val userState: UserEntityState = UserEntityState.UNCLASSIFIED,
    val note: String? = null,
    val family: DeviceFamily?,
    @ColumnInfo(name = "signature_name") val signatureName: String?,
    val guess: String?,
    @ColumnInfo(name = "guess_confidence") val guessConfidence: Confidence,
    val notable: Boolean,
    val vendor: String?,
    @ColumnInfo(name = "company_id") val companyId: Int?,
    @ColumnInfo(name = "service_uuids") val serviceUuids: String,
    @ColumnInfo(name = "service_data") val serviceData: String?,
    val connectable: Boolean?,
    @ColumnInfo(name = "tx_power") val txPower: Int?,
    val security: String?,
    @ColumnInfo(name = "frequency_mhz") val frequencyMhz: Int?,
    val channel: Int?,
    @ColumnInfo(name = "wifi_standard") val wifiStandard: String?,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
    @ColumnInfo(name = "last_rssi") val lastRssi: Int,
    @ColumnInfo(name = "observation_count") val observationCount: Int,
)

/** Raw evidence. Sampled (not every packet) and bounded by retention. */
@Entity(
    tableName = "observations",
    indices = [Index("entity_id"), Index("observed_at_ms"), Index("session_id"), Index("place_id")],
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "observed_at_ms") val observedAtMs: Long,
    val rssi: Int,
    val radio: RadioKind,
    val provenance: Provenance = Provenance.SENSED,
    @ColumnInfo(name = "place_id") val placeId: Long?,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    /** Phone location at time of observation; only inside a geotagged session. */
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "raw_hex") val rawHex: String?,
)

/** A contiguous stretch of presence of one entity at one place/session. */
@Entity(tableName = "encounters", indices = [Index("entity_id"), Index("place_id"), Index("ended_at_ms")])
data class EncounterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "place_id") val placeId: Long?,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long,
    @ColumnInfo(name = "max_rssi") val maxRssi: Int,
)

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "keep_learning") val keepLearning: Boolean = true,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    /** Where the place is, set by the user (current location or map pick). On this phone only. */
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "radius_m") val radiusM: Int? = null,
)

@Entity(tableName = "place_visits", indices = [Index("place_id")])
data class PlaceVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "place_id") val placeId: Long,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long? = null,
)

/** Which entities a place visit contained; the place baseline is learned from these. */
@Entity(tableName = "visit_entities", primaryKeys = ["visit_id", "entity_id"], indices = [Index("entity_id")])
data class VisitEntityEntity(
    @ColumnInfo(name = "visit_id") val visitId: Long,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SessionKind,
    val name: String,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long? = null,
    val geotag: Boolean,
    @ColumnInfo(name = "place_id") val placeId: Long?,
    @ColumnInfo(name = "start_label") val startLabel: String?,
)

@Entity(tableName = "session_entities", primaryKeys = ["session_id", "entity_id"], indices = [Index("entity_id")])
data class SessionEntityEntity(
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
    @ColumnInfo(name = "present_ms") val presentMs: Long,
    @ColumnInfo(name = "rssi_sum") val rssiSum: Long,
    @ColumnInfo(name = "rssi_count") val rssiCount: Int,
    @ColumnInfo(name = "new_to_you") val newToYou: Boolean,
)

@Entity(tableName = "session_events", indices = [Index("session_id")])
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    val kind: SessionEventKind,
    @ColumnInfo(name = "entity_id") val entityId: String? = null,
    /** User note text, or a data label (entity name / family) for sensed events. */
    val text: String? = null,
)

@Entity(tableName = "session_locations", indices = [Index("session_id")])
data class SessionLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float?,
)

@Entity(tableName = "attention_events", indices = [Index("entity_id"), Index("created_at_ms")])
data class AttentionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "place_id") val placeId: Long?,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    @ColumnInfo(name = "visit_id") val visitId: Long?,
    val score: Float,
    val headline: AttentionHeadline,
    /** JSON-encoded reasons and inputs; always at least one reason. */
    @ColumnInfo(name = "reasons_json") val reasonsJson: String,
    @ColumnInfo(name = "inputs_json") val inputsJson: String,
    val resolved: Boolean = false,
)

/** The install's local person profile, created before any wallet (handoff §6). */
@Entity(tableName = "local_profile")
data class LocalProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    val handle: String? = null,
    @ColumnInfo(name = "avatar_local_uri") val avatarLocalUri: String?,
    @ColumnInfo(name = "created_at_utc_ms") val createdAtUtcMs: Long,
)

@Entity(tableName = "linked_identities")
data class LinkedIdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: LinkedIdentityKind,
    @ColumnInfo(name = "public_key") val publicKey: String,
    val cluster: SolanaCluster?,
    @ColumnInfo(name = "wallet_label") val walletLabel: String?,
    @ColumnInfo(name = "signed_in") val signedIn: Boolean,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "presence_public_key") val presencePublicKeyHex: String,
    /** Pairwise secret, wrapped with an Android Keystore key. Never leaves the phone. */
    @ColumnInfo(name = "pairwise_secret_wrapped") val pairwiseSecretWrapped: String,
    @ColumnInfo(name = "added_at_ms") val addedAtMs: Long,
    @ColumnInfo(name = "last_nearby_ms") val lastNearbyMs: Long? = null,
    @ColumnInfo(name = "last_nearby_place") val lastNearbyPlace: String? = null,
)

@Entity(tableName = "proof_batches")
data class ProofBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    val title: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "record_count") val recordCount: Int,
    @ColumnInfo(name = "observation_count") val observationCount: Int,
    @ColumnInfo(name = "attention_count") val attentionCount: Int,
    @ColumnInfo(name = "merkle_root") val merkleRootHex: String,
    @ColumnInfo(name = "commitment") val commitmentHex: String,
    @ColumnInfo(name = "nonce") val nonceHex: String,
    val schema: String,
    val cluster: SolanaCluster,
    @ColumnInfo(name = "tx_signature") val txSignature: String? = null,
    val state: ProofState = ProofState.PENDING,
    @ColumnInfo(name = "block_time_ms") val blockTimeMs: Long? = null,
    @ColumnInfo(name = "wallet_address") val walletAddress: String? = null,
    val error: String? = null,
)

/** What is needed to later prove one record: its salt, leaf hash and canonical bytes. */
@Entity(tableName = "proof_leaves", primaryKeys = ["batch_id", "idx"], indices = [Index("record_id")])
data class ProofLeafEntity(
    @ColumnInfo(name = "batch_id") val batchId: Long,
    val idx: Int,
    @ColumnInfo(name = "record_type") val recordType: String,
    @ColumnInfo(name = "record_id") val recordId: Long,
    @ColumnInfo(name = "entity_id") val entityId: String?,
    @ColumnInfo(name = "salt") val saltHex: String,
    @ColumnInfo(name = "leaf_hash") val leafHashHex: String,
    @ColumnInfo(name = "canonical") val canonicalHex: String,
)

/**
 * Context items worth remembering: Remote ID broadcasts this phone received (SENSED) and aircraft
 * reported by a provider during a session (NETWORK). Satellite passes are recomputed on demand.
 */
@Entity(tableName = "context_events", indices = [Index("at_ms"), Index("session_id"), Index("subject_id")])
data class ContextEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ContextKind,
    val provenance: Provenance,
    /** Provider or origin, e.g. "OpenSky Network", "Phone BLE". */
    val source: String,
    /** ICAO24 address, Remote ID UAS ID or NORAD number. */
    @ColumnInfo(name = "subject_id") val subjectId: String,
    val title: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "alt_m") val altM: Double? = null,
    @ColumnInfo(name = "speed_mps") val speedMps: Double? = null,
    @ColumnInfo(name = "course_deg") val courseDeg: Double? = null,
    @ColumnInfo(name = "operator_lat") val operatorLat: Double? = null,
    @ColumnInfo(name = "operator_lon") val operatorLon: Double? = null,
    @ColumnInfo(name = "distance_km") val distanceKm: Double? = null,
    @ColumnInfo(name = "session_id") val sessionId: Long? = null,
    @ColumnInfo(name = "entity_id") val entityId: String? = null,
)
