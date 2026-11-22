package io.github.valerioisufi.pageboard.sync.lan

import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.change.AddPage
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.DocumentSnapshot
import io.github.valerioisufi.pageboard.model.change.snapshot
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.sync.InMemorySharedDocuments
import io.github.valerioisufi.pageboard.sync.ResourceFiles
import io.github.valerioisufi.pageboard.sync.SyncEngine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
        val server = LanTransport(scope, 0)
        val port = server.start()
        val client = LanTransport(scope, 0)
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

    /** The discovery of a device on [network]; [reaches] false: its announcements and the others' get lost. */
    private class FakeDiscovery(private val network: FakeNetwork, private val reaches: Boolean = true) : LanDiscovery {
        private var name: String? = null

        /** Whether the next [refresh] finds a new address; the announcements made. */
        var newAddress = false
        var announcements = 0

        /** Whether its announcement stays as it is on the network (an old address left there). */
        var frozen = false

        override suspend fun refresh(): Boolean = newAddress.also { newAddress = false }

        override suspend fun advertise(name: String, port: Int, attributes: Map<String, String>) {
            announcements++
            if (!reaches || frozen) return
            this.name = name
            network.announced.update { it + (name to LanService(name, "127.0.0.1", port, attributes)) }
        }

        override val services: Flow<List<LanService>> =
            if (reaches) network.announced.map { it.values.toList() } else flowOf(emptyList())

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
        val engine = SyncEngine(documents, shared, scope, { name }, NoFiles())
        lateinit var transport: LanTransport
        lateinit var lan: LanSync
        lateinit var discovery: FakeDiscovery

        /**
         * Goes on the network, listening on [port] (0: a free one, not the default one that an
         * application of this machine may hold); [found]: whether it finds the others and they find it.
         */
        suspend fun goOnline(port: Int = 0, found: Boolean = true) {
            transport = LanTransport(scope, port)
            discovery = FakeDiscovery(network, found)
            lan = LanSync(engine, discovery, scope, store.replicaId(), { name }, transport, retryMs = 200)
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
        val invitation = assertNotNull(ada.engine.invite(document.id, "Lab"))
        ada.lan.announce()
        // Without an address: found on the network
        bob.lan.join(invitation)
        cy.lan.join(invitation)
        ada.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        cy.awaitSame(document.id, ada)
        bob.awaitSame(document.id, ada)
        bob.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(0))) }
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
        val invitation = assertNotNull(ada.engine.invite(document.id, "Lab"))
        ada.lan.announce()
        bob.lan.join(invitation)
        bob.awaitSame(document.id, ada)

        // Bob goes away (another port when back); both go on working on the document
        bob.goOffline()
        ada.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        bob.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(0))) }
        bob.goOnline()
        bob.awaitSame(document.id, ada)
        assertEquals(3, assertNotNull(ada.snapshot(document.id)).pages.size)
        listOf(ada, bob).forEach { it.goOffline() }
    }

    @Test
    fun aDeviceStartedAgainIsConnectedAgainWithoutBeingFoundAgain() = lanTest { scope ->
        val network = FakeNetwork()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, scope, network) }
        val port = LanTransport(scope, 0).let { free -> free.start().also { free.close() } }
        ada.goOnline()
        bob.goOnline(port)
        val document = ada.store.createDocument("Lab")
        val invitation = assertNotNull(ada.engine.invite(document.id, "Lab"))
        ada.lan.announce()
        bob.lan.join(invitation)
        bob.awaitSame(document.id, ada)

        // Bob starts again on the same port (held by the connections just closed), but this time
        // its announcement and the ones of the others get lost: Ada connects to where it was
        bob.goOffline()
        ada.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        bob.goOnline(port, found = false)
        assertEquals(port, bob.transport.boundPort)
        bob.awaitSame(document.id, ada)
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

    @Test
    fun aDiscoveryOnANewAddressIsAnnouncedAgain() = lanTest { scope ->
        val ada = Device("ada", scope, FakeNetwork())
        ada.goOnline()
        val before = ada.discovery.announcements
        // Nothing changed: not announced again
        ada.lan.refresh()
        assertEquals(before, ada.discovery.announcements)
        ada.discovery.newAddress = true
        // By this refresh or the next round of its own
        ada.lan.refresh()
        while (ada.discovery.announcements == before) delay(20)
        assertEquals(before + 1, ada.discovery.announcements)
        ada.goOffline()
    }

    @Test
    fun aDeviceFoundWhereItIsNoMoreIsSaidNotToAnswer() = lanTest { scope ->
        val network = FakeNetwork()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, scope, network) }
        ada.goOnline()
        val document = ada.store.createDocument("Lab")
        val invitation = assertNotNull(ada.engine.invite(document.id, "Lab"))
        ada.lan.announce()
        // Ada announced on an address gone (a port nobody listens on), Bob only finds it there
        val adaId = ada.store.replicaId()
        val gone = closedPort(scope)
        ada.discovery.frozen = true
        network.announced.update { it + (adaId to it.getValue(adaId).copy(port = gone)) }
        bob.goOnline()
        bob.lan.join(invitation)
        val failing = withTimeout(10_000) { bob.lan.devices.first { devices -> devices.any { it.id == adaId && it.failures > 0 } } }.single { it.id == adaId }
        assertTrue(failing.shared)
        assertEquals("ada", failing.name)
        assertEquals("127.0.0.1:$gone", failing.address)
        assertNotNull(failing.failure)
        assertEquals(false, bob.lan.check(waitMs = 2_000).devices.single { it.device.id == adaId }.reachable)

        // Announced where it is again: connected, the failures forgotten
        ada.discovery.frozen = false
        ada.lan.announce()
        bob.awaitSame(document.id, ada)
        withTimeout(10_000) { bob.lan.devices.first { devices -> devices.any { it.id == adaId && it.failures == 0 && it.connected } } }
        listOf(ada, bob).forEach { it.goOffline() }
    }

    @Test
    fun theCheckSaysWhetherThisDeviceAnswersWhereItIsAnnounced() = lanTest { scope ->
        val network = FakeNetwork()
        val ada = Device("ada", scope, network)
        ada.goOnline()
        val adaId = ada.store.replicaId()
        val fine = ada.lan.check(waitMs = 2_000)
        assertTrue(fine.listening)
        assertEquals("127.0.0.1:${ada.transport.boundPort}", fine.announcedAt)
        assertEquals(true, fine.announcedReachable)

        // Its announcement left on an old address
        val gone = closedPort(scope)
        ada.discovery.frozen = true
        network.announced.update { it + (adaId to it.getValue(adaId).copy(port = gone)) }
        // Found there by its discovery
        delay(500)
        val stale = ada.lan.check(waitMs = 2_000)
        assertEquals("127.0.0.1:$gone", stale.announcedAt)
        assertEquals(false, stale.announcedReachable)
        ada.goOffline()
        assertEquals(false, ada.lan.check(waitMs = 100).listening)
    }

    /** A port of this machine nobody listens on. */
    private suspend fun closedPort(scope: CoroutineScope): Int = LanTransport(scope, 0).let { free -> free.start().also { free.close() } }

    private suspend fun Device.awaitSame(documentId: String, other: Device) {
        while (true) {
            val mine = snapshot(documentId)
            if (mine != null && mine == other.snapshot(documentId)) return
            delay(20)
        }
    }
}
