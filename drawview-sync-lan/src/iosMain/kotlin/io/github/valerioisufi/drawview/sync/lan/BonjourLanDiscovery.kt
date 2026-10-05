package io.github.valerioisufi.drawview.sync.lan

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.darwin.NSObject

/**
 * [LanDiscovery] of iOS (Bonjour). The application declares the service in its `Info.plist`
 * (`NSBonjourServices`: `_drawview._tcp`) with the reason of the use of the local network
 * (`NSLocalNetworkUsageDescription`).
 */
@OptIn(BetaInteropApi::class)
class BonjourLanDiscovery : LanDiscovery {
    private var announced: NSNetService? = null

    override suspend fun advertise(name: String, port: Int, attributes: Map<String, String>) = withContext(Dispatchers.Main) {
        announced?.stop()
        val service = NSNetService(domain = DOMAIN, type = TYPE, name = name, port = port)
        val record: Map<Any?, *> = attributes.mapValues { (_, value) -> NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) }
        service.setTXTRecordData(NSNetService.dataFromTXTRecordDictionary(record))
        service.publish()
        announced = service
    }

    override val services: Flow<List<LanService>> = callbackFlow {
        val found = mutableMapOf<String, LanService>()
        val resolving = mutableListOf<NSNetService>()
        val serviceDelegate = object : NSObject(), NSNetServiceDelegateProtocol {
            override fun netServiceDidResolveAddress(sender: NSNetService) {
                val host = sender.hostName?.removeSuffix(".") ?: return
                val record = sender.TXTRecordData()?.let { NSNetService.dictionaryFromTXTRecordData(it) }.orEmpty()
                val attributes = record.entries.associate { (key, value) ->
                    key.toString() to ((value as? NSData)?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }.orEmpty())
                }
                found[sender.name] = LanService(sender.name, host, sender.port.toInt(), attributes)
                trySend(found.values.toList())
            }
        }
        val browserDelegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol {
            @ObjCSignatureOverride
            override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
                didFindService.delegate = serviceDelegate
                resolving.add(didFindService)
                didFindService.resolveWithTimeout(RESOLVE_TIMEOUT_S)
            }

            @ObjCSignatureOverride
            override fun netServiceBrowser(browser: NSNetServiceBrowser, didRemoveService: NSNetService, moreComing: Boolean) {
                resolving.remove(didRemoveService)
                found.remove(didRemoveService.name)
                trySend(found.values.toList())
            }
        }
        val browser = NSNetServiceBrowser()
        browser.delegate = browserDelegate
        browser.searchForServicesOfType(TYPE, inDomain = DOMAIN)
        // The delegates are weak references of their objects: kept here while browsing
        val delegates = listOf(serviceDelegate, browserDelegate)
        awaitClose {
            browser.stop()
            resolving.forEach { it.stop() }
            check(delegates.isNotEmpty())
        }
    }.flowOn(Dispatchers.Main)

    override fun close() {
        announced?.stop()
        announced = null
    }

    private companion object {
        const val DOMAIN = "local."
        const val TYPE = "${LanDiscovery.SERVICE_TYPE}."
        const val RESOLVE_TIMEOUT_S = 5.0
    }
}
