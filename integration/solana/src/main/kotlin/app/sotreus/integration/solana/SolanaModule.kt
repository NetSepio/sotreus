package app.sotreus.integration.solana

import app.sotreus.core.data.repository.SolanaGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SolanaModule {
    @Binds
    abstract fun gateway(impl: MwaSolanaGateway): SolanaGateway
}
