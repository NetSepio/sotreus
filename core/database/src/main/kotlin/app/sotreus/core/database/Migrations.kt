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

    val ALL = arrayOf(MIGRATION_1_2)
}
