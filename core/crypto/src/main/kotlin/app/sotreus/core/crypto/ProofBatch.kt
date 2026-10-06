package app.sotreus.core.crypto

import java.security.SecureRandom

/**
 * Salted-leaf Merkle batches for proof stamping (architecture handoff §15).
 *
 *     leaf       = SHA256("SOTREUS_OBSERVATION_V1" || salt32 || canonicalRecord)
 *     node       = SHA256(0x01 || left || right)        (an odd node is carried up unchanged)
 *     commitment = SHA256("SOTREUS_BATCH_V1" || u32(schema) || root || u64(count) || nonce32)
 *
 * Salts and nonces are random and stay on the phone; only the 32-byte commitment is published.
 * An unsalted hash of an address or SSID could be guessed back, which is why every leaf is salted.
 */
object ProofBatch {
    const val LEAF_DOMAIN = "SOTREUS_OBSERVATION_V1"
    const val BATCH_DOMAIN = "SOTREUS_BATCH_V1"
    const val SCHEMA_VERSION = 1
    const val SALT_BYTES = 32
    private const val NODE_PREFIX: Byte = 0x01

    data class Leaf(val salt: ByteArray, val hash: ByteArray)

    data class Batch(
        val leaves: List<Leaf>,
        val merkleRoot: ByteArray,
        val nonce: ByteArray,
        val commitment: ByteArray,
    )

    /** One step of a Merkle path: the sibling hash and whether it sits on the left. */
    data class PathStep(val sibling: ByteArray, val siblingOnLeft: Boolean)

    fun leafHash(salt: ByteArray, canonicalRecord: ByteArray): ByteArray {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        return Sha256.hash(ascii(LEAF_DOMAIN), salt, canonicalRecord)
    }

    fun merkleRoot(leafHashes: List<ByteArray>): ByteArray {
        require(leafHashes.isNotEmpty()) { "a batch needs at least one record" }
        var level = leafHashes
        while (level.size > 1) level = parentLevel(level)
        return level.single()
    }

    fun merklePath(leafHashes: List<ByteArray>, index: Int): List<PathStep> {
        require(index in leafHashes.indices)
        val steps = mutableListOf<PathStep>()
        var level = leafHashes
        var i = index
        while (level.size > 1) {
            val sibling = if (i % 2 == 0) i + 1 else i - 1
            if (sibling < level.size) steps += PathStep(level[sibling], siblingOnLeft = sibling < i)
            level = parentLevel(level)
            i /= 2
        }
        return steps
    }

    fun verify(leafHash: ByteArray, path: List<PathStep>, root: ByteArray): Boolean {
        var acc = leafHash
        path.forEach { step ->
            acc = if (step.siblingOnLeft) node(step.sibling, acc) else node(acc, step.sibling)
        }
        return acc.contentEquals(root)
    }

    fun commitment(root: ByteArray, recordCount: Long, nonce: ByteArray, schemaVersion: Int = SCHEMA_VERSION): ByteArray {
        require(root.size == 32 && nonce.size == 32)
        return Sha256.hash(ascii(BATCH_DOMAIN), u32be(schemaVersion), root, u64be(recordCount), nonce)
    }

    /** Builds a batch with fresh random salts and nonce. [records] are canonical record bytes. */
    fun build(records: List<ByteArray>, random: SecureRandom = SecureRandom()): Batch {
        val leaves = records.map { record ->
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            Leaf(salt, leafHash(salt, record))
        }
        val root = merkleRoot(leaves.map { it.hash })
        val nonce = ByteArray(32).also(random::nextBytes)
        return Batch(leaves, root, nonce, commitment(root, records.size.toLong(), nonce))
    }

    private fun node(left: ByteArray, right: ByteArray) = Sha256.hash(byteArrayOf(NODE_PREFIX), left, right)

    private fun parentLevel(level: List<ByteArray>): List<ByteArray> =
        level.chunked(2).map { pair -> if (pair.size == 2) node(pair[0], pair[1]) else pair[0] }
}
