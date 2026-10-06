package app.sotreus.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import app.sotreus.core.database.entity.AttentionEventEntity
import app.sotreus.core.database.entity.ContextEventEntity
import app.sotreus.core.database.entity.EncounterEntity
import app.sotreus.core.database.entity.EntityEntity
import app.sotreus.core.database.entity.FriendEntity
import app.sotreus.core.database.entity.LinkedIdentityEntity
import app.sotreus.core.database.entity.LocalProfileEntity
import app.sotreus.core.database.entity.ObservationEntity
import app.sotreus.core.database.entity.PlaceEntity
import app.sotreus.core.database.entity.PlaceVisitEntity
import app.sotreus.core.database.entity.ProofBatchEntity
import app.sotreus.core.database.entity.ProofLeafEntity
import app.sotreus.core.database.entity.SessionEntity
import app.sotreus.core.database.entity.SessionEntityEntity
import app.sotreus.core.database.entity.SessionEventEntity
import app.sotreus.core.database.entity.SessionLocationEntity
import app.sotreus.core.database.entity.VisitEntityEntity
import app.sotreus.core.model.UserEntityState
import kotlinx.coroutines.flow.Flow

data class EntityLabelRow(
    val id: String,
    @ColumnInfo(name = "user_state") val userState: UserEntityState,
    @ColumnInfo(name = "user_name") val userName: String?,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
)

/** Partial row for [EntityDao.updateSystem]: everything except the user's fields. */
data class EntitySystemFields(
    val id: String,
    @ColumnInfo(name = "advertised_name") val advertisedName: String?,
    val family: app.sotreus.core.model.DeviceFamily?,
    @ColumnInfo(name = "signature_name") val signatureName: String?,
    val guess: String?,
    @ColumnInfo(name = "guess_confidence") val guessConfidence: app.sotreus.core.model.Confidence,
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
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
    @ColumnInfo(name = "last_rssi") val lastRssi: Int,
    @ColumnInfo(name = "observation_count") val observationCount: Int,
)

data class EntityPlaceName(
    @ColumnInfo(name = "entity_id") val entityId: String,
    val name: String,
)

data class PlaceEncounterSummary(
    @ColumnInfo(name = "place_id") val placeId: Long?,
    val name: String?,
    val count: Int,
    @ColumnInfo(name = "last_ms") val lastMs: Long,
)

data class EntityVisitCount(
    @ColumnInfo(name = "entity_id") val entityId: String,
    val visits: Int,
)

data class EncounterRow(
    val id: Long,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "place_name") val placeName: String?,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long,
    @ColumnInfo(name = "max_rssi") val maxRssi: Int,
)

data class SessionSummaryRow(
    val id: Long,
    val radios: Int,
    @ColumnInfo(name = "new_to_you") val newToYou: Int,
)

data class CountRow(
    @ColumnInfo(name = "k") val key: Long,
    val count: Int,
)

@Dao
interface EntityDao {
    @Query("SELECT * FROM entities ORDER BY last_seen_ms DESC")
    fun observeAll(): Flow<List<EntityEntity>>

    @Query("SELECT * FROM entities WHERE id = :id")
    fun observe(id: String): Flow<EntityEntity?>

    @Query("SELECT * FROM entities WHERE id = :id")
    suspend fun get(id: String): EntityEntity?

    @Query("SELECT * FROM entities WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<EntityEntity>

    @Upsert
    suspend fun upsert(entities: List<EntityEntity>)

    /** New entities only; existing rows (and their user fields) are left alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entities: List<EntityEntity>)

    /** Updates sensed/classified fields and never touches the user's label, name or note. */
    @Update(entity = EntityEntity::class)
    suspend fun updateSystem(rows: List<EntitySystemFields>)

    @Query("SELECT id, user_state, user_name, first_seen_ms FROM entities WHERE user_state != 'UNCLASSIFIED' OR user_name IS NOT NULL")
    fun observeLabeled(): Flow<List<EntityLabelRow>>

    @Query("UPDATE entities SET user_state = :state WHERE id = :id")
    suspend fun setUserState(id: String, state: UserEntityState)

    @Query("UPDATE entities SET user_name = :name WHERE id = :id")
    suspend fun setUserName(id: String, name: String?)

    @Query("UPDATE entities SET note = :note WHERE id = :id")
    suspend fun setNote(id: String, note: String?)

    @Query("SELECT COUNT(*) FROM entities")
    suspend fun count(): Int

    @Query("DELETE FROM entities WHERE id NOT IN (SELECT DISTINCT entity_id FROM observations) AND user_state = 'UNCLASSIFIED' AND note IS NULL")
    suspend fun deleteOrphans()

    @Query("DELETE FROM entities")
    suspend fun deleteAll()

    @Query("DELETE FROM entities WHERE id LIKE 'sim:%'")
    suspend fun deleteSimulated()
}

@Dao
interface ObservationDao {
    @Insert
    suspend fun insert(observations: List<ObservationEntity>)

