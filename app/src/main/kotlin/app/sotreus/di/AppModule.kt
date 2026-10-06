package app.sotreus.di

import app.sotreus.BuildConfig
import app.sotreus.core.database.SotreusDatabase
import app.sotreus.core.model.AppBuildInfo
import app.sotreus.core.model.Distribution
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** [Distribution] is bound by the flavor source set's `FlavorModule`, not read from BuildConfig. */
    @Provides
    @Singleton
    fun provideAppBuildInfo(distribution: Distribution) = AppBuildInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        applicationId = BuildConfig.APPLICATION_ID,
        buildType = BuildConfig.BUILD_TYPE,
        distribution = distribution,
        databaseSchemaVersion = SotreusDatabase.SCHEMA_VERSION,
    )
}
