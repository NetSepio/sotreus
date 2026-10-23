package app.sotreus.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Public vendor-prefix information, with no device address or location. */
@Entity(tableName = "vendor_lookups")
data class VendorLookupEntity(
    @PrimaryKey val prefix: String,
    val vendor: String?,
    @ColumnInfo(name = "block_type") val blockType: String?,
    @ColumnInfo(name = "registry_updated") val registryUpdated: String?,
    @ColumnInfo(name = "fetched_at_ms") val fetchedAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
)
