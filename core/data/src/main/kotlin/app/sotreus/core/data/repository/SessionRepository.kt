package app.sotreus.core.data.repository

import android.content.Context
import app.sotreus.core.data.service.ObservationService
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.AttentionDao
import app.sotreus.core.database.dao.EntityDao
import app.sotreus.core.database.dao.PlaceDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.EntityEntity
import app.sotreus.core.database.entity.SessionEntity
import app.sotreus.core.database.entity.SessionEventEntity
import app.sotreus.core.model.SessionEventKind
import app.sotreus.core.model.SessionKind
import app.sotreus.core.model.StampingMode
import app.sotreus.intelligence.SessionCompare
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class SessionListItem(
    val session: SessionEntity,
    val radios: Int,
    val newToYou: Int,
    val attention: Int,
)

data class SessionLiveView(
    val session: SessionEntity,
    val events: List<SessionEventEntity>,
    val radios: Int,
    val newToYou: Int,
    val attention: Int,
)

data class CompareRow(val entityId: String, val entity: EntityEntity?, val presentMs: Long, val avgRssi: Int, val otherPresentMs: Long? = null)

data class CompareView(
    val a: SessionEntity,
    val b: SessionEntity,
    val both: Int,
    val onlyA: List<CompareRow>,
    val onlyB: List<CompareRow>,
    val changed: List<CompareRow>,
)

/** What to do after a session ends. */
sealed interface SessionEnd {
    data object Summary : SessionEnd
    data class AskToStamp(val sessionId: Long) : SessionEnd
}

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionDao,
    private val attention: AttentionDao,
    private val entities: EntityDao,
    private val places: PlaceDao,
    private val settings: SettingsRepository,
) {
    val active: Flow<SessionEntity?> = sessions.observeActive()

    fun observeList(): Flow<List<SessionListItem>> = combine(
        sessions.observeAll(),
        sessions.observeSummaries(),
        attention.observeSessionCounts(),
    ) { all, summaries, attentionCounts ->
        val s = summaries.associateBy { it.id }
        val a = attentionCounts.associate { it.key to it.count }
        all.map { SessionListItem(it, s[it.id]?.radios ?: 0, s[it.id]?.newToYou ?: 0, a[it.id] ?: 0) }
    }

    fun observeSession(id: Long) = sessions.observe(id)

    fun observeLive(id: Long): Flow<SessionLiveView?> = combine(
        sessions.observe(id),
        sessions.observeEvents(id),
        sessions.observeEntities(id),
        attention.observeCountForSession(id),
    ) { s, events, rows, att ->
        s?.let { SessionLiveView(it, events, rows.size, rows.count { r -> r.newToYou }, att) }
    }

    /** Starts the foreground observation service with a visible notification. */
    /** [label] names the session at a saved place ("Office · morning"); [labelNoPlace] otherwise. */
    suspend fun start(kind: SessionKind, geotag: Boolean, label: String, labelNoPlace: String): Long {
        sessions.active()?.let { return it.id }
        val now = System.currentTimeMillis()
        val placeId = settings.current().currentPlaceId
        val placeName = placeId?.let { places.get(it)?.name }
        val id = sessions.insert(
            SessionEntity(kind = kind, name = placeName?.let { "$it · $label" } ?: labelNoPlace, startedAtMs = now, geotag = geotag, placeId = placeId, startLabel = placeName),
        )
        sessions.insertEvent(SessionEventEntity(sessionId = id, atMs = now, kind = SessionEventKind.STARTED))
        settings.setGeotagNextSession(false)
        ObservationService.start(context, kind, geotag)
        return id
    }

    suspend fun addNote(id: Long, text: String) {
        if (text.isBlank()) return
        sessions.insertEvent(SessionEventEntity(sessionId = id, atMs = System.currentTimeMillis(), kind = SessionEventKind.USER_NOTE, text = text.trim()))
    }

    /**
     * Ends the session. On a Solana Mobile device with "ask at end" stamping, the caller should
     * offer the stamp confirmation (screen S4); otherwise it shows the summary.
     */
    suspend fun end(id: Long, stampingAvailable: Boolean): SessionEnd {
        val now = System.currentTimeMillis()
        val session = sessions.get(id) ?: return SessionEnd.Summary
        sessions.insertEvent(SessionEventEntity(sessionId = id, atMs = now, kind = SessionEventKind.ENDED))
        sessions.end(id, now)
        val mode = settings.current().stampingMode
        val ask = stampingAvailable && (
            (mode == StampingMode.ASK_END_JOURNEY && session.kind == SessionKind.JOURNEY) ||
                (mode == StampingMode.ASK_END_SIT && session.kind == SessionKind.SIT)
            )
        return if (ask) SessionEnd.AskToStamp(id) else SessionEnd.Summary
    }

    suspend fun compare(aId: Long, bId: Long): CompareView? {
        val a = sessions.get(aId) ?: return null
        val b = sessions.get(bId) ?: return null
        fun rows(list: List<app.sotreus.core.database.entity.SessionEntityEntity>) = list.map {
            SessionCompare.Radio(it.entityId, it.presentMs.coerceAtLeast(it.lastSeenMs - it.firstSeenMs), if (it.rssiCount > 0) (it.rssiSum / it.rssiCount).toInt() else -100)
        }
        val result = SessionCompare.compare(rows(sessions.entities(aId)), rows(sessions.entities(bId)))
        val ids = (result.onlyA.map { it.entityId } + result.onlyB.map { it.entityId } + result.changed.map { it.a.entityId }).distinct()
        val names = ids.chunked(500).flatMap { entities.getAll(it) }.associateBy { it.id }
        return CompareView(
            a = a,
            b = b,
            both = result.both.size,
            onlyA = result.onlyA.map { CompareRow(it.entityId, names[it.entityId], it.presentMs, it.avgRssi) },
            onlyB = result.onlyB.map { CompareRow(it.entityId, names[it.entityId], it.presentMs, it.avgRssi) },
            changed = result.changed.map { CompareRow(it.b.entityId, names[it.b.entityId], it.b.presentMs, it.b.avgRssi, it.a.presentMs) },
        )
    }

    suspend fun sits(): List<SessionEntity> = sessions.observeAll().first().filter { it.kind == SessionKind.SIT && it.endedAtMs != null }

    suspend fun all(): List<SessionEntity> = sessions.observeAll().first()
}