    @Query("SELECT * FROM observations WHERE entity_id = :entityId ORDER BY observed_at_ms DESC LIMIT :limit")
    fun observeForEntity(entityId: String, limit: Int): Flow<List<ObservationEntity>>

    @Query("SELECT COUNT(*) FROM observations WHERE entity_id = :entityId")
    fun observeCountForEntity(entityId: String): Flow<Int>

    @Query("SELECT * FROM observations WHERE session_id = :sessionId ORDER BY observed_at_ms")
    suspend fun forSession(sessionId: Long): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE entity_id = :entityId ORDER BY observed_at_ms")
    suspend fun allForEntity(entityId: String): List<ObservationEntity>

    @Query("SELECT COUNT(*) FROM observations")
    fun observeCount(): Flow<Int>

    @Query(
        "DELETE FROM observations WHERE observed_at_ms < :beforeMs AND (:keepTagged = 0 OR entity_id NOT IN " +
            "(SELECT id FROM entities WHERE user_state IN ('TAGGED','WATCH','MINE')))",
    )
    suspend fun deleteOlderThan(beforeMs: Long, keepTagged: Boolean): Int

    @Query("DELETE FROM observations WHERE place_id = :placeId")
    suspend fun deleteForPlace(placeId: Long)

    @Query("DELETE FROM observations WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM observations")
    suspend fun deleteAll()

    @Query("DELETE FROM observations WHERE entity_id LIKE 'sim:%'")
    suspend fun deleteSimulated()

    @Query("SELECT * FROM observations WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ObservationEntity>

    @Query(
        "SELECT o.* FROM observations o WHERE o.entity_id = :entityId ORDER BY o.observed_at_ms DESC LIMIT :limit",
    )
    suspend fun recentForEntity(entityId: String, limit: Int): List<ObservationEntity>
}

@Dao
interface EncounterDao {
    @Insert
    suspend fun insert(encounter: EncounterEntity): Long

    @Query("UPDATE encounters SET ended_at_ms = :endedAtMs, max_rssi = MAX(max_rssi, :rssi) WHERE id = :id")
    suspend fun extend(id: Long, endedAtMs: Long, rssi: Int)

    @Query(
        "SELECT e.place_id AS place_id, p.name AS name, COUNT(*) AS count, MAX(e.ended_at_ms) AS last_ms " +
            "FROM encounters e LEFT JOIN places p ON p.id = e.place_id WHERE e.entity_id = :entityId " +
            "GROUP BY e.place_id ORDER BY count DESC",
    )
    fun observeByPlace(entityId: String): Flow<List<PlaceEncounterSummary>>

    @Query("SELECT COUNT(*) FROM encounters WHERE entity_id = :entityId")
    fun observeCount(entityId: String): Flow<Int>

    @Query(
        "SELECT DISTINCT e.entity_id AS entity_id, p.name AS name FROM encounters e " +
            "JOIN places p ON p.id = e.place_id",
    )
    fun observePlaceNames(): Flow<List<EntityPlaceName>>

    @Query(
        "SELECT DISTINCT e.entity_id AS entity_id, p.name AS name FROM encounters e JOIN places p ON p.id = e.place_id " +
            "WHERE e.entity_id IN (:entityIds)",
    )
    suspend fun placeNames(entityIds: List<String>): List<EntityPlaceName>

