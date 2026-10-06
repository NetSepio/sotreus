package app.sotreus.core.data.repository

import app.sotreus.core.crypto.Cbor
import app.sotreus.core.crypto.ProofBatch
import app.sotreus.core.crypto.hexToBytes
import app.sotreus.core.crypto.toHex
import app.sotreus.core.database.dao.AttentionDao
import app.sotreus.core.database.dao.ObservationDao
import app.sotreus.core.database.dao.ProofDao
import app.sotreus.core.database.dao.SessionDao
import app.sotreus.core.database.entity.AttentionEventEntity
import app.sotreus.core.database.entity.ObservationEntity
import app.sotreus.core.database.entity.ProofBatchEntity
import app.sotreus.core.database.entity.ProofLeafEntity
import app.sotreus.core.model.ProofState
import app.sotreus.core.model.SolanaCluster
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Builds salted-Merkle proof batches from local records (handoff §15). The records, salts and
 * paths stay in Room; only [ProofBatchEntity.commitmentHex] is ever handed to the chain gateway.
 */
@Singleton
class ProofRepository @Inject constructor(
    private val proofs: ProofDao,
    private val sessions: SessionDao,
    private val observations: ObservationDao,
    private val attention: AttentionDao,
) {
    fun observeBatches(): Flow<List<ProofBatchEntity>> = proofs.observeBatches()

    fun observeBatch(id: Long): Flow<ProofBatchEntity?> = proofs.observeBatch(id)

    suspend fun batch(id: Long) = proofs.batch(id)

    /** Creates a PENDING batch for a session's observations and attention events. */
    suspend fun createForSession(sessionId: Long, cluster: SolanaCluster): Long? {
        val session = sessions.get(sessionId) ?: return null
        val obs = observations.forSession(sessionId)
        val events = attention.forSession(sessionId)
        if (obs.isEmpty() && events.isEmpty()) return null
        val records = obs.map { "observation" to canonical(it) } + events.map { "attention" to canonical(it) }
        val built = ProofBatch.build(records.map { it.second })
        val batchId = proofs.insertBatch(
            ProofBatchEntity(
                sessionId = sessionId,
                title = session.name,
                createdAtMs = System.currentTimeMillis(),
                recordCount = records.size,
                observationCount = obs.size,
                attentionCount = events.size,
                merkleRootHex = built.merkleRoot.toHex(),
                commitmentHex = built.commitment.toHex(),
                nonceHex = built.nonce.toHex(),
                schema = ProofBatch.BATCH_DOMAIN,
                cluster = cluster,
            ),
        )
        val ids = obs.map { it.id } + events.map { it.id }
        val entityIds = obs.map { it.entityId } + events.map { it.entityId }
        proofs.insertLeaves(
            built.leaves.mapIndexed { i, leaf ->
                ProofLeafEntity(
                    batchId = batchId, idx = i, recordType = records[i].first, recordId = ids[i], entityId = entityIds[i],
                    saltHex = leaf.salt.toHex(), leafHashHex = leaf.hash.toHex(), canonicalHex = records[i].second.toHex(),
                )
            },
        )
        return batchId
    }

    suspend fun markSubmitted(id: Long, signature: String, wallet: String) {
        proofs.batch(id)?.let { proofs.updateBatch(it.copy(state = ProofState.SUBMITTED, txSignature = signature, walletAddress = wallet, error = null)) }
    }

    suspend fun markFinalized(id: Long, blockTimeMs: Long?) {
        proofs.batch(id)?.let { proofs.updateBatch(it.copy(state = ProofState.FINALIZED, blockTimeMs = blockTimeMs ?: it.blockTimeMs)) }
    }

    suspend fun markFailed(id: Long, error: String) {
        proofs.batch(id)?.let { proofs.updateBatch(it.copy(state = ProofState.FAILED, error = error.take(200))) }
    }

    suspend fun resetToPending(id: Long) {
        proofs.batch(id)?.let { proofs.updateBatch(it.copy(state = ProofState.PENDING, error = null)) }
    }

    /**
     * "Export a proof": one record with its salt and Merkle path, checkable against the published
     * commitment without revealing any other record in the batch.
     */
    suspend fun exportRecordProof(batchId: Long, index: Int = 0): String? {
        val batch = proofs.batch(batchId) ?: return null
        val leaves = proofs.leaves(batchId)
        val leaf = leaves.getOrNull(index) ?: return null
        val path = ProofBatch.merklePath(leaves.map { it.leafHashHex.hexToBytes() }, index)
        return buildJsonObject {
            put("schema", batch.schema)
            put("schemaVersion", ProofBatch.SCHEMA_VERSION)
            put("cluster", batch.cluster.name.lowercase())
            put("transactionSignature", batch.txSignature)
            put("commitment", batch.commitmentHex)
            put("merkleRoot", batch.merkleRootHex)
            put("recordCount", batch.recordCount)
            put("batchNonce", batch.nonceHex)
            put("recordType", leaf.recordType)
            put("recordCanonicalCbor", leaf.canonicalHex)
            put("salt", leaf.saltHex)
            put("leafHash", leaf.leafHashHex)
            put("leafDomain", ProofBatch.LEAF_DOMAIN)
            put(
                "merklePath",
                JsonArray(path.map { step -> buildJsonObject { put("sibling", step.sibling.toHex()); put("siblingOnLeft", JsonPrimitive(step.siblingOnLeft)) } }),
            )
        }.toString()
    }

    suspend fun clearPending() {
        proofs.deletePendingLeaves()
        proofs.deletePendingBatches()
    }

    companion object {
        /** Canonical observation view. Salted before hashing; never published as-is. */
        fun canonical(o: ObservationEntity): ByteArray = Cbor.map(
            "type" to Cbor.of("observation"),
            "id" to Cbor.of(o.id),
            "entity" to Cbor.of(o.entityId),
            "radio" to Cbor.of(o.radio.name),
            "observed_at_ms" to Cbor.of(o.observedAtMs),
            "rssi" to Cbor.of(o.rssi.toLong()),
            "provenance" to Cbor.of(o.provenance.name),
            "lat_e7" to Cbor.ofNullable(o.lat?.let { (it * 1e7).roundToLong() }),
            "lon_e7" to Cbor.ofNullable(o.lon?.let { (it * 1e7).roundToLong() }),
        ).encode()

        fun canonical(a: AttentionEventEntity): ByteArray = Cbor.map(
            "type" to Cbor.of("attention"),
            "id" to Cbor.of(a.id),
            "entity" to Cbor.of(a.entityId),
            "created_at_ms" to Cbor.of(a.createdAtMs),
            "score_milli" to Cbor.of((a.score * 1000).roundToLong()),
            "headline" to Cbor.of(a.headline.name),
        ).encode()
    }
}
