package mihon.fork.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/** Finds and announces Mihon IT services on the home network (mDNS / DNS-SD). */
object LanDiscovery {

    data class Found(val name: String, val host: String, val port: Int) {
        val address get() = "$host:$port"
    }

    suspend fun discover(context: Context, serviceType: String, timeoutMillis: Long = 4000): List<Found> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val services = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                services.close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                services.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        val found = mutableListOf<NsdServiceInfo>()
        try {
            withTimeoutOrNull(timeoutMillis) { for (service in services) found += service }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        // Resolved one at a time: older Android versions refuse concurrent resolutions
        return found.distinctBy { it.serviceName }.mapNotNull { resolve(nsd, it) }
    }

    /** Announces a service until the returned function is called. */
    fun register(context: Context, serviceType: String, name: String, port: Int): () -> Unit {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return {}
        val info = NsdServiceInfo().apply {
            serviceName = name
            this.serviceType = serviceType
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return { runCatching { nsd.unregisterService(listener) } }
    }

    /** This phone's IPv4 address on the current network, to show it for manual connections. */
    fun localAddress(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val properties = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
        return properties.linkAddresses.map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): Found? = withTimeoutOrNull(RESOLVE_MILLIS) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveModern(nsd, info)
        } else {
            resolveLegacy(nsd, info)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveModern(nsd: NsdManager, info: NsdServiceInfo): Found? {
        var callback: NsdManager.ServiceInfoCallback? = null
        return try {
            suspendCancellableCoroutine { cont ->
                callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceUpdated(updated: NsdServiceInfo) {
                        val host = updated.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull() ?: return
                        val address = host.hostAddress ?: return
                        if (cont.isActive) cont.resume(Found(info.serviceName, address, updated.port))
                    }

                    override fun onServiceLost() = Unit
                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                nsd.registerServiceInfoCallback(info, Runnable::run, callback!!)
            }
        } finally {
            callback?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(nsd: NsdManager, info: NsdServiceInfo): Found? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = (serviceInfo.host as? Inet4Address)?.hostAddress
                        if (cont.isActive) {
                            cont.resume(host?.let { Found(info.serviceName, it, serviceInfo.port) })
                        }
                    }
                },
            )
        }

    private const val RESOLVE_MILLIS = 3000L
}
