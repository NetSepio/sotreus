package app.sotreus.edge.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import app.sotreus.edge.link.ByteTransport
import app.sotreus.edge.link.EdgeLinkState
import app.sotreus.edge.link.EdgeRequestException
import app.sotreus.edge.protocol.EdgeProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Bluetooth GATT link to one bonded Edge (protocol §1). Every characteristic needs an encrypted,
 * MITM-authenticated link, so the device must be bonded first ([EdgePairing.bond]). The Edge
 * rotates its private address; Android resolves it through the bond, and [autoConnect] lets the
 * stack reconnect when the Edge comes back into range.
 *
 * GATT allows one operation in flight: every operation goes through [op], which waits for its
 * callback. Notifications from `events` and `records` are forwarded as raw chunks.
 */
@SuppressLint("MissingPermission")
class BleGattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val autoConnect: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) : ByteTransport {
    private val _state = MutableStateFlow<EdgeLinkState>(EdgeLinkState.Idle)
    override val state: StateFlow<EdgeLinkState> = _state.asStateFlow()
    private val eventCh = Channel<ByteArray>(Channel.UNLIMITED)
    private val recordCh = Channel<ByteArray>(Channel.UNLIMITED)
    override val eventChunks: Flow<ByteArray> = eventCh.receiveAsFlow()
    override val recordChunks: Flow<ByteArray> = recordCh.receiveAsFlow()

    @Volatile private var mtu = 23
    override val maxWrite: Int get() = mtu - EdgeProtocol.ATT_OVERHEAD

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var control: BluetoothGattCharacteristic? = null
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    @Volatile private var closed = false
    private val ops = Mutex()
    private var setupJob: Job? = null
    private var rearmJob: Job? = null

    /** A paired Edge whose bond this phone no longer has: stop reconnecting, ask to pair again. */
    @Volatile private var pairingLost = false
    private var bondWatch: android.content.BroadcastReceiver? = null

    fun connect() {
        closed = false
        // The long-lived link (autoConnect) is for an Edge paired before. Without the bond Android
        // cannot resolve the Edge's private address, so reconnecting would wait forever.
        if (autoConnect && device.bondState != BluetoothDevice.BOND_BONDED) {
            lostPairing()
            return
        }
        if (autoConnect) watchBond()
        _state.value = EdgeLinkState.Connecting
        gatt = device.connectGatt(context, autoConnect && !linkHeld(), callback, BluetoothDevice.TRANSPORT_LE)
            ?: run { _state.value = EdgeLinkState.Failed("Bluetooth is not available"); null }
    }

    /**
     * Android already holds a radio link to the Edge, e.g. the pairing link this one replaces. A
     * connect then joins that link directly. Auto-connect on a link that is already up leaves the
     * Edge in the phone's accept list: seen on Android 16, the phone then opened and dropped a
     * second link to the Edge every 300 ms. A later drop re-arms with auto-connect as usual.
     */
    private fun linkHeld(): Boolean {
        val held = runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
        if (held) Log.i(TAG, "joining the Edge link Android already holds")
        return held
    }

    override suspend fun write(chunk: ByteArray) {
        val g = gatt ?: throw EdgeRequestException("not connected")
        val c = control ?: throw EdgeRequestException("not connected")
        val status = op<Int> {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    c.value = chunk
                    g.writeCharacteristic(c)
                }
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw EdgeRequestException("write failed ($status)")
    }

    override suspend fun readRssi(): Int? {
        val g = gatt ?: return null
        if (_state.value !is EdgeLinkState.Connected) return null
        return runCatching { op<Int>(timeoutMs = 3_000) { g.readRemoteRssi() } }.getOrNull()
    }

    private fun lostPairing() {
        pairingLost = true
        rearmJob?.cancel()
        _state.value = EdgeLinkState.Failed(PAIRING_LOST, pairingLost = true)
    }

