package app.sotreus.flavor

import app.sotreus.core.model.Distribution
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Distribution binding for this build profile (store, signing key, artifact). Which auth and
 * settings screens appear is decided at runtime from the device, not from the flavor.
 */
@Module
@InstallIn(SingletonComponent::class)
object FlavorModule {
    @Provides
    fun provideDistribution(): Distribution = Distribution.GENERIC
}
