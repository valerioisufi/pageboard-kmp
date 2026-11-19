package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.sync.protocol.Invitation
import kotlinx.coroutines.CoroutineScope

/**
 * A way the devices find and reach each other (GRASP Polymorphism): the local network
 * (`LanSync`), the internet (`IrohSync`). Each one keeps the shared documents of its engine the
 * same as on the devices it reaches; an application runs the ones it wants at once.
 */
public interface SyncNetwork {
    /** Starts finding and reaching the other devices. */
    public suspend fun start()

    /** Joins the document of [invitation], through its owner where this network reaches it. */
    public suspend fun join(invitation: Invitation)

    /**
     * Makes again at once what was lost; [networkChanged]: after the background, or when the
     * device moved to another network.
     */
    public suspend fun refresh(networkChanged: Boolean = false)

    public suspend fun stop()
}

/**
 * The synchronization over the internet an application gives (GRASP Protected Variations: the
 * modules showing it do not depend on the engine of the connections): `IrohInternet` of
 * `pageboard-sync-iroh`.
 */
public interface InternetSync {
    /** Whether it works on this device (its native library is there). */
    public val isAvailable: Boolean

    /**
     * The network of [engine] through the relay [relay] with the access [token] (empty: none);
     * an empty [relay]: the public relays of the authors of the engine, for trying it.
     */
    public fun network(engine: SyncEngine, scope: CoroutineScope, relay: String, token: String): SyncNetwork
}
