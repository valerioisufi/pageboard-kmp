package io.github.valerioisufi.pageboard.sync.lan

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSNetServicesErrorCode
import platform.Foundation.NSNumber
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.darwin.NSObject
import platform.posix.AF_INET

/**
 * [LanDiscovery] of iOS (Bonjour). The application declares the service in its `Info.plist`
 * (`NSBonjourServices`: `_pageboard._tcp`) with the reason of the use of the local network
 * (`NSLocalNetworkUsageDescription`).
 */
@OptIn(BetaInteropApi::class)
public class BonjourLanDiscovery : LanDiscovery {
    private var announced: NSNetService? = null
    private val problemSet = DiscoveryProblems()

    override val problems: StateFlow<List<DiscoveryProblem>> get() = problemSet.problems

    /** The announcement published, or why not (kept here: the delegate of a service is a weak reference). */
    private val publishDelegate = object : NSObject(), NSNetServiceDelegateProtocol {
        override fun netServiceDidPublish(sender: NSNetService) {
            problemSet.clear(DiscoveryProblem.AdvertiseFailed::class, DiscoveryProblem.PermissionDenied::class)
        }

        @ObjCSignatureOverride
        override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
            problemSet.report(bonjourProblem(errorCodeOf(didNotPublish), announcing = true))
        }
    }

    override suspend fun advertise(name: String, port: Int, attributes: Map<String, String>): Unit = withContext(Dispatchers.Main) {
        checkConfiguration()
        announced?.stop()
        val service = NSNetService(domain = DOMAIN, type = TYPE, name = name, port = port)
        service.delegate = publishDelegate
        val record: Map<Any?, *> = attributes.mapValues { (_, value) -> NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) }
        service.setTXTRecordData(NSNetService.dataFromTXTRecordDictionary(record))
        service.publish()
        announced = service
    }

    override val services: Flow<List<LanService>> = callbackFlow {
        val found = mutableMapOf<String, LanService>()
        // The services on the network, by name (the same service may come as different objects):
        // resolved (again when it fails), their attributes followed
        val present = mutableMapOf<String, NSNetService>()
        val failures = mutableMapOf<String, Int>()
        val serviceDelegate = object : NSObject(), NSNetServiceDelegateProtocol {
            override fun netServiceDidResolveAddress(sender: NSNetService) {
                if (sender.name !in present) return
                // The address itself: no other lookup of the name of the device when connecting
                val host = sender.ipv4Address() ?: sender.hostName?.removeSuffix(".") ?: return
                failures.remove(sender.name)
                found[sender.name] = LanService(sender.name, host, sender.port.toInt(), attributesOf(sender.TXTRecordData()))
                trySend(found.values.toList())
            }

            @ObjCSignatureOverride
            override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
                // Tried again a little later, while it is on the network
                val attempt = (failures[sender.name] ?: 0) + 1
                failures[sender.name] = attempt
                launch {
                    delay(RETRY_MS * minOf(attempt, MAX_RETRY_STEPS))
                    present[sender.name]?.resolveWithTimeout(RESOLVE_TIMEOUT_S)
                }
            }

            override fun netService(sender: NSNetService, didUpdateTXTRecordData: NSData) {
                // Its documents changed
                val service = found[sender.name]?.takeIf { sender.name in present } ?: return
                found[sender.name] = service.copy(attributes = attributesOf(didUpdateTXTRecordData))
                trySend(found.values.toList())
            }
        }
        val browserDelegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol {
            override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
                problemSet.clear(DiscoveryProblem.BrowseFailed::class)
            }

            @ObjCSignatureOverride
            override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
                problemSet.report(bonjourProblem(errorCodeOf(didNotSearch), announcing = false))
            }

            @ObjCSignatureOverride
            override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
                present.remove(didFindService.name)?.forget()
                present[didFindService.name] = didFindService
                didFindService.delegate = serviceDelegate
                didFindService.startMonitoring()
                didFindService.resolveWithTimeout(RESOLVE_TIMEOUT_S)
            }

            @ObjCSignatureOverride
            override fun netServiceBrowser(browser: NSNetServiceBrowser, didRemoveService: NSNetService, moreComing: Boolean) {
                present.remove(didRemoveService.name)?.forget()
                failures.remove(didRemoveService.name)
                found.remove(didRemoveService.name)
                trySend(found.values.toList())
            }
        }
        checkConfiguration()
        val browser = NSNetServiceBrowser()
        browser.delegate = browserDelegate
        browser.searchForServicesOfType(TYPE, inDomain = DOMAIN)
        // The delegates are weak references of their objects: kept here while browsing
        val delegates = listOf(serviceDelegate, browserDelegate)
        awaitClose {
            browser.stop()
            present.values.forEach { it.forget() }
            check(delegates.isNotEmpty())
        }
    }.flowOn(Dispatchers.Main)

    override fun close() {
        announced?.stop()
        announced?.delegate = null
        announced = null
        problemSet.clearAll()
    }

    /** The keys of the `Info.plist` iOS needs for the local network, reported when missing. */
    private fun checkConfiguration() {
        val bundle = NSBundle.mainBundle
        val services = bundle.objectForInfoDictionaryKey(BONJOUR_SERVICES_KEY) as? List<*>
        val missing = buildList {
            if (services?.any { it?.toString()?.removeSuffix(".") == LanDiscovery.SERVICE_TYPE } != true) add("$BONJOUR_SERVICES_KEY: ${LanDiscovery.SERVICE_TYPE}")
            if (bundle.objectForInfoDictionaryKey(LOCAL_NETWORK_USAGE_KEY) == null) add(LOCAL_NETWORK_USAGE_KEY)
        }
        if (missing.isEmpty()) problemSet.clear(DiscoveryProblem.MissingConfiguration::class)
        else problemSet.report(DiscoveryProblem.MissingConfiguration(missing))
    }

    private companion object {
        const val DOMAIN = "local."
        const val TYPE = "${LanDiscovery.SERVICE_TYPE}."
        const val RESOLVE_TIMEOUT_S = 5.0
        const val RETRY_MS = 2_000L
        const val MAX_RETRY_STEPS = 5

        fun NSNetService.forget() {
            stopMonitoring()
            stop()
            delegate = null
        }

        /** The code of a failure of Bonjour, in its dictionary of error. */
        fun errorCodeOf(error: Map<Any?, *>): Int? = when (val code = error[NSNetServicesErrorCode]) {
            is Number -> code.toInt()
            is NSNumber -> code.intValue
            else -> null
        }

        fun attributesOf(record: NSData?): Map<String, String> =
            record?.let { NSNetService.dictionaryFromTXTRecordData(it) }.orEmpty().entries.associate { (key, value) ->
                key.toString() to ((value as? NSData)?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }.orEmpty())
            }

        /** The IPv4 address of the service (not a self-assigned one when it has another), if any. */
        @OptIn(ExperimentalForeignApi::class)
        fun NSNetService.ipv4Address(): String? {
            val addresses = addresses.orEmpty().mapNotNull { address ->
                val data = address as? NSData ?: return@mapNotNull null
                if (data.length < 8u) return@mapNotNull null
                // A sockaddr_in: length, family, port, address
                val bytes = data.bytes?.reinterpret<UByteVar>() ?: return@mapNotNull null
                if (bytes[1].toInt() != AF_INET) null else (4..7).joinToString(".") { bytes[it].toString() }
            }
            return addresses.firstOrNull { !it.startsWith("169.254.") } ?: addresses.firstOrNull()
        }
    }
}
