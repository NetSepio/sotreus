package app.sotreus.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.sotreus.core.database.dao.AttentionDao
import app.sotreus.core.database.dao.EncounterDao
import app.sotreus.core.database.dao.EntityDao
import app.sotreus.core.database.dao.FriendDao
import app.sotreus.core.database.dao.ObservationDao
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.dao.ProfileDao
import app.sotreus.core.database.dao.ProofDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.AttentionEventEntity
import app.sotreus.core.database.entity.ContextEventEntity
import app.sotreus.core.database.dao.ContextDao
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

/**
 * The single on-device store. Everything here stays on the phone; network clients never take
 * these entities (architecture handoff §26). Timestamps are UTC epoch milliseconds.
 *
 * Every schema change ships with an explicit migration ([Migrations]); only debuggable builds may
 * fall back to a destructive rebuild when no migration exists.
 */
@Database(
    entities = [
        EntityEntity::class, ObservationEntity::class, EncounterEntity::class, PlaceEntity::class,
        PlaceVisitEntity::class, VisitEntityEntity::class, SessionEntity::class, SessionEntityEntity::class,
        SessionEventEntity::class, SessionLocationEntity::class, AttentionEventEntity::class,
        LocalProfileEntity::class, LinkedIdentityEntity::class, FriendEntity::class, ProofBatchEntity::class,
        ProofLeafEntity::class, ContextEventEntity::class,
    ],
    version = SotreusDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class SotreusDatabase : RoomDatabase() {
    abstract fun entityDao(): EntityDao
    abstract fun observationDao(): ObservationDao
    abstract fun encounterDao(): EncounterDao
    abstract fun placeDao(): PlaceDao
    abstract fun sessionDao(): SessionDao
    abstract fun attentionDao(): AttentionDao
    abstract fun profileDao(): ProfileDao
    abstract fun friendDao(): FriendDao
    abstract fun proofDao(): ProofDao
    abstract fun contextDao(): ContextDao

    companion object {
        const val SCHEMA_VERSION = 3
        const val NAME = "sotreus.db"
    }
}
