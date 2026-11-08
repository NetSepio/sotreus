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

    /**
     * v8 (V2, Sotreus Edge): paired Edges, boot anchors, untimed records, Edge sighting windows,
     * the phone fixes used to tag them, and origin / location provenance on observations,
     * encounters and entities. New columns on existing tables are nullable: null means "this
     * phone", as before V2. Statements generated from the exported schema 8.json.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE entities ADD COLUMN wifi_role TEXT")
            db.execSQL("ALTER TABLE entities ADD COLUMN last_located_ms INTEGER")
            db.execSQL("ALTER TABLE entities ADD COLUMN last_lat REAL")
            db.execSQL("ALTER TABLE entities ADD COLUMN last_lon REAL")
            db.execSQL("ALTER TABLE entities ADD COLUMN last_loc_accuracy_m REAL")
            db.execSQL("ALTER TABLE entities ADD COLUMN last_loc_source TEXT")
            db.execSQL("ALTER TABLE observations ADD COLUMN origin TEXT")
            db.execSQL("ALTER TABLE observations ADD COLUMN loc_source TEXT")
            db.execSQL("ALTER TABLE observations ADD COLUMN loc_accuracy_m REAL")
            db.execSQL("ALTER TABLE encounters ADD COLUMN first_lat REAL")
            db.execSQL("ALTER TABLE encounters ADD COLUMN first_lon REAL")
            db.execSQL("ALTER TABLE encounters ADD COLUMN last_lat REAL")
            db.execSQL("ALTER TABLE encounters ADD COLUMN last_lon REAL")
            db.execSQL("ALTER TABLE encounters ADD COLUMN loc_accuracy_m REAL")
            db.execSQL("ALTER TABLE encounters ADD COLUMN loc_source TEXT")
            db.execSQL("ALTER TABLE encounters ADD COLUMN origin TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS `edge_devices` (`device_id` TEXT NOT NULL, `name` TEXT NOT NULL, `suffix` TEXT NOT NULL, `hardware` TEXT NOT NULL, `firmware` TEXT NOT NULL, `protocol` INTEGER NOT NULL, `caps_json` TEXT NOT NULL, `probe_salt` TEXT NOT NULL, `bt_address` TEXT NOT NULL, `paired_at_ms` INTEGER NOT NULL, `last_connected_ms` INTEGER, `last_sync_ms` INTEGER, `sync_cursor` INTEGER NOT NULL, `records_lost` INTEGER NOT NULL, `data_link` TEXT NOT NULL, `deployment` TEXT NOT NULL, `place_lat` REAL, `place_lon` REAL, `place_accuracy_m` REAL, `place_set_ms` INTEGER, `place_source` TEXT, `linked_place_id` INTEGER, `wifi_baseline` TEXT, `baseline_from_ms` INTEGER, `placement_held_since_ms` INTEGER, `phone_tagging` INTEGER NOT NULL, PRIMARY KEY(`device_id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `edge_boot_anchors` (`device_id` TEXT NOT NULL, `boot` INTEGER NOT NULL, `unix_at_boot_ms` INTEGER NOT NULL, PRIMARY KEY(`device_id`, `boot`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `edge_untimed_records` (`device_id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `boot` INTEGER NOT NULL, `type` INTEGER NOT NULL, `first_uptime_ms` INTEGER NOT NULL, `last_uptime_ms` INTEGER NOT NULL, `record_hex` TEXT NOT NULL, `received_at_ms` INTEGER NOT NULL, PRIMARY KEY(`device_id`, `seq`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `edge_sightings` (`device_id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `entity_id` TEXT NOT NULL, `type` INTEGER NOT NULL, `first_ms` INTEGER NOT NULL, `last_ms` INTEGER NOT NULL, `rssi_last` INTEGER NOT NULL, `rssi_max` INTEGER NOT NULL, `rssi_min` INTEGER NOT NULL, `frames` INTEGER NOT NULL, `continues` INTEGER NOT NULL, `covered_live` INTEGER NOT NULL, `loc_kind` TEXT NOT NULL, `loc_source` TEXT, `lat` REAL, `lon` REAL, `radius_m` REAL, `end_lat` REAL, `end_lon` REAL, `path_m` REAL, `coverage` REAL, `altitude_m` REAL, `vertical_accuracy_m` REAL, PRIMARY KEY(`device_id`, `seq`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_edge_sightings_entity_id` ON `edge_sightings` (`entity_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_edge_sightings_last_ms` ON `edge_sightings` (`last_ms`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `location_fixes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at_ms` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `accuracy_m` REAL NOT NULL, `altitude_m` REAL, `vertical_accuracy_m` REAL, `msl_altitude_m` REAL, `speed_mps` REAL, `speed_accuracy_mps` REAL, `provider` TEXT, `satellites_used` INTEGER, `satellites_in_view` INTEGER, `pressure_hpa` REAL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_fixes_at_ms` ON `location_fixes` (`at_ms`)")
        }
    }

    /**
     * v9 (V2, delete after sync): raw copies of Edge records nothing else reads yet, so every
     * received record is stored before the phone confirms it. Generated from schema 9.json.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `edge_kept_records` (`device_id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `type` INTEGER NOT NULL, `boot` INTEGER NOT NULL, `flags` INTEGER NOT NULL, `first_ms` INTEGER NOT NULL, `last_ms` INTEGER NOT NULL, `record_hex` TEXT NOT NULL, `received_at_ms` INTEGER NOT NULL, PRIMARY KEY(`device_id`, `seq`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_edge_kept_records_received_at_ms` ON `edge_kept_records` (`received_at_ms`)")
        }
    }

    /** v10: a placement set on this phone while the Edge was away, sent on the next connect. */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE edge_devices ADD COLUMN placement_pending INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Automatic places (docs/plans/auto-places.md): which places Sotreus made rather than the owner. */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE places ADD COLUMN auto INTEGER NOT NULL DEFAULT 0")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
}
