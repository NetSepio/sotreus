package app.sotreus.integration.solana

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import app.sotreus.core.data.repository.WalletException
import com.solana.mobilewalletadapter.clientlib.associationDetails
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationIntentCreator
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.clientlib.scenario.Scenario
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Activity-result bridge for Mobile Wallet Adapter. [attach] runs from [android.app.Activity.onCreate]
 * so the launcher is registered before the activity is started.
 *
 * The association intent is launched here, on the tap's call stack. clientlib's
 * [com.solana.mobilewalletadapter.clientlib.ActivityResultSender] posts that launch through
 * `lifecycle.whenResumed`, and on targetSdk 37 the system then blocks it as a background start,
 * so the wallet disambiguation dialog never appears.
 */
@Singleton
class WalletActivityBridge @Inject constructor() {
    private var activity: ComponentActivity? = null
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pending: CompletableDeferred<Int>? = null

    /** Package of the wallet picked in the last chooser, reported by the system. */
    @Volatile var chosenPackage: String? = null
        private set
    private var receiverRegistered = false
    private val chosenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val component = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            chosenPackage = component?.packageName
        }
    }

    fun attach(activity: ComponentActivity) {
        this.activity = activity
        pending?.cancel()
        pending = null
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val waiting = pending
            pending = null
            waiting?.complete(result.resultCode)
        }
    }

    /**
     * Opens the association [intent] before the caller suspends. Returns the activity result code.
     * Throws [WalletException] with [WalletException.noWallet] when no installed wallet can handle it.
     */
    fun open(intent: Intent, chooseWallet: Boolean, walletPackage: String? = null): CompletableDeferred<Int> {
        val currentActivity = activity ?: throw WalletException("No activity to open the wallet from")
        val launch = launcher ?: throw WalletException("No activity to open the wallet from")
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw WalletException("The wallet has to be opened from the button tap")
        }
        if (pending?.isActive == true) throw WalletException("A wallet request is already open")
        // Send follow-up requests only to the wallet chosen at sign-in, when it is still installed,
        // so a different default wallet cannot pick up a session it did not authorise.
        if (!chooseWallet && walletPackage != null) {
            val pinned = Intent(intent).setPackage(walletPackage)
            if (pinned.resolveActivity(currentActivity.packageManager) != null) intent.setPackage(walletPackage)
        }
        if (intent.resolveActivity(currentActivity.packageManager) == null) {
            throw WalletException("No compatible wallet found.", noWallet = true)
        }
        val deferred = CompletableDeferred<Int>()
        pending = deferred
        try {
            val request = if (chooseWallet) {
                chosenPackage = null
                Intent.createChooser(intent, currentActivity.getString(R.string.wallet_picker_title), chosenSender(currentActivity))
            } else intent
            launch.launch(request)
        } catch (e: ActivityNotFoundException) {
            if (pending === deferred) pending = null
            throw WalletException("No compatible wallet found.", noWallet = true)
        }
        return deferred
    }

    /** The system reports the picked app to this sender (EXTRA_CHOSEN_COMPONENT). App-private. */
    private fun chosenSender(context: Context): android.content.IntentSender {
        val app = context.applicationContext
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(app, chosenReceiver, IntentFilter(ACTION_WALLET_CHOSEN), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        val callback = Intent(ACTION_WALLET_CHOSEN).setPackage(app.packageName)
        return PendingIntent.getBroadcast(app, 0, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
    }

    private companion object {
        const val ACTION_WALLET_CHOSEN = "app.sotreus.action.WALLET_CHOSEN"
    }
}

/**
 * One local Mobile Wallet Adapter session. The intent is sent before [use] suspends, then the
 * websocket and the caller's wallet requests run off the main thread.
 */
@Singleton
class WalletSession @Inject constructor(private val bridge: WalletActivityBridge) {
    /** The wallet app picked in the most recent chooser, if the system reported it. */
    fun lastChosenPackage(): String? = bridge.chosenPackage

    suspend fun <T> use(walletUriBase: Uri?, walletPackage: String?, chooseWallet: Boolean = false, block: suspend (MobileWalletAdapterClient) -> T): T {
        val scenario = LocalAssociationScenario(Scenario.DEFAULT_CLIENT_TIMEOUT_MS)
        try {
            val intent = associationIntent(scenario, if (chooseWallet) null else walletUriBase)
            // Launch before the first suspend so the system still counts this as the button tap.
            val result = bridge.open(intent, chooseWallet, walletPackage)
            val declined = AtomicBoolean(false)
            return withContext(Dispatchers.IO) {
                val watcher = watchCancellation(this, scenario, result, declined)
                try {
                    val client = scenario.start().get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    block(client)
                } catch (t: Throwable) {
                    if (declined.get()) throw WalletException("The wallet request was declined.", userCancelled = true)
                    throw t
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            runCatching { scenario.close().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
    }

    private fun watchCancellation(
        scope: CoroutineScope,
        scenario: LocalAssociationScenario,
        result: CompletableDeferred<Int>,
        declined: AtomicBoolean,
    ) = scope.launch {
        val code = runCatching { result.await() }.getOrNull() ?: return@launch
        if (code == Activity.RESULT_CANCELED) {
            declined.set(true)
            runCatching { scenario.close().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
    }

    private fun associationIntent(scenario: LocalAssociationScenario, walletUriBase: Uri?): Intent {
        val prefix = walletUriBase?.takeIf { it.scheme == "https" }
        val session = scenario.session ?: throw WalletException("Could not start a wallet session")
        val details = scenario.associationDetails(prefix)
        val intent = LocalAssociationIntentCreator.createAssociationIntent(details.uriPrefix, details.port, session)
        // Match the wallets' DEFAULT filters. Connect uses an explicit chooser so a preferred
        // handler cannot hide the other compatible wallets installed on the device.
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        return intent
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 35L
        const val CLOSE_TIMEOUT_SECONDS = 5L
    }
}
