package io.github.valerioisufi.pageboard.sync.iroh

import io.github.valerioisufi.pageboard.sync.InternetSync
import io.github.valerioisufi.pageboard.sync.SyncEngine
import io.github.valerioisufi.pageboard.sync.SyncNetwork
import kotlinx.coroutines.CoroutineScope
import io.github.valerioisufi.pageboard.sync.protocol.ExperimentalSyncApi

/**
 * The synchronization over the internet with iroh, for an application to give the modules
 * showing it ([InternetSync]): an [IrohSync] with the key of the connections of this device
 * ([IrohSecret.ofThisDevice]).
 */
@ExperimentalSyncApi
public object IrohInternet : InternetSync {
    override val isAvailable: Boolean get() = IrohTransport.isAvailable

    /**
     * The network of [engine] through [relay] (its address: `https://` when it gives none;
     * several separated by spaces), with the access [token]; without [relay], the public relays
     * of n0 ([RelayConfig.N0_PUBLIC], rate-limited, for trying).
     */
    override fun network(engine: SyncEngine, scope: CoroutineScope, relay: String, token: String): SyncNetwork {
        val urls = relay.split(' ', '\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.map { if ("://" in it) it else "https://$it" }
        val relays = if (urls.isEmpty()) RelayConfig.N0_PUBLIC else RelayConfig(urls, token.trim().ifEmpty { null })
        return IrohSync(engine, IrohTransport(scope, IrohSecret.ofThisDevice(), relays), scope)
    }
}
