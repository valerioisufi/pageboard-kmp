package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.model.change.DocumentSnapshot
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.model.change.snapshot
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.space.SpaceLibrary
import io.github.valerioisufi.pageboard.sync.protocol.DeviceIdentity
import io.github.valerioisufi.pageboard.sync.protocol.DocumentKey
import io.github.valerioisufi.pageboard.sync.protocol.InMemoryChannel
import io.github.valerioisufi.pageboard.sync.protocol.InMemoryNetwork
import io.github.valerioisufi.pageboard.sync.protocol.OpenFrame
import io.github.valerioisufi.pageboard.sync.protocol.PROTOCOL_VERSION
import io.github.valerioisufi.pageboard.sync.protocol.SecureChannel
import io.github.valerioisufi.pageboard.sync.protocol.Invitation
import io.github.valerioisufi.pageboard.sync.protocol.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A document shared on its own and through a shared folder at once, through the engines (see
 * `docs/plans/shared-access.md`, phase 2): two devices in both accesses, each access managed on
 * its own, the document synced through either one.
 */
class SharedAccessTest {

    /** A device keeping spaces, whose library keeps the documents shared on their own in the spaces too. */
    private class Device(val name: String, scope: CoroutineScope, test: TestScope, network: InMemoryNetwork, val identity: DeviceIdentity = DeviceIdentity.generate()) {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val shared = StoreSharedDocuments(store, documents)
        val library = SpaceLibrary(store, store, scope, sharedApart = { _, _ -> false })
        val engine = SyncEngine(documents, shared, scope, { name }, identity = identity, keepAliveMs = 0, spaces = library)
        val transport = network.transport(name)
        private val connections = mutableListOf<Job>()

        init {
            scope.launch { transport.incoming.collect { engine.accept(it) } }
        }

        suspend fun join(invitation: Invitation, root: SpaceRoot? = null) {
            if (root != null) engine.joinSpace(invitation, root) else engine.join(invitation)
            connections += engine.connect(transport.connect(invitation.addresses.getValue(MEMORY)), invitation.documentId, invitation.key)
        }

        /** Connects to [address] about [documentId] with [key] only. */
        suspend fun connectWith(address: String, documentId: String, key: DocumentKey) {
            connections += engine.connect(transport.connect(address), documentId, key)
        }

        suspend fun connectTo(address: String) {
            val connected = engine.peers.value.mapTo(HashSet()) { it.documentId }
            for ((id, key) in engine.connectionKeys().distinctBy { it.first }) {
                if (id !in connected) connections += engine.connect(transport.connect(address), id, key)
            }
        }

        suspend fun access(documentId: String): DocumentAccess? = shared.stateOf(documentId)?.let(DocumentAccess::decode)

        suspend fun snapshot(documentId: String): DocumentSnapshot {
            val open = assertNotNull(documents.open(documentId))
            return open.edit { it.document.snapshot() }.also { documents.close(open) }
        }

        suspend fun rename(documentId: String, title: String) {
            val open = assertNotNull(documents.open(documentId))
            open.edit { it.submit(RenameDocument(title)) }
            documents.close(open)
        }

        val device: String get() = identity.publicKey
    }

    private suspend fun TestScope.exchange(a: Device, b: Device) {
        repeat(40) {
            advanceUntilIdle()
            val missing = listOf(a, b).any { device ->
                val connected = device.engine.peers.value.mapTo(HashSet()) { it.documentId }
                device.engine.connectionKeys().any { it.first !in connected }
            }
            if (it > 1 && !missing) return
            a.connectTo(b.name)
            b.connectTo(a.name)
        }
        advanceUntilIdle()
    }

