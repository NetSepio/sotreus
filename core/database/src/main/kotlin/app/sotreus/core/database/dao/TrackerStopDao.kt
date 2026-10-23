package app.sotreus.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.sotreus.core.database.entity.TrackerStopEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackerStopDao {
    @Query("SELECT last_heard_ms FROM tracker_memory WHERE stable_id = :stableId") suspend fun lastHeard(stableId: String): Long?
    @Query("INSERT OR REPLACE INTO tracker_memory (stable_id, last_heard_ms) VALUES (:stableId, :atMs)") suspend fun remember(stableId: String, atMs: Long)
    @Query("DELETE FROM tracker_memory WHERE last_heard_ms < :cutoff OR stable_id NOT IN (SELECT stable_id FROM tracker_memory ORDER BY last_heard_ms DESC LIMIT 400)") suspend fun pruneMemory(cutoff: Long)
    @Query("DELETE FROM tracker_memory") suspend fun clearMemory()
    @Query("SELECT * FROM tracker_stops WHERE stable_id = :stableId AND hits >= 2 ORDER BY last_ms DESC LIMIT 8")
    fun observeStable(stableId: String): Flow<List<TrackerStopEntity>>

    @Query("SELECT * FROM tracker_stops WHERE entity_id = :entityId AND hits >= 2 ORDER BY last_ms DESC LIMIT 8")
    fun observe(entityId: String): Flow<List<TrackerStopEntity>>
    @Query("SELECT * FROM tracker_stops WHERE stable_id = :stableId ORDER BY last_ms DESC LIMIT 8")
    suspend fun latest(stableId: String): List<TrackerStopEntity>
    @Insert suspend fun insert(row: TrackerStopEntity): Long
    @Update suspend fun update(row: TrackerStopEntity)
    @Query("DELETE FROM tracker_stops WHERE last_ms < :cutoff") suspend fun prune(cutoff: Long)
    @Query("DELETE FROM tracker_stops WHERE stable_id = :stableId AND id NOT IN (SELECT id FROM tracker_stops WHERE stable_id = :stableId ORDER BY last_ms DESC LIMIT 8)")
    suspend fun cap(stableId: String)
    @Query("DELETE FROM tracker_stops WHERE stable_id NOT IN (SELECT stable_id FROM tracker_stops GROUP BY stable_id ORDER BY MAX(last_ms) DESC LIMIT 400)")
    suspend fun capAll()
    @Query("DELETE FROM tracker_stops WHERE session_id = :sessionId") suspend fun deleteForSession(sessionId: Long)
    @Query("DELETE FROM tracker_stops") suspend fun clear()
}
