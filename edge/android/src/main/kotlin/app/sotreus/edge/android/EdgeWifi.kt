package app.sotreus.edge.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Wi-Fi routes to an Edge (HANDOFF_V2_EDGE.md §3.2): joining the Edge's own network for a sync
 * burst (Android shows its own prompt), and finding an Edge on a shared network by mDNS
 * (`_sotreus._tcp`). While the phone is on the Edge's network, the Edge listens to Wi-Fi on one
 * channel only, so a burst is short and released right after.
 */
@Singleton
class EdgeWifi @Inject constructor(@ApplicationContext private val context: Context) {
    private val cm: ConnectivityManager? get() = context.getSystemService(ConnectivityManager::class.java)

    /** A joined Edge network. Call [release] as soon as the sync ends. */
    class Joined(val network: Network, private val onRelease: () -> Unit) {
        fun release() = onRelease()
    }

    /** Joins the Edge's access point (Android 10+). Null if the owner declined or it timed out. */
    suspend fun joinEdgeNetwork(ssid: String, password: String, timeoutMs: Long = 45_000): Joined? {
        if (Build.VERSION.SDK_INT < 29) return null
        val manager = cm ?: return null
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        var callback: ConnectivityManager.NetworkCallback? = null
        val network = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(network) }
                    override fun onUnavailable() { if (cont.isActive) cont.resume(null) }
                }
                callback = cb
                manager.requestNetwork(request, cb)
            }
        }
        val cb = callback
        if (network == null) {
            cb?.let { runCatching { manager.unregisterNetworkCallback(it) } }
            return null
        }
        return Joined(network) { cb?.let { runCatching { manager.unregisterNetworkCallback(it) } } }
    }

    /** An HTTP client whose sockets and DNS go through [network] (the Edge's network, not mobile data). */
    fun clientFor(network: Network): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns { host -> network.getAllByName(host).toList() }
        .build()

    /**
     * A client for a shared network that resolves the Edge's `.local` name to [address] itself,
     * so the request keeps the `.local` host (allowed for plain HTTP) instead of a bare IP.
     */
    fun clientFor(host: String, address: InetAddress): OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = if (hostname.equals(host, ignoreCase = true)) listOf(address) else Dns.SYSTEM.lookup(hostname)
        })
        .build()

    data class Found(val serviceName: String, val address: InetAddress, val port: Int)

    /** Finds the Edge named `sotreus-<suffix>` on the current network, or null. */
    @SuppressLint("MissingPermission")
    suspend fun find(suffix: String, timeoutMs: Long = 8_000): Found? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val wanted = "sotreus-${suffix.lowercase()}"
        val info = withTimeoutOrNull(timeoutMs) {
            callbackFlow {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(s: NsdServiceInfo) { if (s.serviceName.lowercase().startsWith(wanted)) trySend(s) }
                    override fun onDiscoveryStarted(t: String) = Unit
                    override fun onDiscoveryStopped(t: String) = Unit
                    override fun onServiceLost(s: NsdServiceInfo) = Unit
                    override fun onStartDiscoveryFailed(t: String, e: Int) { close() }
                    override fun onStopDiscoveryFailed(t: String, e: Int) = Unit
                }
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
            }.firstOrNull()
        } ?: return null
        val resolved = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(s: NsdServiceInfo) { if (cont.isActive) cont.resume(s) }
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) { if (cont.isActive) cont.resume(null) }
                })
            }
        } ?: return null
        @Suppress("DEPRECATION")
        val address = (if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses.firstOrNull() else resolved.host) ?: return null
        return Found(resolved.serviceName, address, resolved.port)
    }

    companion object {
        const val SERVICE_TYPE = "_sotreus._tcp"
        const val EDGE_AP_URL = "http://192.168.4.1"

        fun lanUrl(suffix: String) = "http://sotreus-${suffix.lowercase()}.local"
        fun lanHost(suffix: String) = "sotreus-${suffix.lowercase()}.local"
    }
}
