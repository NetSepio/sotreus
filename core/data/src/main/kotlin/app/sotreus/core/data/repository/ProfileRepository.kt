package app.sotreus.core.data.repository

import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.database.dao.ProfileDao
import app.sotreus.core.database.entity.LinkedIdentityEntity
import app.sotreus.core.database.entity.LocalProfileEntity
import app.sotreus.core.model.LinkedIdentityKind
import app.sotreus.core.model.SolanaCluster
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The local person profile (handoff §6). It exists before, and independently of, any wallet. A
 * Solana wallet can be linked on Solana Mobile devices; unlinking keeps every local record.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val profiles: ProfileDao,
    private val settings: SettingsRepository,
) {
    val profile: Flow<LocalProfileEntity?> = profiles.observe()

    val wallet: Flow<LinkedIdentityEntity?> = profiles.observeIdentities().map { list ->
        list.firstOrNull { it.kind == LinkedIdentityKind.SOLANA_WALLET }
    }

    /** "Start local-only": create the profile and finish onboarding. */
    suspend fun startLocalOnly() {
        ensureProfile()
        settings.setOnboardingDone()
    }

    suspend fun ensureProfile(): LocalProfileEntity = profiles.get() ?: LocalProfileEntity(
        id = UUID.randomUUID().toString(),
        displayName = null,
        avatarLocalUri = null,
        createdAtUtcMs = System.currentTimeMillis(),
    ).also { profiles.upsert(it) }

    suspend fun update(displayName: String?, handle: String?) {
        val p = ensureProfile()
        profiles.upsert(p.copy(displayName = displayName?.trim()?.ifBlank { null }, handle = handle?.trim()?.removePrefix("@")?.ifBlank { null }))
    }

    suspend fun linkWallet(publicKey: String, cluster: SolanaCluster, walletLabel: String?) {
        ensureProfile()
        profiles.deleteWallets()
        profiles.insertIdentity(
            LinkedIdentityEntity(
                kind = LinkedIdentityKind.SOLANA_WALLET, publicKey = publicKey, cluster = cluster, walletLabel = walletLabel,
                signedIn = true, createdAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Disconnecting keeps every local observation and proof receipt. */
    suspend fun unlinkWallet() {
        profiles.deleteWallets()
        settings.setWalletAuthTokenWrapped(null)
    }
}
