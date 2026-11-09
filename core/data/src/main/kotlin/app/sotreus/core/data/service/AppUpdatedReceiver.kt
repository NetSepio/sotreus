package app.sotreus.core.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.sotreus.core.data.pipeline.ObservationController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * An update stops Sotreus's Edge service, and companion presence never reports an Edge that stayed
 * in range, so the link would wait until Sotreus is opened. Android exempts this broadcast from the
 * background limits on starting a foreground service; the observation controller decides.
 */
class AppUpdatedReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun observationController(): ObservationController
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).observationController().onAppUpdated()
    }
}
