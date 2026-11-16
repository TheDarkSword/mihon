package mihon.fork.translation

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import mihon.translation.pc.PcClient
import java.net.Inet4Address
import kotlin.coroutines.resume

/** Finds the PC translator announced on the home network (mDNS, `_mihonit._tcp`). */
object PcDiscovery {

    data class Found(val name: String, val address: String)

    suspend fun discover(context: Context, timeoutMillis: Long = 4000): List<Found> {
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
        nsd.discoverServices(PcClient.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        val found = mutableListOf<NsdServiceInfo>()
        try {
            withTimeoutOrNull(timeoutMillis) { for (service in services) found += service }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        // Resolved one at a time: older Android versions refuse concurrent resolutions
        return found.distinctBy { it.serviceName }.mapNotNull { info ->
            resolve(nsd, info)?.let { Found(info.serviceName, it) }
        }
    }

    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): String? = withTimeoutOrNull(RESOLVE_MILLIS) {
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            resolveModern(nsd, info)
        } else {
            resolveLegacy(nsd, info)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveModern(nsd: NsdManager, info: NsdServiceInfo): String? {
        var callback: NsdManager.ServiceInfoCallback? = null
        return try {
            suspendCancellableCoroutine { cont ->
                callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceUpdated(updated: NsdServiceInfo) {
                        val host = updated.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull() ?: return
                        if (cont.isActive) cont.resume(PcClient.address(host.hostAddress ?: return, updated.port))
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
    private suspend fun resolveLegacy(nsd: NsdManager, info: NsdServiceInfo): String? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = (serviceInfo.host as? Inet4Address)?.hostAddress
                        if (cont.isActive) cont.resume(host?.let { PcClient.address(it, serviceInfo.port) })
                    }
                },
            )
        }

    private const val RESOLVE_MILLIS = 3000L
}