    /** Android can drop the bond at any time (seen after one failed encrypted reconnect on Android 9). */
    private fun watchBond() {
        if (bondWatch != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: android.content.Intent) {
                @Suppress("DEPRECATION")
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device.address) return
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) != BluetoothDevice.BOND_NONE) return
                Log.w(TAG, "this phone dropped its bond with the Edge")
                lostPairing()
                gatt?.let { runCatching { it.disconnect() } }
            }
        }
        bondWatch = receiver
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override suspend fun close() {
        bondWatch?.let { runCatching { context.unregisterReceiver(it) } }
        bondWatch = null
        closed = true
        setupJob?.cancel()
        rearmJob?.cancel()
        pending?.completeExceptionally(EdgeRequestException("closed"))
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        control = null
        _state.value = EdgeLinkState.Idle
    }

    /** Reconnect attempts since the link was last up. */
    @Volatile private var attempts = 0

    /**
     * After a drop: a fresh auto-connect at once, while the Edge advertises fast (100–150 ms for 30 s
     * after a disconnect), then again every [REARM_MS]. Some stacks (Android 9 on a Mate 9) never
     * complete a pending auto-connect by themselves. No direct connect: the Edge uses a rotating
     * private address, which only auto-connect resolves through the bond, so a direct connect goes to
     * an address the Edge has already left (it fails with 133 after 30 s).
     */
    private fun rearm() {
        rearmJob?.cancel()
        attempts++
        rearmJob = scope.launch {
            kotlinx.coroutines.delay(REOPEN_DELAY_MS)
            if (closed || linkUp) return@launch
            reopen(auto = true)
            while (!closed && !linkUp) {
                kotlinx.coroutines.delay(REARM_MS)
                if (closed || linkUp) break
                reopen(auto = true)
            }
        }
    }

    private suspend fun reopen(auto: Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        if (closed || linkUp || pairingLost) return@withContext
        gatt?.let { runCatching { it.close() } }
        gatt = device.connectGatt(context, auto && !linkHeld(), callback, BluetoothDevice.TRANSPORT_LE)
    }

    private suspend fun <T> op(timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Boolean): T = ops.withLock {
        val d = CompletableDeferred<Any?>()
        pending = d
        try {
            if (!start()) throw EdgeRequestException("Bluetooth is busy")
            @Suppress("UNCHECKED_CAST")
            withTimeout(timeoutMs) { d.await() as T }
        } finally {
            if (pending === d) pending = null
        }
    }

    private fun finish(value: Any?) {
        pending?.complete(value)
    }

    private fun fail(reason: String) {
        pending?.completeExceptionally(EdgeRequestException(reason))
    }

    /** Services → MTU → priority → notifications on events and records. */
    private suspend fun setup(g: BluetoothGatt) {
        val discovered = op<Int> { g.discoverServices() }
        if (discovered != BluetoothGatt.GATT_SUCCESS) throw EdgeRequestException("service discovery failed ($discovered)")
        val svc = g.getService(UUID.fromString(EdgeProtocol.SERVICE_UUID)) ?: throw EdgeRequestException("this device is not a Sotreus Edge")
        val ctl = svc.getCharacteristic(UUID.fromString(EdgeProtocol.CONTROL_UUID)) ?: throw EdgeRequestException("control characteristic missing")
        val events = svc.getCharacteristic(UUID.fromString(EdgeProtocol.EVENTS_UUID)) ?: throw EdgeRequestException("events characteristic missing")
        val records = svc.getCharacteristic(UUID.fromString(EdgeProtocol.RECORDS_UUID)) ?: throw EdgeRequestException("records characteristic missing")
        mtu = runCatching { op<Int> { g.requestMtu(EdgeProtocol.REQUESTED_MTU) } }.getOrDefault(23)
        // No requestConnectionPriority: Android moves this link to 15 ms by itself about 0.5 s after
        // connect, and an extra request collided with the Edge's own link procedures (status 35) and
        // came just before links dropped on Android 9.
        // Bond on this LE link (Android shows its prompt for the code on the Edge's display). Calling
        // createBond() without a link can try classic Bluetooth on Android 9, which an LE-only Edge never answers.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            // A paired Edge whose bond is gone: pairing again needs the Edge's PAIR screen, so ask the owner.
            if (autoConnect) throw PairingLostException()
            _state.value = EdgeLinkState.Bonding
            if (!bondOverLink()) throw EdgeRequestException("Pairing didn't finish: the code was not entered or did not match")
            _state.value = EdgeLinkState.Connecting
        }
        enableNotifications(g, events)
        enableNotifications(g, records)
        control = ctl
        _state.value = EdgeLinkState.Connected(mtu)
    }

    /**
     * The code goes into Android's own pairing box. Android 8–11 opens that box in front of the app
     * (instead of as a notification) only if Bluetooth discovery ran in the last minute, so run a
     * brief one first.
     */
    private suspend fun promptInForeground() {
        if (Build.VERSION.SDK_INT >= 31) return
        @Suppress("DEPRECATION")
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
        runCatching {
            if (adapter.startDiscovery()) kotlinx.coroutines.delay(300)
            adapter.cancelDiscovery()
        }
    }

    private suspend fun bondOverLink(timeoutMs: Long = 120_000): Boolean = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        promptInForeground()
        bondAwait()
    } ?: false

    private suspend fun bondAwait(): Boolean = run {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, intent: android.content.Intent) {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d?.address != device.address) return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) {
                        runCatching { context.unregisterReceiver(this) }
                        if (cont.isActive) cont.resumeWith(Result.success(state == BluetoothDevice.BOND_BONDED))
                    }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                context, receiver, android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                runCatching { context.unregisterReceiver(receiver) }
                cont.resumeWith(Result.success(true))
            } else if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                runCatching { context.unregisterReceiver(receiver) }
                cont.resumeWith(Result.success(false))
            }
        }
    }

    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(c, true)) throw EdgeRequestException("could not subscribe")
        val cccd = c.getDescriptor(CCCD) ?: throw EdgeRequestException("notification descriptor missing")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val status = op<Int> {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = value; g.writeDescriptor(cccd) }
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw EdgeRequestException("subscribe failed ($status); is the Edge paired with this phone?")
    }

    /** The radio link is up (between STATE_CONNECTED and STATE_DISCONNECTED). */
    @Volatile private var linkUp = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linkUp = true
                    attempts = 0
                    rearmJob?.cancel()
                    _state.value = EdgeLinkState.Connecting
                    setupJob?.cancel()
                    setupJob = scope.launch {
                        runCatching { setup(g) }.onFailure {
                            if (it is kotlinx.coroutines.CancellationException) return@onFailure
                            Log.w(TAG, "setup failed", it)
                            if (it is PairingLostException || pairingLost) {
                                lostPairing()
                                runCatching { g.disconnect() }
                                return@onFailure
                            }
                            // The link dropped mid-setup: stay Lost and let auto-connect bring it back.
                            // disconnect() here would cancel Android's pending reconnect.
                            if (!linkUp) return@onFailure
                            _state.value = EdgeLinkState.Failed(it.message ?: "setup failed")
                            runCatching { g.disconnect() }
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    linkUp = false
                    control = null
                    fail("link lost")
                    if (!closed && !pairingLost) _state.value = EdgeLinkState.Lost(clock(), "status $status")
                    if (!autoConnect && !closed) runCatching { g.close() }
                    if (autoConnect && !closed && !pairingLost) rearm()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish(status)

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) finish(mtu) else finish(23)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish(status)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish(status)

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) =
            if (status == BluetoothGatt.GATT_SUCCESS) finish(rssi) else fail("rssi read failed ($status)")

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = route(c, value)

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) route(c, c.value ?: return)
        }
    }

    private fun route(c: BluetoothGattCharacteristic, value: ByteArray) {
        when (c.uuid) {
            EVENTS -> eventCh.trySend(value.copyOf())
            RECORDS -> recordCh.trySend(value.copyOf())
        }
    }

    private class PairingLostException : Exception(PAIRING_LOST)

    private companion object {
        private const val REARM_MS = 60_000L
        const val PAIRING_LOST = "This phone lost its pairing with the Edge"
        private const val REOPEN_DELAY_MS = 500L
        const val TAG = "SotreusEdgeBle"
        const val OP_TIMEOUT_MS = 10_000L
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val EVENTS: UUID = UUID.fromString(EdgeProtocol.EVENTS_UUID)
        val RECORDS: UUID = UUID.fromString(EdgeProtocol.RECORDS_UUID)
    }
}
