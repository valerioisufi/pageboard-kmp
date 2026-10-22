package io.github.valerioisufi.drawview.sync.iroh

import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.DocumentSnapshot
import io.github.valerioisufi.drawview.model.change.snapshot
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.sync.InMemorySharedDocuments
import io.github.valerioisufi.drawview.sync.ResourceFiles
import io.github.valerioisufi.drawview.sync.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A device synchronizing its documents over iroh, through [relays]. */
internal class IrohDevice(val name: String, private val scope: CoroutineScope, relays: RelayConfig) {
    val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
    val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1), scope)
    val engine = SyncEngine(documents, InMemorySharedDocuments(), scope, { name }, NoFiles)
    val transport = IrohTransport(scope, IrohSecret.generate(), relays)
    lateinit var address: String

    private object NoFiles : ResourceFiles {
        override fun read(path: String): ByteArray? = null
        override fun write(bytes: ByteArray, extension: String): String? = null
    }

    /** Starts its endpoint and serves the channels the others open. */
    suspend fun goOnline() {
        address = transport.start()
        scope.launch { transport.incoming.collect { engine.accept(it) } }
    }

    fun goOffline() = transport.close()

    /** Connects to the device at [address] about [documentId] (as a member, or joining it). */
    suspend fun connectTo(address: String, documentId: String) {
        val key = engine.connectionKeys().first { it.first == documentId }.second
        engine.connect(transport.connect(address), documentId, key)
    }

    suspend fun change(documentId: String, block: (DocumentSession) -> DocumentChange?) {
        val open = assertNotNull(documents.open(documentId))
        open.edit { session -> block(session)?.let { session.submit(it) } }
        documents.close(open)
    }

    suspend fun snapshot(documentId: String): DocumentSnapshot? {
        val open = documents.open(documentId) ?: return null
        return open.edit { it.document.snapshot() }.also { documents.close(open) }
    }

    suspend fun awaitSame(documentId: String, other: IrohDevice) {
        while (true) {
            val mine = snapshot(documentId)
            if (mine != null && mine == other.snapshot(documentId)) return
            delay(20)
        }
    }
}

/** Runs [block] in real time, with a scope for the work of the devices. */
internal fun irohSyncTest(block: suspend (CoroutineScope) -> Unit) = runTest {
    withContext(Dispatchers.Default) {
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(60_000) { block(work) }
        } finally {
            work.cancel()
        }
    }
}

/**
 * Three devices keeping a document the same over iroh: Ada shares it, Bob and Cy join through her
 * at the address [reach] gives of her; what each one changes reaches the others (relayed by Ada).
 */
internal suspend fun threeDevicesKeepTheDocumentTheSame(
    scope: CoroutineScope,
    relays: RelayConfig,
    reach: (IrohDevice) -> String = { it.address }
) {
    val (ada, bob, cy) = listOf("ada", "bob", "cy").map { IrohDevice(it, scope, relays) }
    listOf(ada, bob, cy).forEach { it.goOnline() }
    val document = ada.store.createDocument("Lab")
    val invitation = assertNotNull(ada.engine.invite(document.id, "Lab"))
    for (device in listOf(bob, cy)) {
        device.engine.join(invitation)
        device.connectTo(reach(ada), document.id)
    }
    ada.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(1))) }
    bob.awaitSame(document.id, ada)
    cy.awaitSame(document.id, ada)
    bob.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(0))) }
    cy.awaitSame(document.id, bob)
    assertEquals(3, assertNotNull(ada.snapshot(document.id)).pages.size)
    listOf(ada, bob, cy).forEach { it.goOffline() }
}

class IrohSyncTest {
    @Test
    fun devicesConnectedDirectlyKeepTheDocumentsTheSame() = irohSyncTest { scope ->
        threeDevicesKeepTheDocumentTheSame(scope, RelayConfig.NONE)
    }
}
