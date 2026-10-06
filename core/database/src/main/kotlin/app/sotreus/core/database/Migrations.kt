package app.sotreus.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Explicit schema migrations (architecture handoff §23). */
object Migrations {
    /** v2: places get an optional user-set location (lat, lon, radius). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE places ADD COLUMN lat REAL")
            db.execSQL("ALTER TABLE places ADD COLUMN lon REAL")
            db.execSQL("ALTER TABLE places ADD COLUMN radius_m INTEGER")
        }
    }

    /** v3: context events (Remote ID broadcasts, aircraft reported during sessions). */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `context_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`provenance` TEXT NOT NULL, `source` TEXT NOT NULL, `subject_id` TEXT NOT NULL, `title` TEXT NOT NULL, `at_ms` INTEGER NOT NULL, " +
                    "`end_ms` INTEGER, `lat` REAL, `lon` REAL, `alt_m` REAL, `speed_mps` REAL, `course_deg` REAL, `operator_lat` REAL, " +
                    "`operator_lon` REAL, `distance_km` REAL, `session_id` INTEGER, `entity_id` TEXT)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_context_events_at_ms` ON `context_events` (`at_ms`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_context_events_session_id` ON `context_events` (`session_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_context_events_subject_id` ON `context_events` (`subject_id`)")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
