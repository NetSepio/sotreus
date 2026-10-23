package app.sotreus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** This phone's opt-in hear location; never a device's advertised position. */
@Entity(tableName = "tracker_stops", indices = [Index("entity_id"), Index("stable_id"), Index("session_id")])
data class TrackerStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "stable_id") val stableId: String,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "first_ms") val firstMs: Long,
    @ColumnInfo(name = "last_ms") val lastMs: Long,
    val hits: Int,
    @ColumnInfo(name = "return_gap_ms") val returnGapMs: Long = 0,
)

/** Coordinate-free continuity clock, so continuous travel is never mistaken for a quiet return. */
@Entity(tableName = "tracker_memory")
data class TrackerMemoryEntity(
    @PrimaryKey @ColumnInfo(name = "stable_id") val stableId: String,
    @ColumnInfo(name = "last_heard_ms") val lastHeardMs: Long,
)
