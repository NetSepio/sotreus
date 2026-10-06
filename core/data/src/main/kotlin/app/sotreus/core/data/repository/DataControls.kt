package app.sotreus.core.data.repository

import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.database.dao.AttentionDao
import app.sotreus.core.database.dao.ContextDao
import app.sotreus.core.database.dao.EncounterDao
import app.sotreus.core.database.dao.EntityDao
import app.sotreus.core.database.dao.ObservationDao
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.model.RetentionPolicy
import javax.inject.Inject
import javax.inject.Singleton

/** Every "Clear data" action on screen 14, plus retention. Each removes exactly what it says. */
@Singleton
class DataControls @Inject constructor(
    private val entities: EntityDao,
    private val observations: ObservationDao,
    private val encounters: EncounterDao,
    private val places: PlaceDao,
    private val sessions: SessionDao,
    private val attention: AttentionDao,
    private val friends: FriendRepository,
    private val proofs: ProofRepository,
    private val pipeline: ObservationPipeline,
    private val context: ContextDao,
) {
    /** A session's observations, encounters, timeline, locations, context and attention events. */
    suspend fun deleteSession(sessionId: Long) {
        context.deleteForSession(sessionId)
        observations.deleteForSession(sessionId)
        encounters.deleteForSession(sessionId)
        attention.deleteForSession(sessionId)
        sessions.deleteEvents(sessionId)
        sessions.deleteEntities(sessionId)
        sessions.deleteLocations(sessionId)
        sessions.delete(sessionId)
    }

    /** A place's visits, baseline, encounters, observations and attention events. The place stays. */
    suspend fun deletePlaceHistory(placeId: Long) {
        observations.deleteForPlace(placeId)
        encounters.deleteForPlace(placeId)
        attention.deleteForPlace(placeId)
        places.deleteVisitEntitiesForPlace(placeId)
        places.deleteVisitsForPlace(placeId)
    }

    suspend fun deletePlace(placeId: Long) {
        deletePlaceHistory(placeId)
        places.delete(placeId)
    }

    suspend fun deletePresenceKeys() = friends.deletePresenceMaterial()

    /** All observations, context records and everything derived from them. Labels, notes and places are kept. */
    suspend fun deleteAllObservations() {
        context.deleteAll()
        observations.deleteAll()
        encounters.deleteAll()
        attention.deleteAll()
        places.deleteAllVisitEntities()
        places.deleteAllVisits()
        sessions.deleteAllEntities()
        sessions.deleteSensedEvents()
        sessions.deleteAllLocations()
        entities.deleteOrphans()
        pipeline.forgetAll()
    }

    /** Removes data produced by the simulated radios. */
    suspend fun deleteSimulated() {
        context.deleteSimulated()
        observations.deleteSimulated()
        encounters.deleteSimulated()
        attention.deleteSimulated()
        places.deleteSimulatedVisitEntities()
        sessions.deleteSimulatedEntities()
        entities.deleteSimulated()
        pipeline.clearSimulated()
    }

    suspend fun clearPendingProofs() = proofs.clearPending()

    /** Applies the retention policy. Returns how many observations were removed. */
    suspend fun applyRetention(policy: RetentionPolicy, now: Long = System.currentTimeMillis()): Int {
        if (policy == RetentionPolicy.KEEP_ALL) return 0
        val cutoff = now - RETENTION_DAYS * 24 * 60 * 60 * 1000L
        val removed = observations.deleteOlderThan(cutoff, keepTagged = policy == RetentionPolicy.KEEP_TAGGED_EXPIRE_REST)
        encounters.deleteOlderThan(cutoff)
        context.deleteOlderThan(cutoff)
        entities.deleteOrphans()
        return removed
    }

    companion object {
        const val RETENTION_DAYS = 30
    }
}
