package app.sotreus.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.sotreus.core.database.entity.VendorLookupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VendorLookupDao {
    @Query("SELECT * FROM vendor_lookups WHERE prefix = :prefix")
    fun observe(prefix: String): Flow<VendorLookupEntity?>

    @Query("SELECT * FROM vendor_lookups WHERE prefix = :prefix")
    suspend fun get(prefix: String): VendorLookupEntity?

    @Upsert
    suspend fun upsert(value: VendorLookupEntity)

    @Query("DELETE FROM vendor_lookups")
    suspend fun deleteAll()

    @Query("DELETE FROM vendor_lookups WHERE expires_at_ms < :now")
    suspend fun deleteExpired(now: Long)
}
