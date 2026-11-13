package app.screeaner

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/** Finds Macs running Screeaner on the local network via Bonjour/mDNS. */
class Discovery(context: Context) {
    data class Endpoint(val host: String, val port: Int)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var started = false

    /** Thread-safe set of everything found so far. */
    val endpoints = CopyOnWriteArraySet<Endpoint>()

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery failed to start: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(service: NsdServiceInfo) {
            resolve(service)
        }

        override fun onServiceLost(service: NsdServiceInfo) {}
    }

    @Suppress("DEPRECATION")
    private fun resolve(service: NsdServiceInfo) {
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val address = info.host?.hostAddress ?: return
                    endpoints.add(Endpoint(address, info.port))
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Resolve threw", e)
        }
    }

    fun start() {
        if (started) return
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            started = true
        } catch (e: Exception) {
            Log.w(TAG, "Discovery unavailable", e)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
        endpoints.clear()
    }

    private companion object {
        const val TAG = "ScreeanerDiscovery"
    }
}
