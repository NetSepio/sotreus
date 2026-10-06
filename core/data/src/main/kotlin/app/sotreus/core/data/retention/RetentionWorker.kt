package app.sotreus.core.data.retention

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sotreus.core.data.repository.DataControls
import app.sotreus.core.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Daily local retention job. Deferred work only; it never scans or uses the network. */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val controls: DataControls,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        controls.applyRetention(settings.current().retention)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "retention",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
