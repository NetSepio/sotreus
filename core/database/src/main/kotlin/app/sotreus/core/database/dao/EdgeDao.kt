package app.sotreus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.sotreus.core.database.entity.EdgeBootAnchorEntity
import app.sotreus.core.database.entity.EdgeDeviceEntity
import app.sotreus.core.database.entity.EdgeSightingEntity
import app.sotreus.core.database.entity.EdgeUntimedRecordEntity
import app.sotreus.core.database.entity.LocationFixEntity
import app.sotreus.core.model.LocationSource
import kotlinx.coroutines.flow.Flow

/** Distinct seqs held: an untimed record moves to `edge_sightings` once its boot is anchored. */
internal const val HELD_SEQS_SQL = """SELECT COUNT(*) FROM (
    SELECT seq FROM edge_sightings WHERE device_id = :deviceId AND seq BETWEEN :fromSeq AND :toSeq
    UNION SELECT seq FROM edge_untimed_records WHERE device_id = :deviceId AND seq BETWEEN :fromSeq AND :toSeq
    UNION SELECT seq FROM edge_kept_records WHERE device_id = :deviceId AND seq BETWEEN :fromSeq AND :toSeq)"""

@Dao
interface EdgeDao {
    @Query("SELECT * FROM edge_devices ORDER BY paired_at_ms DESC")
    fun observeDevices(): Flow<List<EdgeDeviceEntity>>

    @Query("SELECT * FROM edge_devices WHERE device_id = :deviceId")
    suspend fun device(deviceId: String): EdgeDeviceEntity?

    @Query("SELECT * FROM edge_devices ORDER BY paired_at_ms DESC LIMIT 1")
    suspend fun current(): EdgeDeviceEntity?

    @Upsert
    suspend fun upsertDevice(device: EdgeDeviceEntity)

    @Query("DELETE FROM edge_devices WHERE device_id = :deviceId")
    suspend fun deleteDevice(deviceId: String)

    @Query("UPDATE edge_devices SET sync_cursor = :cursor, last_sync_ms = :atMs, records_lost = records_lost + :lost WHERE device_id = :deviceId")
    suspend fun advanceCursor(deviceId: String, cursor: Long, atMs: Long, lost: Long)

