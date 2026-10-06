package app.sotreus.core.data.pipeline

import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.entity.PlaceVisitEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Place visits. A visit is the time the phone observes while a place is selected. Reopening the
 * app within 30 minutes continues the same visit, so the baseline counts real visits, not app opens.
 */
@Singleton
class PlaceTracker @Inject constructor(private val places: PlaceDao) {
    private val mutex = Mutex()
    private var placeId: Long? = null
    private var visitId: Long? = null
    private var lastActivityMs = 0L

    data class Baseline(val completedVisits: Int, val visitsByEntity: Map<String, Int>)

    val currentVisitId: Long? get() = visitId

    /** Makes sure a visit is open for [newPlaceId] (or none for an unsaved place). */
    suspend fun ensure(newPlaceId: Long?, now: Long): Long? = mutex.withLock {
        if (newPlaceId != placeId) {
            closeLocked(now)
            placeId = newPlaceId
        }
        val pid = placeId ?: return@withLock null
        if (visitId == null) {
            val open = places.openVisit(pid)
            visitId = if (open != null && now - maxOf(lastActivityMs, open.startedAtMs) < CONTINUE_MS) {
                open.id
            } else {
                open?.let { places.endVisit(it.id, maxOf(lastActivityMs, it.startedAtMs)) }
                places.insertVisit(PlaceVisitEntity(placeId = pid, startedAtMs = now))
            }
        }
        lastActivityMs = now
        visitId
    }

    fun touch(now: Long) {
        lastActivityMs = now
    }

    suspend fun close(now: Long) = mutex.withLock { closeLocked(now) }

    private suspend fun closeLocked(now: Long) {
        visitId?.let { places.endVisit(it, if (lastActivityMs > 0) lastActivityMs else now) }
        visitId = null
    }

    suspend fun baseline(placeId: Long): Baseline = Baseline(
        completedVisits = places.completedVisitCount(placeId),
        visitsByEntity = places.baselineCounts(placeId).associate { it.entityId to it.visits },
    )

    companion object {
        const val CONTINUE_MS = 30 * 60 * 1000L
    }
}
