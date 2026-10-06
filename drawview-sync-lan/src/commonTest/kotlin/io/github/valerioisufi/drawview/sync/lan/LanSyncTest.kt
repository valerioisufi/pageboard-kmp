package io.github.valerioisufi.drawview.sync.lan

import io.github.valerioisufi.drawview.core.change.AddPage
import io.github.valerioisufi.drawview.core.change.DocumentChange
import io.github.valerioisufi.drawview.core.change.DocumentSession
import io.github.valerioisufi.drawview.core.change.DocumentSnapshot
import io.github.valerioisufi.drawview.core.change.snapshot
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.data.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.data.KeyValueDocumentStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import io.github.valerioisufi.drawview.sync.InMemorySharedDocuments
import io.github.valerioisufi.drawview.sync.ResourceFiles
import io.github.valerioisufi.drawview.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LanSyncTest {

    /** Runs [block] in real time, with a scope for the work of the devices. */
    private fun lanTest(block: suspend (CoroutineScope) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                withTimeout(30_000) { block(work) }
            } finally {
                work.cancel()
            }
        }
    }

    @Test
    fun framesTravelOverTcp() = lanTest { scope ->
        val server = LanTransport(scope)
        val port = server.start()
        val client = LanTransport(scope)
        val outgoing = client.connect("127.0.0.1:$port")
        val incoming = server.incoming.first()

        val large = ByteArray(1_500_000) { (it % 251).toByte() }
        outgoing.send("hello".encodeToByteArray())
        outgoing.send(ByteArray(0))
        outgoing.send(large)
        assertEquals("hello", incoming.receive()?.decodeToString())
        assertContentEquals(ByteArray(0), incoming.receive())
        assertContentEquals(large, incoming.receive())
        incoming.send("back".encodeToByteArray())
        assertEquals("back", outgoing.receive()?.decodeToString())

        // Closed on one side: the other one sees the end
        outgoing.close()
        assertNull(incoming.receive())
        client.close()
        server.close()
    }

    /** A network where the devices find each other in memory (the connections are real). */
    private class FakeNetwork {
        val announced = MutableStateFlow<Map<String, LanService>>(emptyMap())
    }

    private class FakeDiscovery(private val network: FakeNetwork) : LanDiscovery {
        private var name: String? = null

        override suspend fun advertise(name: String, port: Int, attributes: Map<String, String>) {
            this.name = name
            network.announced.update { it + (name to LanService(name, "127.0.0.1", port, attributes)) }
        }

        override val services: Flow<List<LanService>> = network.announced.map { it.values.toList() }

        override fun close() {
            name?.let { gone -> network.announced.update { it - gone } }
        }
    }

    private class NoFiles : ResourceFiles {
        override fun read(path: String): ByteArray? = null
        override fun write(bytes: ByteArray, extension: String): String? = null
    }

    /** A device of the local network, on it from [goOnline] to [goOffline]. */
    private class Device(val name: String, private val scope: CoroutineScope, private val network: FakeNetwork) {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1), scope)
        val shared = InMemorySharedDocuments()
        val engine = SyncEngine(documents, shared, scope, name, NoFiles())
        lateinit var transport: LanTransport
        lateinit var lan: LanSync

        suspend fun goOnline() {
            transport = LanTransport(scope)
            lan = LanSync(engine, shared, FakeDiscovery(network), scope, store.replicaId(), name, transport, retryMs = 200)
            lan.start()
        }

        suspend fun goOffline() = lan.stop()

        suspend fun change(documentId: String, block: (DocumentSession) -> DocumentChange?) {
            val open = assertNotNull(documents.open(documentId))
            open.edit { session -> block(session)?.let { session.submit(it) } }
            documents.close(open)
        }

        suspend fun snapshot(documentId: String): DocumentSnapshot? {
            val open = documents.open(documentId) ?: return null
            return open.edit { it.document.snapshot() }.also { documents.close(open) }
        }
    }

    @Test
    fun devicesOfTheNetworkFindEachOtherAndKeepTheDocumentsTheSame() = lanTest { scope ->
        val network = FakeNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, scope, network) }
        listOf(ada, bob, cy).forEach { it.goOnline() }

        val document = ada.store.createDocument("Lab")
        val invitation = ada.engine.share(document.id, "Lab")
        ada.lan.announce()
        // Without an address: found on the network
        bob.lan.join(invitation)
        cy.lan.join(invitation)
        ada.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(1))) }
        cy.awaitSame(document.id, ada)
        bob.awaitSame(document.id, ada)
        bob.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(0))) }
        cy.awaitSame(document.id, bob)
        assertEquals(3, assertNotNull(ada.snapshot(document.id)).pages.size)

        listOf(ada, bob, cy).forEach { it.goOffline() }
    }

    @Test
    fun aDeviceGoneAndBackIsSynchronizedAgain() = lanTest { scope ->
        val network = FakeNetwork()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, scope, network) }
        listOf(ada, bob).forEach { it.goOnline() }
        val document = ada.store.createDocument("Lab")
        val invitation = ada.engine.share(document.id, "Lab")
        ada.lan.announce()
        bob.lan.join(invitation)
        bob.awaitSame(document.id, ada)

        // Bob goes away (another port when back); both go on working on the document
        bob.goOffline()
        ada.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(1))) }
        bob.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(0))) }
        bob.goOnline()
        bob.awaitSame(document.id, ada)
        assertEquals(3, assertNotNull(ada.snapshot(document.id)).pages.size)
        listOf(ada, bob).forEach { it.goOffline() }
    }

    @Test
    fun aListenerClosedBySystemListensAgain() = lanTest { scope ->
        val network = FakeNetwork()
        val ada = Device("ada", scope, network)
        ada.goOnline()
        ada.transport.stopListening()
        while (!ada.transport.isListening) delay(20)
        // Announced where it listens now
        val replica = ada.store.replicaId()
        while (network.announced.value[replica]?.port != ada.transport.boundPort) delay(20)
        ada.goOffline()
    }

    private suspend fun Device.awaitSame(documentId: String, other: Device) {
        while (true) {
            val mine = snapshot(documentId)
            if (mine != null && mine == other.snapshot(documentId)) return
            delay(20)
        }
    }
}
