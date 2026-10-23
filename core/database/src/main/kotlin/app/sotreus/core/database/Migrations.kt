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

    /** v4: Proofs & Tracking — lost devices, sightings, fixed-witness tokens and records. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `lost_reports` (`entity_id` TEXT NOT NULL, `lookup_hex` TEXT NOT NULL, `label` TEXT NOT NULL, `owner_public_hex` TEXT NOT NULL, `owner_private_wrapped` TEXT NOT NULL, `delete_secret_wrapped` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `published_at_ms` INTEGER, `last_polled_ms` INTEGER, `found_at_ms` INTEGER, PRIMARY KEY(`entity_id`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_lost_reports_lookup_hex` ON `lost_reports` (`lookup_hex`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `find_sightings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entity_id` TEXT NOT NULL, `lookup_hex` TEXT NOT NULL, `seen_at_ms` INTEGER NOT NULL, `received_at_ms` INTEGER NOT NULL, `lat` REAL, `lon` REAL, `accuracy_m` REAL, `rssi` INTEGER, `by_this_phone` INTEGER NOT NULL, `simulated` INTEGER NOT NULL, `relay_id` TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_find_sightings_entity_id` ON `find_sightings` (`entity_id`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_find_sightings_relay_id` ON `find_sightings` (`relay_id`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `witness_heard` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `session_id` INTEGER NOT NULL, `token_hex` TEXT NOT NULL, `slot` INTEGER NOT NULL, `first_seen_ms` INTEGER NOT NULL, `last_seen_ms` INTEGER NOT NULL, `best_rssi` INTEGER NOT NULL, `lat` REAL, `lon` REAL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_witness_heard_session_id_token_hex` ON `witness_heard` (`session_id`, `token_hex`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_witness_heard_token_hex` ON `witness_heard` (`token_hex`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `witness_attestations` (`token_hex` TEXT NOT NULL, `slot` INTEGER NOT NULL, `witness_public_hex` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `published_at_ms` INTEGER NOT NULL, `signature_hex` TEXT NOT NULL, `valid` INTEGER NOT NULL, `fetched_at_ms` INTEGER NOT NULL, PRIMARY KEY(`token_hex`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `witness_slots` (`slot` INTEGER NOT NULL, `token_hex` TEXT NOT NULL, `place_id` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `published_at_ms` INTEGER, PRIMARY KEY(`slot`))")
        }
    }

    /** v5: observations can carry an opt-in plus code of the phone's location. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE observations ADD COLUMN plus_code TEXT")
        }
    }

    /** v6: cached public vendor-prefix lookups. No observed addresses or location. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `vendor_lookups` (`prefix` TEXT NOT NULL, `vendor` TEXT, `block_type` TEXT, `registry_updated` TEXT, `fetched_at_ms` INTEGER NOT NULL, `expires_at_ms` INTEGER NOT NULL, PRIMARY KEY(`prefix`))")
            db.execSQL("UPDATE entities SET link_confidence = 'LOW', vendor = NULL WHERE radio = 'WIFI' AND UPPER(SUBSTR(REPLACE(address, ':', ''), 2, 1)) IN ('1','2','3','5','6','7','9','A','B','D','E','F')")
            db.execSQL("UPDATE entities SET vendor = NULL WHERE radio = 'BLE' AND address_type != 'PUBLIC'")
        }
    }

    /** v7: bounded opt-in tracker stop history and Remote ID operator semantics. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS tracker_memory (stable_id TEXT NOT NULL, last_heard_ms INTEGER NOT NULL, PRIMARY KEY(stable_id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS tracker_stops (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entity_id TEXT NOT NULL, stable_id TEXT NOT NULL, session_id INTEGER, lat REAL NOT NULL, lon REAL NOT NULL, first_ms INTEGER NOT NULL, last_ms INTEGER NOT NULL, hits INTEGER NOT NULL, return_gap_ms INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tracker_stops_entity_id ON tracker_stops (entity_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tracker_stops_stable_id ON tracker_stops (stable_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tracker_stops_session_id ON tracker_stops (session_id)")
            db.execSQL("ALTER TABLE session_entities ADD COLUMN is_flood INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE context_events ADD COLUMN operator_kind INTEGER")
            db.execSQL("ALTER TABLE context_events ADD COLUMN operator_alt_m REAL")
            db.execSQL("ALTER TABLE context_events ADD COLUMN height_m REAL")
            db.execSQL("ALTER TABLE context_events ADD COLUMN height_reference INTEGER")
            db.execSQL("ALTER TABLE context_events ADD COLUMN eu_category TEXT")
            db.execSQL("ALTER TABLE context_events ADD COLUMN eu_class TEXT")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
}