    private fun syncTest(block: suspend TestScope.(CoroutineScope) -> Unit) = runTest(timeout = 5.minutes) {
        val failures = mutableListOf<Throwable>()
        val work = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> failures += e })
        try {
            block(work)
        } finally {
            work.cancel()
        }
        assertEquals(emptyList(), failures.map { it.toString() }, "failures not caught by the engines")
    }

    /** Ada's course shared as a folder, its notes shared on their own too; Bob in both. */
    private data class Shared(val ada: Device, val bob: Device, val spaceId: String, val notes: String)

    private suspend fun TestScope.shareBoth(work: CoroutineScope, network: InMemoryNetwork): Shared {
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        ada.library.start()
        bob.library.start()
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        val notes = ada.store.createDocument("Notes", folderId = course)
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val ofFolder = assertNotNull(ada.engine.invite(spaceId, "Course", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        val ofNotes = assertNotNull(ada.engine.invite(notes.id, "Notes", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        advanceUntilIdle()
        val adaAccess = assertNotNull(ada.access(notes.id))
        assertNotNull(adaAccess.own, "Ada's notes shared on their own")
        assertEquals(listOf(spaceId), adaAccess.spaces.map { it.space }, "and through the folder")

        bob.join(ofFolder, SpaceRoot.NewFolder)
        advanceUntilIdle()
        exchange(ada, bob)
        bob.join(ofNotes)
        advanceUntilIdle()
        exchange(ada, bob)
        val bobAccess = assertNotNull(bob.access(notes.id))
        assertNotNull(bobAccess.own?.members?.memberOf(bob.device), "Bob in the own sharing")
        assertNotNull(bobAccess.of(spaceId)?.members?.memberOf(bob.device), "and in the folder")
        assertEquals(ada.snapshot(notes.id), bob.snapshot(notes.id))
        return Shared(ada, bob, spaceId, notes.id)
    }

    @Test
    fun aDocumentInAFolderAndSharedOnItsOwnSyncsThroughEither() = syncTest { work ->
        val (ada, bob, spaceId, notes) = shareBoth(work, InMemoryNetwork())
        val own = assertNotNull(bob.access(notes)?.own?.key)
        val throughFolder = assertNotNull(bob.access(notes)?.of(spaceId)?.key)

        // Through its own sharing only
        ada.engine.stop()
        bob.engine.stop()
        advanceUntilIdle()
        ada.rename(notes, "Waves")
        bob.connectWith("ada", notes, own)
        advanceUntilIdle()
        assertEquals("Waves", bob.snapshot(notes).title)

        // Through the folder only
        ada.engine.stop()
        bob.engine.stop()
        advanceUntilIdle()
        ada.rename(notes, "Optics")
        bob.connectWith("ada", notes, throughFolder)
        advanceUntilIdle()
        assertEquals("Optics", bob.snapshot(notes).title)
    }

    @Test
    fun removedFromTheFolderTheDocumentGoesOnThroughItsOwnSharing() = syncTest { work ->
        val (ada, bob, spaceId, notes) = shareBoth(work, InMemoryNetwork())
        val oldState = assertNotNull(bob.shared.stateOf(notes)).encode()

        ada.engine.removeMember(spaceId, bob.device)
        advanceUntilIdle()
        exchange(ada, bob)
        val newKey = assertNotNull(ada.access(notes)?.of(spaceId)?.key)
        assertEquals(2, ada.access(notes)?.of(spaceId)?.epoch)
        val bobAccess = assertNotNull(bob.access(notes), "still shared with Bob")
        assertNotNull(bobAccess.own)
        assertEquals(emptyList(), bobAccess.spaces, "no more through the folder")
        assertTrue(bob.shared.all().values.flatMap { DocumentAccess.decode(it).all }.flatMap { it.keys }.none { it.documentKey == newKey })

        ada.rename(notes, "Waves")
        exchange(ada, bob)
        assertEquals("Waves", bob.snapshot(notes).title, "through its own sharing")

        // Bob's device with an application of its own, connecting through the folder with what it
        // had before (the key of epoch 1): Bob is a member of the notes' own sharing, not of the
        // folder any more, so Ada closes the connection and never gives it the new key of the folder
        bob.engine.stop()
        advanceUntilIdle()
        val before = DocumentAccess.decode(assertNotNull(SharingState.decode(oldState))).of(spaceId)
        val oldKey = assertNotNull(before?.key)
        val (mine, theirs) = InMemoryChannel.pair()
        ada.engine.accept(theirs)
        mine.send(OpenFrame(PROTOCOL_VERSION, oldKey.tag).encode())
        val channel = SecureChannel.of(mine, oldKey)
        runCurrent()
        val challenge = assertIs<Challenge>(SyncCodec.decode(assertNotNull(channel.receive())))
        val replica = bob.store.replicaId()
        val proof = bob.identity.sign(helloProof(challenge.nonce, oldKey.tag, replica, bob.device))
        val membership = assertNotNull(before.members)
        channel.send(SyncCodec.encode(Hello(replica, "bob", emptyMap(), hasDocument = true, device = bob.device, proof = proof, membershipVersion = membership.version, epoch = membership.epoch)))
        advanceUntilIdle()
        val received = buildList {
            while (true) {
                val next = backgroundScope.async { channel.receive() }
                runCurrent()
                if (!next.isCompleted) {
                    next.cancel()
                    break
                }
                add(SyncCodec.decode(next.await() ?: break) ?: continue)
            }
        }
        assertTrue(received.none { it is KeyUpdate }, "never the new key of the folder: $received")
        assertTrue(ada.engine.peers.value.none { it.device == bob.device && it.documentId == notes }, "not working with Ada through the folder")
    }

    @Test
    fun removedFromTheDocumentItGoesOnThroughTheFolder() = syncTest { work ->
        val (ada, bob, spaceId, notes) = shareBoth(work, InMemoryNetwork())
        ada.engine.removeMember(notes, bob.device)
        advanceUntilIdle()
        exchange(ada, bob)
        val bobAccess = assertNotNull(bob.access(notes))
        assertNull(bobAccess.own, "no more on its own")
        assertEquals(listOf(spaceId), bobAccess.spaces.map { it.space })
        assertEquals(2, ada.access(notes)?.own?.epoch)

        ada.rename(notes, "Waves")
        exchange(ada, bob)
        assertEquals("Waves", bob.snapshot(notes).title, "through the folder")
        assertEquals(Role.EDITOR, bob.engine.spaceMemberships.value[spaceId]?.memberOf(bob.device)?.role)

        // Bob still writes, as an editor of the folder: his changes taken by Ada
        bob.rename(notes, "Light")
        exchange(ada, bob)
        assertEquals("Light", ada.snapshot(notes).title)
    }

    @Test
    fun theOwnSharingEndedLeavesTheFolder() = syncTest { work ->
        val (ada, bob, spaceId, notes) = shareBoth(work, InMemoryNetwork())
        ada.engine.unshare(notes)
        advanceUntilIdle()
        exchange(ada, bob)
        assertNull(ada.access(notes)?.own, "Ada's own sharing ended")
        assertEquals(listOf(spaceId), ada.access(notes)?.spaces?.map { it.space })
        assertNull(bob.access(notes)?.own)
        assertEquals(listOf(spaceId), bob.access(notes)?.spaces?.map { it.space })

        ada.rename(notes, "Waves")
        exchange(ada, bob)
        assertEquals("Waves", bob.snapshot(notes).title)
    }

    @Test
    fun theDocumentMovedOutOfTheFolderGoesOnThroughItsOwnSharing() = syncTest { work ->
        val (ada, bob, spaceId, notes) = shareBoth(work, InMemoryNetwork())
        assertTrue(ada.store.moveDocument(notes, null))
        advanceUntilIdle()
        exchange(ada, bob)
        assertEquals(emptyList(), ada.access(notes)?.spaces, "out of the folder")
        assertEquals(emptyList(), bob.access(notes)?.spaces)
        assertNotNull(bob.access(notes)?.own)
        assertTrue(bob.store.documents.first().any { it.id == notes }, "Bob keeps it")
        assertTrue(ada.library.view(spaceId).none { it.id == notes })

        ada.rename(notes, "Waves")
        exchange(ada, bob)
        assertEquals("Waves", bob.snapshot(notes).title, "through its own sharing")
    }

    private companion object {
        const val MEMORY = "memory"
    }
}