    /** Moves the cursor forward only (records handed off live may already be past a sync's page). */
    @Query("UPDATE edge_devices SET sync_cursor = MAX(sync_cursor, :cursor), last_sync_ms = :atMs WHERE device_id = :deviceId")
    suspend fun raiseCursor(deviceId: String, cursor: Long, atMs: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKept(rows: List<app.sotreus.core.database.entity.EdgeKeptRecordEntity>)

    @Query("DELETE FROM edge_kept_records WHERE received_at_ms < :beforeMs")
    suspend fun deleteKeptOlderThan(beforeMs: Long): Int

    @Query("DELETE FROM edge_kept_records")
    suspend fun deleteAllKept()

    /** Full-size Bluetooth packets: the owner's choice ([offMs] null), or Sotreus turning them off after early drops. */
    @Query("UPDATE edge_devices SET full_size_packets = :on, full_size_off_ms = :offMs WHERE device_id = :deviceId")
    suspend fun setFullSizePackets(deviceId: String, on: Boolean?, offMs: Long?)

    /** How many of seqs [fromSeq]..[toSeq] this phone holds, in any table a record lands in. */
    @Query(HELD_SEQS_SQL)
    suspend fun heldSeqs(deviceId: String, fromSeq: Long, toSeq: Long): Long

    /** A handshake on [boot]: records lost at the restarts since the last one, and restarts whose loss is not known. */
    @Query("UPDATE edge_devices SET last_boot = :boot, records_lost = records_lost + :lost, restarts_not_known = restarts_not_known + :notKnown WHERE device_id = :deviceId")
    suspend fun noteBoot(deviceId: String, boot: Long, lost: Long, notKnown: Long)

    @Query("UPDATE edge_devices SET last_connected_ms = :atMs, firmware = :firmware, caps_json = :capsJson, probe_salt = :probeSalt WHERE device_id = :deviceId")
    suspend fun connected(deviceId: String, atMs: Long, firmware: String, capsJson: String, probeSalt: String)

    // ---- Boot anchors ----------------------------------------------------------------------

    @Upsert
    suspend fun upsertAnchor(anchor: EdgeBootAnchorEntity)

    @Query("SELECT * FROM edge_boot_anchors WHERE device_id = :deviceId")
    suspend fun anchors(deviceId: String): List<EdgeBootAnchorEntity>

    // ---- Untimed records -------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUntimed(rows: List<EdgeUntimedRecordEntity>)

    @Query("SELECT COUNT(*) FROM edge_untimed_records WHERE device_id = :deviceId")
    fun observeUntimedCount(deviceId: String): Flow<Int>

    @Query("SELECT * FROM edge_untimed_records WHERE device_id = :deviceId AND boot = :boot")
    suspend fun untimedForBoot(deviceId: String, boot: Int): List<EdgeUntimedRecordEntity>

    @Query("DELETE FROM edge_untimed_records WHERE device_id = :deviceId AND seq IN (:seqs)")
    suspend fun deleteUntimed(deviceId: String, seqs: List<Long>)

    // ---- Sightings -------------------------------------------------------------------------

    /** Idempotent: a re-synced record keeps its first stored row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSightings(rows: List<EdgeSightingEntity>): List<Long>

    @Query("SELECT * FROM edge_sightings WHERE entity_id = :entityId ORDER BY last_ms DESC LIMIT :limit")
    fun observeSightings(entityId: String, limit: Int): Flow<List<EdgeSightingEntity>>

    @Query("SELECT * FROM edge_sightings WHERE device_id = :deviceId AND type = 2 AND last_ms BETWEEN :fromMs AND :toMs")
    suspend fun apSightings(deviceId: String, fromMs: Long, toMs: Long): List<EdgeSightingEntity>

    @Query("SELECT COUNT(*) FROM edge_sightings WHERE device_id = :deviceId")
    fun observeSightingCount(deviceId: String): Flow<Int>

    @Query("UPDATE edge_sightings SET loc_kind = 'POINT', loc_source = 'EDGE_FIXED', lat = :lat, lon = :lon, radius_m = :radiusM, coverage = 1.0 WHERE device_id = :deviceId AND first_ms >= :fromMs AND loc_kind = 'NOT_KNOWN'")
    suspend fun applyFixedPosition(deviceId: String, fromMs: Long, lat: Double, lon: Double, radiusM: Double)

    @Query("DELETE FROM edge_sightings WHERE last_ms < :beforeMs")
    suspend fun deleteSightingsOlderThan(beforeMs: Long): Int

    @Query("DELETE FROM edge_sightings")
    suspend fun deleteAllSightings()

    @Query("DELETE FROM edge_untimed_records")
    suspend fun deleteAllUntimed()

    // ---- Location fixes --------------------------------------------------------------------

    @Insert
    suspend fun insertFix(fix: LocationFixEntity)

    @Query("SELECT * FROM location_fixes WHERE at_ms BETWEEN :fromMs AND :toMs ORDER BY at_ms")
    suspend fun fixes(fromMs: Long, toMs: Long): List<LocationFixEntity>

    @Query("DELETE FROM location_fixes WHERE at_ms < :beforeMs")
    suspend fun deleteFixesOlderThan(beforeMs: Long): Int

    @Query("DELETE FROM location_fixes")
    suspend fun deleteAllFixes()

    // ---- Entity and encounter location (V2) ------------------------------------------------

    @Query(
        "UPDATE entities SET last_located_ms = :atMs, last_lat = :lat, last_lon = :lon, last_loc_accuracy_m = :accuracyM, last_loc_source = :source " +
            "WHERE id = :entityId AND (last_located_ms IS NULL OR last_located_ms <= :atMs)",
    )
    suspend fun setEntityLocated(entityId: String, atMs: Long, lat: Double, lon: Double, accuracyM: Float, source: LocationSource)

    @Query("UPDATE entities SET wifi_role = :role WHERE id IN (:ids)")
    suspend fun setWifiRole(ids: List<String>, role: app.sotreus.core.model.WifiRole)

    @Query(
        "UPDATE encounters SET first_lat = COALESCE(first_lat, :lat), first_lon = COALESCE(first_lon, :lon), last_lat = :lat, last_lon = :lon, " +
            "loc_accuracy_m = :accuracyM, loc_source = :source WHERE id = :encounterId",
    )
    suspend fun setEncounterLocated(encounterId: Long, lat: Double, lon: Double, accuracyM: Float, source: LocationSource)

    @Query("SELECT COUNT(*) FROM encounters WHERE entity_id = :entityId AND started_at_ms <= :toMs AND ended_at_ms >= :fromMs")
    suspend fun overlappingEncounters(entityId: String, fromMs: Long, toMs: Long): Int
}
