package app.sotreus.core.data.repository

import app.sotreus.core.data.pipeline.ObservationPipeline
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.AttentionDao
import app.sotreus.core.database.dao.EncounterDao
import app.sotreus.core.database.dao.EncounterRow
import app.sotreus.core.database.dao.EntityDao
import app.sotreus.core.database.dao.ObservationDao
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.dao.PlaceEncounterSummary
import app.sotreus.core.database.dao.ProofDao
import app.sotreus.core.database.entity.AttentionEventEntity
import app.sotreus.core.database.entity.EntityEntity
import app.sotreus.core.database.entity.ObservationEntity
import app.sotreus.core.database.entity.PlaceEntity
import app.sotreus.core.database.entity.ProofBatchEntity
import app.sotreus.core.model.AttentionHeadline
import app.sotreus.core.model.AttentionInputs
import app.sotreus.core.model.AttentionReason
import app.sotreus.core.model.UserEntityState
import app.sotreus.intelligence.Baseline
import app.sotreus.intelligence.fieldwatch.Sighting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class EntityDetail(
    val entity: EntityEntity,
    val encounterCount: Int,
    val byPlace: List<PlaceEncounterSummary>,
    val observationCount: Int,
    val proofBatches: List<ProofBatchEntity>,
    val attention: List<AttentionEventEntity>,
)

data class HistoryEntity(
    val entity: EntityEntity,
    val encounters: Int,
    val places: List<String>,
)

@Singleton
class EntityRepository @Inject constructor(
    private val entities: EntityDao,
    private val observations: ObservationDao,
    private val encounters: EncounterDao,
    private val attention: AttentionDao,
    private val proofs: ProofDao,
    private val pipeline: ObservationPipeline,
) {
    fun observeDetail(id: String): Flow<EntityDetail?> = combine(
        entities.observe(id),
        encounters.observeCount(id),
        encounters.observeByPlace(id),
        observations.observeCountForEntity(id),
        combine(proofs.observeBatchesForEntity(id), attention.observeForEntity(id)) { p, a -> p to a },
    ) { e, count, byPlace, obs, (batches, events) ->
        e?.let { EntityDetail(it, count, byPlace, obs, batches, events) }
    }

    fun observeEvidence(id: String, limit: Int = 500): Flow<List<ObservationEntity>> = observations.observeForEntity(id, limit)

    fun liveSighting(id: String): Sighting? = pipeline.sighting(id)

    fun observeHistory(): Flow<List<HistoryEntity>> = combine(
        entities.observeAll(),
        encounters.observeCounts(),
        encounters.observePlaceNames(),
    ) { all, counts, names ->
        val countMap = counts.associate { it.entityId to it.count }
        val placeMap = names.groupBy({ it.entityId }, { it.name })
        all.map { HistoryEntity(it, countMap[it.id] ?: 0, placeMap[it.id].orEmpty().distinct()) }
    }

    fun observeEncounters(limit: Int = 300): Flow<List<EncounterRow>> = encounters.observeRecent(limit)

    suspend fun setLabel(id: String, state: UserEntityState) {
        entities.setUserState(id, state)
        if (state == UserEntityState.MINE || state == UserEntityState.EXPECTED || state == UserEntityState.IGNORE) {
            attention.resolveForEntity(id)
        }
    }

    suspend fun rename(id: String, name: String?) = entities.setUserName(id, name?.trim()?.ifBlank { null })

    suspend fun setNote(id: String, note: String?) = entities.setNote(id, note?.trim()?.ifBlank { null })

    suspend fun get(id: String) = entities.get(id)
}

data class AttentionDetail(
    val event: AttentionEventEntity,
    val entity: EntityEntity?,
    val reasons: List<AttentionReason>,
    val inputs: AttentionInputs,
    val evidence: List<EvidenceRow>,
    val evidenceTotal: Int,
    val placeName: String?,
)

/** An observation with the phone's place at the time of observation. */
data class EvidenceRow(val observation: ObservationEntity, val placeName: String?)

data class AttentionListItem(
    val event: AttentionEventEntity,
    val entity: EntityEntity?,
    val headline: AttentionHeadline,
)

