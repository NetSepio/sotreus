package app.sotreus.edge.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import app.sotreus.edge.protocol.EdgeProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Pairing (HANDOFF_V2_EDGE.md §3.1): Android's Companion Device picker lists devices advertising
 * the Sotreus service (no scan permission needed), then the app bonds and Android asks for the
 * 6-digit code on the Edge's display. Presence observation lets the app hear when the Edge is back.
 */
@Singleton
@SuppressLint("MissingPermission")
class EdgePairing @Inject constructor(@ApplicationContext private val context: Context) {
    private val cdm: CompanionDeviceManager? get() = context.getSystemService(CompanionDeviceManager::class.java)
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun request(): AssociationRequest = AssociationRequest.Builder()
        .addDeviceFilter(
            BluetoothLeDeviceFilter.Builder()
                .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(EdgeProtocol.SERVICE_UUID)).build())
                .build(),
        )
        .setSingleDevice(false)
        .build()

    /**
     * Starts the system picker. [onPicker] gets the IntentSender to launch from the screen
     * (`StartIntentSenderForResult`); [onError] gets a reason when there is nothing to show.
     */
    fun associate(activity: android.app.Activity, onPicker: (IntentSender) -> Unit, onError: (String) -> Unit) {
        // Android 8–12 casts the manager's context to an Activity, so it must come from the screen's Activity.
        val m = activity.getSystemService(CompanionDeviceManager::class.java) ?: return onError("This phone has no companion device support")
        val executor = Executor { it.run() }
        if (Build.VERSION.SDK_INT >= 33) {
            m.associate(request(), executor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = onPicker(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) = Unit
                override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "No Edge found")
            })
        } else {
            @Suppress("DEPRECATION")
            m.associate(request(), object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(chooserLauncher: IntentSender) = onPicker(chooserLauncher)
                override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "No Edge found")
            }, null)
        }
    }

    /**
     * Android 12+ uses the system picker. Android 8–11's picker crashes on some scan results in a
     * crowded place (a platform bug), so there the app scans for the Sotreus service itself.
     */
    val useSystemPicker: Boolean get() = Build.VERSION.SDK_INT >= 31

    data class FoundEdge(val device: BluetoothDevice, val name: String, val rssi: Int)

    /** Edges advertising the Sotreus service (their name shows only while pairing is open). */
    fun scan(): kotlinx.coroutines.flow.Flow<FoundEdge> = kotlinx.coroutines.flow.callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Turn Bluetooth on to find your Edge"))
            return@callbackFlow
        }
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                trySend(FoundEdge(r.device, r.scanRecord?.deviceName?.ifBlank { null } ?: "Sotreus Edge", r.rssi))
            }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth scan failed ($errorCode)"))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(EdgeProtocol.SERVICE_UUID)).build())
        val settings = android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    /** The device the owner picked, from the picker's result intent. */
    fun deviceFrom(result: Intent?): BluetoothDevice? {
        if (result == null) return null
        if (Build.VERSION.SDK_INT >= 34) {
            val info = result.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            info?.associatedDevice?.bleDevice?.device?.let { return it }
            info?.associatedDevice?.bluetoothDevice?.let { return it }
        }
        @Suppress("DEPRECATION")
        return when (val extra = result.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is ScanResult -> extra.device
            is BluetoothDevice -> extra
            else -> null
        }
    }

    fun device(address: String): BluetoothDevice? = runCatching { adapter?.getRemoteDevice(address.uppercase()) }.getOrNull()

    fun isBonded(device: BluetoothDevice): Boolean = device.bondState == BluetoothDevice.BOND_BONDED

    /**
     * Bonds with [device]; Android shows its own prompt for the code on the Edge's display.
     * Returns the bonded device (whose address Android may have resolved), or null if the owner
     * cancelled, the code was wrong, or it timed out.
     */
    suspend fun bond(device: BluetoothDevice, timeoutMs: Long = 120_000): BluetoothDevice? {
        if (isBonded(device)) return device
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        @Suppress("DEPRECATION")
                        val d = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (d?.address != device.address) return
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                            BluetoothDevice.BOND_BONDED -> { runCatching { context.unregisterReceiver(this) }; if (cont.isActive) cont.resume(d) }
                            BluetoothDevice.BOND_NONE -> { runCatching { context.unregisterReceiver(this) }; if (cont.isActive) cont.resume(null) }
                        }
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                if (!device.createBond()) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /** Ask Android to report when this Edge comes into or out of range (API 31+). */
    fun observePresence(address: String) {
        if (Build.VERSION.SDK_INT < 31) return
        @Suppress("DEPRECATION")
        runCatching { cdm?.startObservingDevicePresence(address.uppercase()) }
    }

    /** Forgets the association. The Bluetooth bond itself is removed in Android's settings. */
    fun forget(address: String) {
        val m = cdm ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            m.myAssociations.filter { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }.forEach { m.disassociate(it.id) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { m.disassociate(address.uppercase()) }
        }
    }

    /**
     * Opens a link. [autoConnect] (the default) lets Android reconnect a bonded Edge when it is back
     * in range; first pairing connects directly so the bond can start on the link at once.
     */
    fun open(device: BluetoothDevice, scope: CoroutineScope, autoConnect: Boolean = true): BleGattTransport =
        BleGattTransport(context, device, scope, autoConnect).also { it.connect() }

    /**
     * The address to keep for a bonded Edge. The Edge rotates a private address; after bonding Android
     * lists it under its identity address, so prefer the bonded entry with the Edge's name.
     */
    fun bondedAddressFor(device: BluetoothDevice, suffix: String): String {
        val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        return bonded.firstOrNull { it.address == device.address }?.address
            ?: bonded.firstOrNull { it.name?.endsWith(suffix, ignoreCase = true) == true }?.address
            ?: device.address
    }
}

/** Presence reports from [EdgePresenceService]. */
object EdgePresence {
    data class Event(val address: String, val present: Boolean, val atMs: Long = System.currentTimeMillis())

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    internal fun report(address: String, present: Boolean) {
        _events.tryEmit(Event(address.uppercase(), present))
    }
}
