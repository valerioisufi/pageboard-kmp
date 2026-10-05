package io.github.valerioisufi.drawview.sync.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * [LanDiscovery] of Android (`NsdManager`). From Android 17 the application asks for the
 * permission `ACCESS_LOCAL_NETWORK` before using it.
 */
class NsdLanDiscovery(context: Context) : LanDiscovery {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null

    override suspend fun advertise(name: String, port: Int, attributes: Map<String, String>) {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = LanDiscovery.SERVICE_TYPE
            setPort(port)
            attributes.forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registration = listener
    }

    override val services: Flow<List<LanService>> = callbackFlow {
        val found = ConcurrentHashMap<String, LanService>()
        // The services to resolve, one at a time (Android before 14 resolves one at a time)
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val callbacks = mutableListOf<NsdManager.ServiceInfoCallback>()
        launch {
            for (info in toResolve) {
                val resolved = resolve(info, callbacks) ?: continue
                found[resolved.name] = resolved
                trySend(found.values.toList())
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                trySend(found.values.toList())
            }

            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(LanDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (Build.VERSION.SDK_INT >= 34) callbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            toResolve.close()
        }
    }

    /** The address and the attributes of [info], or `null` when it cannot be resolved. */
    private suspend fun resolve(info: NsdServiceInfo, callbacks: MutableList<NsdManager.ServiceInfoCallback>): LanService? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= 34) {
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(resolved: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(resolved.toLanService())
                    }

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                callbacks.add(callback)
                nsd.registerServiceInfoCallback(info, executor, callback)
            } else {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(resolved.toLanService())
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        }

    override fun close() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()

        @Suppress("DEPRECATION")
        fun NsdServiceInfo.toLanService(): LanService? {
            val address = if (Build.VERSION.SDK_INT >= 34) hostAddresses.firstOrNull { it is Inet4Address } ?: hostAddresses.firstOrNull() else host
            val hostAddress = address?.hostAddress ?: return null
            val attributes = attributes.mapValues { (_, value) -> value?.decodeToString().orEmpty() }
            return LanService(serviceName, hostAddress, port, attributes)
        }
    }
}
