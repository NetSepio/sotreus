package app.sotreus.core.database

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/** One database transaction for writes that must land together or not at all. */
@Singleton
class DatabaseTransactions @Inject constructor(private val db: SotreusDatabase) {
    suspend fun <T> run(block: suspend () -> T): T = db.withTransaction(block)
}
