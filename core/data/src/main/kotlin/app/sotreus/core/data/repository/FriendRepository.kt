package app.sotreus.core.data.repository

import android.net.Uri
import app.sotreus.core.crypto.Presence
import app.sotreus.core.crypto.hexToBytes
import app.sotreus.core.crypto.toHex
import app.sotreus.core.data.security.SecretBox
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.FriendDao
import app.sotreus.core.database.entity.FriendEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Friends and the app-generated presence identity (handoff §17). Friendship is approved by both
 * people: each scans the other's QR. Nothing here touches an account or wallet.
 */
@Singleton
class FriendRepository @Inject constructor(
    private val friends: FriendDao,
    private val settings: SettingsRepository,
    private val box: SecretBox,
) {
    private val mutex = Mutex()

    val all: Flow<List<FriendEntity>> = friends.observeAll()

    /** An invite link opened from outside the app, waiting for the user to confirm. */
    val pendingInvite = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** This install's presence key pair, created on first use and wrapped by the Keystore. */
    suspend fun presenceKeys(): Presence.KeyPair = mutex.withLock {
        settings.presenceKeyWrapped()?.let { wrapped ->
            runCatching { box.unwrap(wrapped) }.getOrNull()?.let { raw ->
                if (raw.size == 64) return@withLock Presence.KeyPair(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
            }
        }
        Presence.generateKeyPair().also { settings.setPresenceKeyWrapped(box.wrap(it.privateKey + it.publicKey)) }
    }

    /** QR / invite payload: a display name and the presence public key. No account, no wallet. */
    suspend fun myInvite(displayName: String): String =
        Uri.Builder().scheme(SCHEME).authority("friend")
            .appendQueryParameter("v", "1")
            .appendQueryParameter("n", displayName.take(40))
            .appendQueryParameter("k", presenceKeys().publicKey.toHex())
            .build().toString()

    sealed interface AddResult {
        data class Added(val name: String) : AddResult
        data object AlreadyFriends : AddResult
        data object NotAnInvite : AddResult
        data object Yourself : AddResult
    }

    suspend fun addFromInvite(text: String): AddResult {
        val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return AddResult.NotAnInvite
        if (uri.scheme != SCHEME || uri.authority != "friend") return AddResult.NotAnInvite
        val keyHex = uri.getQueryParameter("k")?.lowercase()?.takeIf { it.length == 64 } ?: return AddResult.NotAnInvite
        val name = uri.getQueryParameter("n")?.trim()?.ifBlank { null } ?: return AddResult.NotAnInvite
        val mine = presenceKeys()
        if (keyHex == mine.publicKey.toHex()) return AddResult.Yourself
        if (friends.byKey(keyHex) != null) return AddResult.AlreadyFriends
        val secret = runCatching { Presence.pairwiseSecret(mine.privateKey, mine.publicKey, keyHex.hexToBytes()) }.getOrNull()
            ?: return AddResult.NotAnInvite
        friends.insert(
            FriendEntity(displayName = name, presencePublicKeyHex = keyHex, pairwiseSecretWrapped = box.wrap(secret), addedAtMs = System.currentTimeMillis()),
        )
        return AddResult.Added(name)
    }

    suspend fun pairwiseSecrets(): List<Pair<FriendEntity, ByteArray>> =
        friends.all().mapNotNull { f -> runCatching { f to box.unwrap(f.pairwiseSecretWrapped) }.getOrNull() }

    suspend fun markNearby(id: Long, atMs: Long, place: String?) = friends.markNearby(id, atMs, place)

    suspend fun remove(id: Long) = friends.delete(id)

    /** New presence identity. Existing friends can no longer recognise this phone until re-paired. */
    suspend fun rotatePresenceIdentity() = mutex.withLock {
        val kp = Presence.generateKeyPair()
        settings.setPresenceKeyWrapped(box.wrap(kp.privateKey + kp.publicKey))
    }

    /** "Delete friend-presence keys": friends, their pairwise secrets and this phone's presence key. */
    suspend fun deletePresenceMaterial() {
        friends.deleteAll()
        settings.setPresenceKeyWrapped(null)
        settings.setNearbyPresence(false)
    }

    companion object {
        const val SCHEME = "sotreus"
    }
}
