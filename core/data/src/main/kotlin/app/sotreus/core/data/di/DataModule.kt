package app.sotreus.core.data.di

import app.sotreus.intelligence.SignatureClassifier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-lifetime scope for work that outlives a screen (pipeline, sessions). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The Fieldwatch catalog is large; build its index once. */
    @Provides
    @Singleton
    fun signatureClassifier(): SignatureClassifier = SignatureClassifier()

    @Provides
    @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
