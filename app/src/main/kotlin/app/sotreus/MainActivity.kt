package app.sotreus

import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    @Inject lateinit var languages: app.sotreus.core.data.field.LanguageRepository
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
            val language by languages.state.collectAsState()
            val localized = remember(language.revision) { app.sotreus.core.data.field.languageContext(this, languages) }
            var crash by remember { mutableStateOf(app.sotreus.core.data.field.CrashNotes.pending(this)) }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides localized) {
                SotreusTheme {
                    if (crash != null) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = {},
                            title = { androidx.compose.material3.Text(stringResource(R.string.crash_recovery_title)) },
                            text = { androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                                androidx.compose.material3.Text(stringResource(R.string.crash_recovery_body))
                                androidx.compose.material3.Text(crash.orEmpty())
                            } },
                            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                                app.sotreus.core.data.field.CrashNotes.dismiss(this)
                                crash = null; (application as SotreusApplication).startObservation()
                            }) { androidx.compose.material3.Text(stringResource(R.string.crash_continue)) } },
                            dismissButton = { androidx.compose.material3.TextButton(onClick = {
                                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Sotreus crash", crash))
                            }) { androidx.compose.material3.Text(stringResource(R.string.crash_copy)) } },
                        )
                    } else SotreusApp(settings = settings, pendingInvite = friends.pendingInvite)
                }
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