    @Query(
        "SELECT e.id, e.entity_id, p.name AS place_name, e.started_at_ms, e.ended_at_ms, e.max_rssi FROM encounters e " +
            "LEFT JOIN places p ON p.id = e.place_id ORDER BY e.ended_at_ms DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<EncounterRow>>

    @Query("SELECT entity_id AS k, COUNT(*) AS count FROM encounters GROUP BY entity_id")
    fun observeCounts(): Flow<List<EntityCountRow>>

    @Query("DELETE FROM encounters WHERE place_id = :placeId")
    suspend fun deleteForPlace(placeId: Long)

    @Query("DELETE FROM encounters WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM encounters WHERE ended_at_ms < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    @Query("DELETE FROM encounters")
    suspend fun deleteAll()

    @Query("DELETE FROM encounters WHERE entity_id LIKE 'sim:%'")
    suspend fun deleteSimulated()
}

data class EntityCountRow(
    @ColumnInfo(name = "k") val entityId: String,
    val count: Int,
)

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE id = :id")
    fun observe(id: Long): Flow<PlaceEntity?>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun get(id: Long): PlaceEntity?

    @Insert
    suspend fun insert(place: PlaceEntity): Long

    @Update
    suspend fun update(place: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertVisit(visit: PlaceVisitEntity): Long

    @Query("UPDATE place_visits SET ended_at_ms = :endedAtMs WHERE id = :visitId")
    suspend fun endVisit(visitId: Long, endedAtMs: Long)

    @Query("UPDATE place_visits SET ended_at_ms = :endedAtMs WHERE ended_at_ms IS NULL")
    suspend fun endOpenVisits(endedAtMs: Long)

    @Query("SELECT * FROM place_visits WHERE place_id = :placeId AND ended_at_ms IS NULL LIMIT 1")
    suspend fun openVisit(placeId: Long): PlaceVisitEntity?

    @Query("SELECT COUNT(*) FROM place_visits WHERE place_id = :placeId")
    fun observeVisitCount(placeId: Long): Flow<Int>

    @Query("SELECT * FROM place_visits WHERE place_id = :placeId ORDER BY started_at_ms DESC LIMIT 1")
    fun observeLatestVisit(placeId: Long): Flow<PlaceVisitEntity?>

    @Query("SELECT COUNT(*) FROM place_visits WHERE place_id = :placeId AND ended_at_ms IS NOT NULL")
    fun observeCompletedVisitCount(placeId: Long): Flow<Int>

    @Query("SELECT place_id AS k, COUNT(*) AS count FROM place_visits GROUP BY place_id")
    fun observeVisitCounts(): Flow<List<CountRow>>

    @Query("SELECT COUNT(*) FROM place_visits WHERE place_id = :placeId AND ended_at_ms IS NOT NULL")
    suspend fun completedVisitCount(placeId: Long): Int

    @Upsert
    suspend fun upsertVisitEntities(rows: List<VisitEntityEntity>)

    @Query("SELECT entity_id FROM visit_entities WHERE visit_id = :visitId")
    fun observeVisitEntityIds(visitId: Long): Flow<List<String>>

    @Query(
        "SELECT ve.entity_id AS entity_id, COUNT(DISTINCT ve.visit_id) AS visits FROM visit_entities ve " +
            "JOIN place_visits v ON v.id = ve.visit_id WHERE v.place_id = :placeId AND v.ended_at_ms IS NOT NULL " +
            "GROUP BY ve.entity_id",
    )
    fun observeBaselineCounts(placeId: Long): Flow<List<EntityVisitCount>>

    @Query(
        "SELECT ve.entity_id AS entity_id, COUNT(DISTINCT ve.visit_id) AS visits FROM visit_entities ve " +
            "JOIN place_visits v ON v.id = ve.visit_id WHERE v.place_id = :placeId AND v.ended_at_ms IS NOT NULL " +
            "GROUP BY ve.entity_id",
    )
    suspend fun baselineCounts(placeId: Long): List<EntityVisitCount>

    @Query("DELETE FROM visit_entities WHERE visit_id IN (SELECT id FROM place_visits WHERE place_id = :placeId)")
    suspend fun deleteVisitEntitiesForPlace(placeId: Long)

    @Query("DELETE FROM place_visits WHERE place_id = :placeId")
    suspend fun deleteVisitsForPlace(placeId: Long)

    @Query("DELETE FROM visit_entities")
    suspend fun deleteAllVisitEntities()

    @Query("DELETE FROM visit_entities WHERE entity_id LIKE 'sim:%'")
    suspend fun deleteSimulatedVisitEntities()

    @Query("DELETE FROM place_visits")
    suspend fun deleteAllVisits()
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET ended_at_ms = :endedAtMs WHERE id = :id")
    suspend fun end(id: Long, endedAtMs: Long)

    @Query("SELECT * FROM sessions ORDER BY started_at_ms DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE ended_at_ms IS NULL ORDER BY started_at_ms DESC LIMIT 1")
    fun observeActive(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE ended_at_ms IS NULL ORDER BY started_at_ms DESC LIMIT 1")
    suspend fun active(): SessionEntity?

    @Upsert
    suspend fun upsertEntities(rows: List<SessionEntityEntity>)

    @Query("SELECT * FROM session_entities WHERE session_id = :sessionId")
    suspend fun entities(sessionId: Long): List<SessionEntityEntity>

    @Query("SELECT * FROM session_entities WHERE session_id = :sessionId")
    fun observeEntities(sessionId: Long): Flow<List<SessionEntityEntity>>

    @Query(
        "SELECT session_id AS id, COUNT(*) AS radios, SUM(CASE WHEN new_to_you THEN 1 ELSE 0 END) AS new_to_you " +
            "FROM session_entities GROUP BY session_id",
    )
    fun observeSummaries(): Flow<List<SessionSummaryRow>>

    @Insert
    suspend fun insertEvent(event: SessionEventEntity): Long

    @Query("SELECT * FROM session_events WHERE session_id = :sessionId ORDER BY at_ms DESC")
    fun observeEvents(sessionId: Long): Flow<List<SessionEventEntity>>

    @Insert
    suspend fun insertLocation(location: SessionLocationEntity)

    @Query("SELECT * FROM session_locations WHERE session_id = :sessionId ORDER BY at_ms DESC LIMIT 1")
    suspend fun lastLocation(sessionId: Long): SessionLocationEntity?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM session_entities WHERE session_id = :id")
    suspend fun deleteEntities(id: Long)

    @Query("DELETE FROM session_events WHERE session_id = :id")
    suspend fun deleteEvents(id: Long)

    @Query("DELETE FROM session_locations WHERE session_id = :id")
    suspend fun deleteLocations(id: Long)

    @Query("DELETE FROM session_entities WHERE entity_id LIKE 'sim:%'")
    suspend fun deleteSimulatedEntities()

    @Query("DELETE FROM session_entities")
    suspend fun deleteAllEntities()

    @Query("DELETE FROM session_events WHERE entity_id IS NOT NULL")
    suspend fun deleteSensedEvents()

    @Query("DELETE FROM session_locations")
    suspend fun deleteAllLocations()
}

@Dao
interface AttentionDao {
    @Insert
    suspend fun insert(event: AttentionEventEntity): Long

    @Update
    suspend fun update(event: AttentionEventEntity)

    @Query("SELECT * FROM attention_events WHERE resolved = 0 ORDER BY score DESC, updated_at_ms DESC")
    fun observeOpen(): Flow<List<AttentionEventEntity>>

    @Query("SELECT * FROM attention_events ORDER BY updated_at_ms DESC")
    fun observeAll(): Flow<List<AttentionEventEntity>>

    @Query("SELECT * FROM attention_events WHERE id = :id")
    fun observe(id: Long): Flow<AttentionEventEntity?>

    @Query(
        "SELECT * FROM attention_events WHERE entity_id = :entityId AND " +
            "((:visitId IS NOT NULL AND visit_id = :visitId) OR (:sessionId IS NOT NULL AND session_id = :sessionId) " +
            "OR (:visitId IS NULL AND :sessionId IS NULL AND created_at_ms > :sinceMs)) LIMIT 1",
    )
    suspend fun findOpenFor(entityId: String, visitId: Long?, sessionId: Long?, sinceMs: Long): AttentionEventEntity?

    @Query("SELECT COUNT(*) FROM attention_events WHERE session_id = :sessionId")
    fun observeCountForSession(sessionId: Long): Flow<Int>

    @Query("SELECT session_id AS k, COUNT(*) AS count FROM attention_events WHERE session_id IS NOT NULL GROUP BY session_id")
    fun observeSessionCounts(): Flow<List<CountRow>>

    @Query("SELECT * FROM attention_events WHERE session_id = :sessionId")
    suspend fun forSession(sessionId: Long): List<AttentionEventEntity>

    @Query("UPDATE attention_events SET resolved = 1 WHERE entity_id = :entityId")
    suspend fun resolveForEntity(entityId: String)

    @Query("DELETE FROM attention_events WHERE place_id = :placeId")
    suspend fun deleteForPlace(placeId: Long)

    @Query("DELETE FROM attention_events WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM attention_events")
    suspend fun deleteAll()

    @Query("DELETE FROM attention_events WHERE entity_id LIKE 'sim:%'")
    suspend fun deleteSimulated()

    @Query("SELECT * FROM attention_events WHERE entity_id = :entityId ORDER BY updated_at_ms DESC")
    fun observeForEntity(entityId: String): Flow<List<AttentionEventEntity>>
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM local_profile LIMIT 1")
    fun observe(): Flow<LocalProfileEntity?>

    @Query("SELECT * FROM local_profile LIMIT 1")
    suspend fun get(): LocalProfileEntity?

    @Upsert
    suspend fun upsert(profile: LocalProfileEntity)

    @Query("SELECT * FROM linked_identities ORDER BY created_at_ms DESC")
    fun observeIdentities(): Flow<List<LinkedIdentityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIdentity(identity: LinkedIdentityEntity): Long

    @Query("DELETE FROM linked_identities WHERE kind = 'SOLANA_WALLET'")
    suspend fun deleteWallets()
}

@Dao
interface FriendDao {
    @Query("SELECT * FROM friends ORDER BY added_at_ms DESC")
    fun observeAll(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends")
    suspend fun all(): List<FriendEntity>

    @Query("SELECT * FROM friends WHERE presence_public_key = :keyHex LIMIT 1")
    suspend fun byKey(keyHex: String): FriendEntity?

    @Insert
    suspend fun insert(friend: FriendEntity): Long

    @Query("UPDATE friends SET last_nearby_ms = :atMs, last_nearby_place = :place WHERE id = :id")
    suspend fun markNearby(id: Long, atMs: Long, place: String?)

    @Query("DELETE FROM friends WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM friends")
    suspend fun deleteAll()
}

@Dao
interface ProofDao {
    @Insert
    suspend fun insertBatch(batch: ProofBatchEntity): Long

    @Update
    suspend fun updateBatch(batch: ProofBatchEntity)

    @Insert
    suspend fun insertLeaves(leaves: List<ProofLeafEntity>)

    @Query("SELECT * FROM proof_batches ORDER BY created_at_ms DESC")
    fun observeBatches(): Flow<List<ProofBatchEntity>>

    @Query("SELECT * FROM proof_batches WHERE id = :id")
    fun observeBatch(id: Long): Flow<ProofBatchEntity?>

    @Query("SELECT * FROM proof_batches WHERE id = :id")
    suspend fun batch(id: Long): ProofBatchEntity?

    @Query("SELECT * FROM proof_leaves WHERE batch_id = :batchId ORDER BY idx")
    suspend fun leaves(batchId: Long): List<ProofLeafEntity>

    @Query(
        "SELECT DISTINCT b.* FROM proof_batches b JOIN proof_leaves l ON l.batch_id = b.id " +
            "WHERE l.entity_id = :entityId ORDER BY b.created_at_ms DESC",
    )
    fun observeBatchesForEntity(entityId: String): Flow<List<ProofBatchEntity>>

    @Query("DELETE FROM proof_leaves WHERE batch_id IN (SELECT id FROM proof_batches WHERE state = 'PENDING')")
    suspend fun deletePendingLeaves()

    @Query("DELETE FROM proof_batches WHERE state = 'PENDING'")
    suspend fun deletePendingBatches()
}

@Dao
interface ContextDao {
    @Insert
    suspend fun insert(event: ContextEventEntity): Long

    @Update
    suspend fun update(event: ContextEventEntity)

    @Query("SELECT * FROM context_events WHERE kind = :kind AND subject_id = :subject ORDER BY at_ms DESC LIMIT 1")
    suspend fun latest(kind: app.sotreus.core.model.ContextKind, subject: String): ContextEventEntity?

    @Query("SELECT * FROM context_events WHERE kind = :kind AND at_ms >= :sinceMs ORDER BY at_ms DESC")
    fun observeSince(kind: app.sotreus.core.model.ContextKind, sinceMs: Long): Flow<List<ContextEventEntity>>

    @Query("SELECT * FROM context_events WHERE kind = 'REMOTE_ID' AND subject_id = :subject ORDER BY at_ms DESC LIMIT :limit")
    fun observeRemoteId(subject: String, limit: Int = 200): Flow<List<ContextEventEntity>>

    @Query("SELECT * FROM context_events WHERE at_ms BETWEEN :fromMs AND :toMs ORDER BY at_ms")
    suspend fun between(fromMs: Long, toMs: Long): List<ContextEventEntity>

    @Query("SELECT * FROM context_events WHERE session_id = :sessionId ORDER BY at_ms")
    suspend fun forSession(sessionId: Long): List<ContextEventEntity>

    @Query("SELECT COUNT(*) FROM context_events")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM context_events WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM context_events")
    suspend fun deleteAll()

    @Query("DELETE FROM context_events WHERE source = '$SOURCE_SIMULATED'")
    suspend fun deleteSimulated()

    companion object {
        /** Source of records produced from the simulated radios. */
        const val SOURCE_SIMULATED = "Simulated radios"
    }

    @Query("DELETE FROM context_events WHERE at_ms < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}
