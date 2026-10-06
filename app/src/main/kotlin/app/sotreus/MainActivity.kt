package app.sotreus

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.sotreus.core.data.repository.FriendRepository
import app.sotreus.core.data.settings.SettingsRepository
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.integration.solana.WalletActivityBridge
import app.sotreus.navigation.SotreusApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var walletBridge: WalletActivityBridge
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var friends: FriendRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark-only app: light system-bar icons on transparent bars, regardless of system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Mobile Wallet Adapter needs its activity-result bridge registered before onStart.
        walletBridge.attach(this)
        handleInvite(intent)
        setContent {
            SotreusTheme {
                SotreusApp(settings = settings, pendingInvite = friends.pendingInvite)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent)
    }

    /** A friend invite link (sotreus://friend?...) waits for the user to confirm on Friends. */
    private fun handleInvite(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == FriendRepository.SCHEME && data.host == "friend") friends.pendingInvite.value = data.toString()
    }
}
