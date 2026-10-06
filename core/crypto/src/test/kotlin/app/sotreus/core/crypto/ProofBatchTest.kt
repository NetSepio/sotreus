package app.sotreus.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Deterministic vectors. Expected values were produced by an independent Python implementation
 * (hashlib + a hand-written deterministic CBOR encoder) so this checks the Kotlin against a second
 * implementation, not against itself. Change these only with a schema version bump.
 */
class ProofBatchTest {

    private val salts = (1..5).map { i -> ByteArray(32) { i.toByte() } }
    private val records = (0 until 5).map { i -> Cbor.map("i" to Cbor.of(i.toLong())).encode() }
    private val leaves = records.indices.map { ProofBatch.leafHash(salts[it], records[it]) }

    @Test
    fun canonicalCborIsDeterministicAndSorted() {
        val record = Cbor.map(
            "rssi" to Cbor.of(-58),
            "kind" to Cbor.of("ble"),
            "at" to Cbor.of(1_759_700_000_000),
            "id" to Cbor.of("a1"),
            "b" to Cbor.Bool(true),
            "n" to Cbor.Null,
            "raw" to Cbor.Bytes(byteArrayOf(1, 2, 3)),
            "list" to Cbor.Array(listOf(Cbor.of(1), Cbor.of("x"), Cbor.of(-300))),
        )
        assertEquals(
            "a86162f5616ef66261741b00000199b64b1d006269646261316372617743010203646b696e6463626c65646c6973748301617839012b64727373693839",
            record.encode().toHex(),
        )
        // Insertion order must not matter.
        val reordered = Cbor.Map(record.entries.entries.reversed().associate { it.key to it.value })
        assertArrayEquals(record.encode(), reordered.encode())
    }

    @Test
    fun saltedLeavesMatchReference() {
        assertEquals("fefc1c49d5636fe8d6b77606ed81d282362f9fc11cebf42ee8c7013032c3e23a", leaves[0].toHex())
        assertEquals("2f881dbddbc85acd1e62fd4895cb5e15fa918bd5afba9b71ef5b03082f60ce5e", leaves[4].toHex())
    }

    @Test
    fun merkleRootOfOddBatchMatchesReference() {
        assertEquals("5661f0bb56f39748334f456b7ae25f7fd8f7ee9a392c535a159fa17caf9222c7", ProofBatch.merkleRoot(leaves).toHex())
    }

    @Test
    fun commitmentMatchesReference() {
        val root = ProofBatch.merkleRoot(leaves)
        val nonce = ByteArray(32) { 0xAB.toByte() }
        assertEquals(
            "cb25afb200b49be0d689c14167f802b30886709c1284b2b5abb65a045e69753b",
            ProofBatch.commitment(root, 5, nonce).toHex(),
        )
    }

    @Test
    fun everyLeafProvesAgainstTheRootAndTamperingFails() {
        for (n in 1..9) {
            val batch = leaves.take(minOf(n, 5)) + (5 until n).map { Sha256.hash(byteArrayOf(it.toByte())) }
            val root = ProofBatch.merkleRoot(batch)
            batch.indices.forEach { i ->
                val path = ProofBatch.merklePath(batch, i)
                assertTrue("n=$n i=$i", ProofBatch.verify(batch[i], path, root))
                assertFalse("tampered n=$n i=$i", ProofBatch.verify(Sha256.hash(batch[i]), path, root))
            }
        }
    }

    @Test
    fun singleRecordRootIsTheLeaf() {
        assertArrayEquals(leaves[0], ProofBatch.merkleRoot(listOf(leaves[0])))
    }

    @Test
    fun freshSaltsHideIdenticalRecords() {
        val a = ProofBatch.build(listOf(records[0]), SecureRandom())
        val b = ProofBatch.build(listOf(records[0]), SecureRandom())
        assertNotEquals(a.leaves[0].hash.toHex(), b.leaves[0].hash.toHex())
        assertNotEquals(a.commitment.toHex(), b.commitment.toHex())
    }
}
