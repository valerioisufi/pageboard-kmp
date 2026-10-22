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
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

/**
 * A device working with the others over iroh through [IrohSync], wherever they are: it reaches
 * them at the addresses they gave. Its engine (with the addresses it learned) outlives its
 * transport, as an application going offline and back.
 */
internal class RoamingDevice(val name: String, private val scope: CoroutineScope, private val relays: RelayConfig) {
    val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
    val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1), scope)
    val engine = SyncEngine(documents, InMemorySharedDocuments(), scope, { name }, NoFiles)
    private val secret = IrohSecret.generate()
    var sync: IrohSync? = null
        private set

    private object NoFiles : ResourceFiles {
        override fun read(path: String): ByteArray? = null
        override fun write(bytes: ByteArray, extension: String): String? = null
    }

    suspend fun goOnline() {
        sync = IrohSync(engine, IrohTransport(scope, secret, relays), scope, retryMs = 500, firstDelayMs = 0).also { it.start() }
    }

    suspend fun goOffline() {
        sync?.stop()
        engine.stop()
        sync = null
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

    suspend fun awaitSame(documentId: String, other: RoamingDevice) {
        while (true) {
            val mine = snapshot(documentId)
            if (mine != null && mine == other.snapshot(documentId)) return
            delay(20)
        }
    }

    /** Waits until this device works on [documentId] with [other] directly. */
    suspend fun awaitConnected(documentId: String, other: RoamingDevice) {
        while (engine.peers.value.none { it.documentId == documentId && it.device == other.engine.device }) delay(20)
    }
}

/**
 * Bob and Cy join a document of Ada with her invitation (which says where she is): they learn
 * from her where the other one is and connect to each other, so that they keep working together
 * once she is gone.
 */
internal suspend fun membersReachEachOtherWithoutTheOwner(scope: CoroutineScope, relays: RelayConfig) {
    val (ada, bob, cy) = listOf("ada", "bob", "cy").map { RoamingDevice(it, scope, relays) }
    listOf(ada, bob, cy).forEach { it.goOnline() }
    val document = ada.store.createDocument("Lab")
    val invitation = assertNotNull(ada.engine.invite(document.id, "Lab"))
    assertNotNull(invitation.addresses[IrohTransport.NAME], "the invitation says where Ada is")
    for (device in listOf(bob, cy)) assertNotNull(device.sync).join(invitation)
    ada.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(1))) }
    bob.awaitSame(document.id, ada)
    cy.awaitSame(document.id, ada)

    // Bob and Cy reach each other at the addresses Ada relayed
    bob.awaitConnected(document.id, cy)
    ada.goOffline()
    bob.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(0))) }
    cy.awaitSame(document.id, bob)
    assertEquals(3, assertNotNull(cy.snapshot(document.id)).pages.size)
    listOf(bob, cy).forEach { it.goOffline() }
}

class IrohSyncControllerTest {

    @Test
    fun membersReachEachOtherWithoutTheOwner() = irohSyncTest { scope ->
        membersReachEachOtherWithoutTheOwner(scope, RelayConfig.NONE)
    }

    @Test
    fun aDeviceBackOnlineIsConnectedAgain() = irohSyncTest { scope ->
        val (ada, bob) = listOf("ada", "bob").map { RoamingDevice(it, scope, RelayConfig.NONE) }
        listOf(ada, bob).forEach { it.goOnline() }
        val document = ada.store.createDocument("Lab")
        assertNotNull(bob.sync).join(assertNotNull(ada.engine.invite(document.id, "Lab")))
        bob.awaitSame(document.id, ada)

        // Bob goes offline (another port when back); both go on working on the document
        bob.goOffline()
        ada.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(1))) }
        bob.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(0))) }
        bob.goOnline()
        bob.awaitSame(document.id, ada)
        assertEquals(3, assertNotNull(ada.snapshot(document.id)).pages.size)
        listOf(ada, bob).forEach { it.goOffline() }
    }

    @Test
    fun aDeviceOfflineNoLongerSaysWhereItIs() = irohSyncTest { scope ->
        val ada = RoamingDevice("ada", scope, RelayConfig.NONE)
        ada.goOnline()
        assertNotNull(ada.engine.ownAddresses.value[IrohTransport.NAME])
        ada.goOffline()
        assertNull(ada.engine.ownAddresses.value[IrohTransport.NAME])
    }
}
