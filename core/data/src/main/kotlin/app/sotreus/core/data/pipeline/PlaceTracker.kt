package app.sotreus.core.data.pipeline

import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.entity.PlaceVisitEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Place visits. A visit is the time the phone observes while a place is selected. Reopening the
 * app, or coming back to the place, within 30 minutes continues the same visit, so the baseline
 * counts real visits, not app opens or a short step outside the place's radius.
 */
@Singleton
class PlaceTracker @Inject constructor(private val places: PlaceDao) {
    private val mutex = Mutex()
    private var placeId: Long? = null
    private var visitId: Long? = null
    private var lastActivityMs = 0L
    private var repaired = false

    data class Baseline(val completedVisits: Int, val visitsByEntity: Map<String, Int>)

    /** What to do when no visit is held: at a start, or after Sotreus was stopped. */
    sealed interface Resume {
        data class Continue(val visitId: Long) : Resume
        data class Reopen(val visitId: Long) : Resume

        /** A new visit; [endStale] (visit id to end time) is an open visit an earlier run left. */
        data class New(val endStale: Pair<Long, Long>? = null) : Resume
    }

    val currentVisitId: Long? get() = visitId

    /** Makes sure a visit is open for [newPlaceId] (or none for an unsaved place). */
    suspend fun ensure(newPlaceId: Long?, now: Long): Long? = mutex.withLock {
        if (!repaired) {
            places.repairVisitEnds()
            repaired = true
        }
        if (newPlaceId != placeId) {
            closeLocked(now)
            placeId = newPlaceId
        }
        val pid = placeId ?: return@withLock null
        if (visitId == null) {
            val open = places.openVisit(pid)
            val lastSeen = open?.let { places.visitLastSeen(it.id) }
            visitId = when (val r = resume(open, lastSeen, if (open == null) places.latestVisit(pid) else null, now)) {
                is Resume.Continue -> r.visitId
                is Resume.Reopen -> r.visitId.also { places.reopenVisit(it) }
                is Resume.New -> {
                    r.endStale?.let { (id, endAt) -> places.endVisit(id, endAt) }
                    places.insertVisit(PlaceVisitEntity(placeId = pid, startedAtMs = now))
                }
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

        /**
         * With no visit held, an open visit can only be one an earlier run left (this run ends every
         * visit it stops holding). Its last activity is its latest radio, kept in the store, not
         * this run's memory. It continues if that was under [CONTINUE_MS] ago, else it ends then (at
         * its start if it holds no radio). With no open visit, the latest one reopens if it ended
         * under [CONTINUE_MS] ago.
         */
        fun resume(open: PlaceVisitEntity?, openLastSeenMs: Long?, latest: PlaceVisitEntity?, now: Long): Resume {
            if (open != null) {
                val last = maxOf(open.startedAtMs, openLastSeenMs ?: open.startedAtMs)
                return if (now - last < CONTINUE_MS) Resume.Continue(open.id) else Resume.New(open.id to last)
            }
            val ended = latest?.endedAtMs
            return if (latest != null && ended != null && now - ended < CONTINUE_MS) Resume.Reopen(latest.id) else Resume.New()
        }

        /**
         * The place a live visit is held for: the selected place when this phone confirms it (picked
         * by hand, or a fresh fix inside it), or when a positioned stationary Edge sits in it, which
         * needs no fix from this phone.
         */
        fun heldPlace(selectedId: Long?, phoneConfirms: Boolean, edgeFixedPlaceId: Long?, edgeFixedNow: Boolean): Long? = when {
            selectedId == null -> null
            phoneConfirms -> selectedId
            edgeFixedNow && selectedId == edgeFixedPlaceId -> selectedId
            else -> null
        }
    }
}
