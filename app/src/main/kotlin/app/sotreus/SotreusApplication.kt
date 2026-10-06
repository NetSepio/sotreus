package app.sotreus

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.sotreus.core.data.pipeline.ObservationController
import app.sotreus.core.data.retention.RetentionWorker
import app.sotreus.core.data.service.Notifications
import app.sotreus.intelligence.fieldwatch.RadioDb
import app.sotreus.social.presence.PresenceController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SotreusApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var observation: ObservationController
    @Inject lateinit var presence: PresenceController

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Offline IEEE / Bluetooth SIG lookup tables used by the Fieldwatch explanations.
        RadioDb.init { assets.open("lookups/radiodb.bin") }
        Notifications.ensureChannels(this)
        observation.start()
        presence.start()
        RetentionWorker.schedule(this)
    }
}
