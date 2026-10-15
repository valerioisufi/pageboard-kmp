package io.github.valerioisufi.drawview.sync.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LanDiscovery] of Android (`NsdManager`). From Android 17 the application asks for the
 * permission `ACCESS_LOCAL_NETWORK` before using it.
 */
public class NsdLanDiscovery(context: Context) : LanDiscovery {
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
        fun publish() = trySend(found.values.toList())
        // Android 14: the services followed (their address and attributes as they change)
        val followed = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
        // Before: resolved one at a time (in parallel they fail), again a little later when it fails
        val present = ConcurrentHashMap.newKeySet<String>()
        val toResolve = Channel<Pair<NsdServiceInfo, Int>>(Channel.UNLIMITED)
        launch {
            for ((info, attempt) in toResolve) {
                if (info.serviceName !in present) continue
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(info) }
                if (info.serviceName !in present) continue
                if (resolved != null) {
                    found[resolved.name] = resolved
                    publish()
                } else {
                    launch {
                        delay(RETRY_MS * minOf(attempt + 1, MAX_RETRY_STEPS))
                        toResolve.trySend(info to attempt + 1)
                    }
                }
            }
        }

        fun follow(info: NsdServiceInfo) {
            val name = info.serviceName
            if (Build.VERSION.SDK_INT < 34) {
                present.add(name)
                toResolve.trySend(info to 0)
                return
            }
            if (followed.containsKey(name)) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                    found[name] = resolved.toLanService() ?: return
                    publish()
                }

                override fun onServiceLost() {
                    if (found.remove(name) != null) publish()
                }

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    // Followed again when found again
                    followed.remove(name, this)
                }

                override fun onServiceInfoCallbackUnregistered() {}
            }
            followed[name] = callback
            runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }.onFailure { followed.remove(name, callback) }
        }

        fun forget(name: String) {
            present.remove(name)
            followed.remove(name)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            if (found.remove(name) != null) publish()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = follow(info)
            override fun onServiceLost(info: NsdServiceInfo) = forget(info.serviceName)
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(LanDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (Build.VERSION.SDK_INT >= 34) followed.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            toResolve.close()
        }
    }

    /** The address and the attributes of [info] (before Android 14), or `null` when it cannot be resolved. */
    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): LanService? = suspendCancellableCoroutine { continuation ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                if (continuation.isActive) continuation.resume(resolved.toLanService())
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }
        })
    }

    override fun close() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
        const val RESOLVE_TIMEOUT_MS = 10_000L
        const val RETRY_MS = 2_000L
        const val MAX_RETRY_STEPS = 5

        @Suppress("DEPRECATION")
        fun NsdServiceInfo.toLanService(): LanService? {
            val address = if (Build.VERSION.SDK_INT >= 34) hostAddresses.firstOrNull { it is Inet4Address } ?: hostAddresses.firstOrNull() else host
            val hostAddress = address?.hostAddress ?: return null
            val attributes = attributes.mapValues { (_, value) -> value?.decodeToString().orEmpty() }
            return LanService(serviceName, hostAddress, port, attributes)
        }
    }
}
