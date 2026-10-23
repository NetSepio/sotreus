package app.sotreus

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.sotreus.context.ContextRepository
import app.sotreus.core.data.pipeline.ObservationController
import app.sotreus.core.data.retention.RetentionWorker
import app.sotreus.core.data.service.Notifications
import app.sotreus.intelligence.fieldwatch.RadioDb
import app.sotreus.social.presence.PresenceController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SotreusApplication : Application(), Configuration.Provider {
    @Inject lateinit var build: app.sotreus.core.model.AppBuildInfo
    @Inject lateinit var languages: app.sotreus.core.data.field.LanguageRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var catalog: app.sotreus.core.data.field.SignatureCatalogRepository
    @Inject lateinit var observation: ObservationController
    @Inject lateinit var presence: PresenceController
    @Inject lateinit var contextSources: ContextRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        app.sotreus.core.data.field.CrashNotes.install(this, build)
        if (app.sotreus.core.data.field.CrashNotes.pending(this) == null) startObservation()
    }

    private var observationStarted = false
    @Synchronized fun startObservation() {
        if (observationStarted) return
        observationStarted = true
        // Offline IEEE / Bluetooth SIG lookup tables used by the Fieldwatch explanations.
        RadioDb.init { assets.open("lookups/radiodb.bin") }
        Notifications.ensureChannels(this)
        observation.start()
        presence.start()
        contextSources.start()
        RetentionWorker.schedule(this)
    }
}
