package com.openobd.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.openobd.core.transport.ObdBridge
import com.openobd.session.ConnectionState
import com.openobd.session.ObdSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

/** Finds a phone running the OpenOBD bridge, from the car screen. */
object BridgeDiscovery {
    private const val TYPE = "_openobd._tcp"

    /** Where the phone was found last time: tried first, so coming back after a hiccup takes a moment, not a search. */
    @Volatile private var last: Pair<String, Int>? = null

    /** The phone announced on the network, else the Wi-Fi gateway (the phone, when this screen is on its hotspot). */
    suspend fun find(context: Context): Pair<String, Int>? {
        last?.let { if (reachable(it)) return it }
        val found = withTimeoutOrNull(5_000) { announced(context) } ?: gateway(context)?.let { it to ObdBridge.DEFAULT_PORT }
        if (found != null) last = found
        return found
    }

    private suspend fun reachable(at: Pair<String, Int>): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(at.first, at.second), 1500) }; true }.getOrDefault(false)
    }

    private suspend fun announced(context: Context): Pair<String, Int>? = suspendCancellableCoroutine { cont ->
        val nsd = context.getSystemService(NsdManager::class.java) ?: return@suspendCancellableCoroutine cont.resume(null)
        var listener: NsdManager.DiscoveryListener? = null
        fun finish(result: Pair<String, Int>?) {
            listener?.let { l -> runCatching { nsd.stopServiceDiscovery(l) } }
            listener = null
            if (cont.isActive) cont.resume(result)
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith(TYPE)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = r.host?.hostAddress ?: return
                        finish(host to r.port)
                    }
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {}
                })
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { listener = null; if (cont.isActive) cont.resume(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null; cont.resume(null) }
        cont.invokeOnCancellation { listener?.let { x -> runCatching { nsd.stopServiceDiscovery(x) } } }
    }

    /** The gateway of the Wi-Fi network this screen is on. */
    private fun gateway(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        return wifi.firstNotNullOfOrNull { n ->
            cm.getLinkProperties(n)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        }
    }
}

/**
 * The phone side of the bridge: opens this phone's adapter and lends it to the car screen over Wi-Fi, announcing
 * itself on the network so the screen finds it, while a foreground service keeps it running with the screen off.
 */
class PhoneBridge(private val context: Context, private val scope: CoroutineScope, private val session: ObdSession, private val settings: SettingsRepository) {
    private val _state = MutableStateFlow(ObdBridge.State())
    val state: StateFlow<ObdBridge.State> = _state
    private var job: Job? = null
    private var registration: NsdManager.RegistrationListener? = null
    val running: Boolean get() = job?.isActive == true

    /** Starts lending the adapter chosen on this phone (disconnecting this phone's own session first). */
    fun start() {
        if (running) return
        val s = settings.settings.value
        if (s.connectionType == ConnectionType.PHONE) {
            _state.value = ObdBridge.State(error = "This phone is set to use another phone's bridge. Choose its own adapter first.")
            return
        }
        if (session.state.value !is ConnectionState.Disconnected) session.disconnect()
        val bridge = ObdBridge({ session.createTransport(s) })
        job = scope.launch {
            val watch = launch {
                var announced = 0
                bridge.state.collect { st ->
                    _state.value = st
                    if (st.port > 0 && st.port != announced) { announced = st.port; announce(st.port) }
                }
            }
            try { bridge.run() } finally { watch.cancel(); unannounce(); _state.value = ObdBridge.State() }
        }
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, com.openobd.service.BridgeService::class.java))
        }
    }

    fun stop() {
        job?.cancel(); job = null
    }

    private fun announce(port: Int) {
        unannounce()
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply { serviceName = "OpenOBD " + Build.MODEL; serviceType = "_openobd._tcp"; setPort(port) }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
        }
        registration = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun unannounce() {
        val l = registration ?: return
        registration = null
        runCatching { context.getSystemService(NsdManager::class.java)?.unregisterService(l) }
    }

    /** This phone's addresses on its networks (hotspot and Wi-Fi), for typing on the car screen if it is not found. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().map { i.name to it.hostAddress.orEmpty() } }
            .filter { (name, _) -> !name.startsWith("rmnet") && !name.startsWith("ccmni") && !name.startsWith("dummy") }
            .map { it.second }.distinct()
    }.getOrDefault(emptyList())

    companion object {
        val texts = listOf("This phone is set to use another phone's bridge. Choose its own adapter first.")
    }
}