@Singleton
class AttentionRepository @Inject constructor(
    private val attention: AttentionDao,
    private val entities: EntityDao,
    private val observations: ObservationDao,
    private val places: PlaceDao,
) {
    fun observeOpen(): Flow<List<AttentionListItem>> = attention.observeOpen().map(::withEntities)

    fun observeAll(): Flow<List<AttentionListItem>> = attention.observeAll().map(::withEntities)

    private suspend fun withEntities(list: List<AttentionEventEntity>): List<AttentionListItem> {
        val rows = entities.getAll(list.map { it.entityId }.distinct()).associateBy { it.id }
        return list.map { AttentionListItem(it, rows[it.entityId], it.headline) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDetail(id: Long): Flow<AttentionDetail?> = attention.observe(id).flatMapLatest { event ->
        if (event == null) return@flatMapLatest flowOf(null)
        combine(entities.observe(event.entityId), observations.observeCountForEntity(event.entityId), places.observeAll()) { e, total, allPlaces ->
            val names = allPlaces.associate { it.id to it.name }
            val recent = observations.recentForEntity(event.entityId, 3)
            AttentionDetail(
                event = event,
                entity = e,
                reasons = AttentionCodec.decodeReasons(event.reasonsJson),
                inputs = AttentionCodec.decodeInputs(event.inputsJson),
                evidence = recent.map { EvidenceRow(it, it.placeId?.let(names::get)) },
                evidenceTotal = total,
                placeName = event.placeId?.let(names::get),
            )
        }
    }
}

data class PlaceSummary(val place: PlaceEntity, val visits: Int)

/** Screen 09: what this place's baseline says about the latest visit. */
data class PlaceBaselineView(
    val place: PlaceEntity,
    val completedVisits: Int,
    val normal: List<BaselineEntry>,
    val occasional: Int,
    val newThisVisit: List<BaselineEntry>,
    val missing: List<BaselineEntry>,
    val thisVisitNormal: Int,
    val thisVisitOccasional: Int,
)

data class BaselineEntry(val entity: EntityEntity?, val entityId: String, val visits: Int)

@Singleton
class PlaceRepository @Inject constructor(
    private val places: PlaceDao,
    private val entities: EntityDao,
    private val settings: SettingsRepository,
) {
    fun observePlaces(): Flow<List<PlaceSummary>> = combine(places.observeAll(), places.observeVisitCounts()) { all, counts ->
        val m = counts.associate { it.key to it.count }
        all.map { PlaceSummary(it, m[it.id] ?: 0) }
    }

    fun observePlace(id: Long) = places.observe(id)

    suspend fun create(name: String, select: Boolean = true): Long {
        val now = System.currentTimeMillis()
        val id = places.insert(PlaceEntity(name = name.trim(), createdAtMs = now, updatedAtMs = now))
        if (select) settings.setCurrentPlace(id)
        return id
    }

    suspend fun select(id: Long?) = settings.setCurrentPlace(id)

    suspend fun rename(id: Long, name: String) {
        places.get(id)?.let { places.update(it.copy(name = name.trim(), updatedAtMs = System.currentTimeMillis())) }
    }

    /** Saves the place's location. Coordinates stay on this phone. */
    suspend fun setLocation(id: Long, lat: Double, lon: Double, radiusM: Int? = null) {
        places.get(id)?.let { places.update(it.copy(lat = lat, lon = lon, radiusM = radiusM ?: it.radiusM ?: DEFAULT_RADIUS_M, updatedAtMs = System.currentTimeMillis())) }
    }

    suspend fun clearLocation(id: Long) {
        places.get(id)?.let { places.update(it.copy(lat = null, lon = null, radiusM = null)) }
    }

    suspend fun createWithLocation(name: String, lat: Double?, lon: Double?, select: Boolean = true): Long {
        val id = create(name, select)
        if (lat != null && lon != null) setLocation(id, lat, lon)
        return id
    }

    suspend fun setKeepLearning(id: Long, keep: Boolean) {
        places.get(id)?.let { places.update(it.copy(keepLearning = keep)) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeBaseline(placeId: Long): Flow<PlaceBaselineView?> = combine(
        places.observe(placeId),
        places.observeBaselineCounts(placeId),
        places.observeCompletedVisitCount(placeId),
        places.observeLatestVisit(placeId).flatMapLatest { v -> v?.let { places.observeVisitEntityIds(it.id) } ?: flowOf(emptyList()) },
    ) { place, counts, completed, latest -> Quad(place, counts, completed, latest) }
        .map { (place, counts, completed, latestIds) ->
            place ?: return@map null
            val byEntity = counts.associate { it.entityId to it.visits }
            val ids = (byEntity.keys + latestIds).toList()
            val rows = ids.chunked(500).flatMap { entities.getAll(it) }.associateBy { it.id }
            fun entry(id: String) = BaselineEntry(rows[id], id, byEntity[id] ?: 0)
            val standing = byEntity.mapValues { (_, v) -> Baseline.standing(v, completed) }
            val normalIds = standing.filterValues { it == Baseline.Standing.NORMAL }.keys
            val latest = latestIds.toSet()
            PlaceBaselineView(
                place = place,
                completedVisits = completed,
                normal = normalIds.map(::entry).sortedByDescending { it.visits },
                occasional = standing.count { it.value == Baseline.Standing.OCCASIONAL },
                newThisVisit = latest.filter { (byEntity[it] ?: 0) == 0 }.map(::entry)
                    .sortedByDescending { (it.entity?.notable == true) || it.entity?.family?.attentionRelevant == true },
                missing = Baseline.missing(normalIds, latest).map(::entry).sortedByDescending { it.visits },
                thisVisitNormal = latest.count { it in normalIds },
                thisVisitOccasional = latest.count { standing[it] == Baseline.Standing.OCCASIONAL || standing[it] == Baseline.Standing.RARE },
            )
        }

    suspend fun currentPlace(): PlaceEntity? = settings.settings.first().currentPlaceId?.let { places.get(it) }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        const val DEFAULT_RADIUS_M = 150
    }
}
