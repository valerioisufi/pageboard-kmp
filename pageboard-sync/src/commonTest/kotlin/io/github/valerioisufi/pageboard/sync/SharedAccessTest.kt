package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.model.change.DocumentSnapshot
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.model.change.snapshot
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.space.SharedAs
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
import io.github.valerioisufi.pageboard.sync.protocol.SpaceRef
import io.github.valerioisufi.pageboard.sync.protocol.MembershipKind
import io.github.valerioisufi.pageboard.sync.protocol.Membership
import io.github.valerioisufi.pageboard.sync.protocol.Member
import io.github.valerioisufi.pageboard.sync.protocol.Grant
import io.github.valerioisufi.pageboard.sync.protocol.Capability
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

    /** A device keeping spaces, whose library takes the documents shared on their own into the spaces as the sharing allows. */
    private class Device(val name: String, scope: CoroutineScope, test: TestScope, network: InMemoryNetwork, val identity: DeviceIdentity = DeviceIdentity.generate()) {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val shared = StoreSharedDocuments(store, documents)
        val library: SpaceLibrary = SpaceLibrary(store, store, scope, keptApart = { spaceId, documentId, _ -> engine.keepsApart(spaceId, documentId) })
        val engine: SyncEngine = SyncEngine(documents, shared, scope, { name }, identity = identity, keepAliveMs = 0, spaces = library)
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

    /**
     * Ada in both accesses, Bob in the folder only, Carl in the notes' own sharing only: each one
     * checks the others' operations with the membership of the other access, given by Ada and
     * anchored in what it trusts (the folder names the notes' own sharing, which names the folder).
     */
    @Test
    fun theMembersOfEachAccessCheckTheOthersThroughTheDeviceInBoth() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        val carl = Device("carl", work, this, network)
        listOf(ada, bob, carl).forEach { it.library.start() }
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        val notes = ada.store.createDocument("Notes", folderId = course).id
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val ofFolder = assertNotNull(ada.engine.invite(spaceId, "Course", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        val ofNotes = assertNotNull(ada.engine.invite(notes, "Notes", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        advanceUntilIdle()
        // Ada names both ways: the folder names the notes' own sharing, the sharing names the folder
        assertEquals(SharedAs(notes, ada.device), ada.library.view(spaceId).single { it.id == notes }.sharedAs)
        assertEquals(listOf(SpaceRef(spaceId, ada.device)), ada.engine.memberships.value[notes]?.spaces)

        bob.join(ofFolder, SpaceRoot.NewFolder)
        advanceUntilIdle()
        exchange(ada, bob)
        carl.join(ofNotes)
        advanceUntilIdle()
        exchange(ada, carl)
        exchange(ada, bob)
        assertNull(bob.access(notes)?.own, "Bob is not in the notes' own sharing")
        assertNull(carl.access(notes)?.of(spaceId), "Carl is not in the folder")
        val carlReplica = carl.store.replicaId()
        val bobReplica = bob.store.replicaId()
        assertEquals(bob.device, carl.engine.unionOf(notes)?.deviceOf(bobReplica), "Carl learned the folder's membership")

        // Carl's change reaches Bob through Ada (who gives Bob the notes' own membership first, on
        // the connection about the notes), and Bob's Carl
        carl.rename(notes, "From Carl")
        exchange(ada, carl)
        exchange(ada, bob)
        assertEquals(carl.device, bob.engine.unionOf(notes)?.deviceOf(carlReplica), "Bob learned the notes' own membership")
        assertEquals("From Carl", bob.snapshot(notes).title)
        bob.rename(notes, "From Bob")
        exchange(ada, bob)
        exchange(ada, carl)
        assertEquals("From Bob", carl.snapshot(notes).title)
        assertEquals(ada.snapshot(notes), bob.snapshot(notes))
        assertEquals(ada.snapshot(notes), carl.snapshot(notes))

        // Not anchored: a membership of the notes made by Bob, naming the folder (the folder names Ada
        // as the owner of their own sharing), and one of the folder made by Carl
        val forgedOwn = Membership(notes, bob.device, version = 99, members = listOf(Member(bob.device, bobReplica, "Bob", Role.OWNER)), spaces = listOf(SpaceRef(spaceId, ada.device)))
        assertTrue(!bob.engine.isAnchored(notes, null, forgedOwn, bob.device, null))
        val realOwn = assertNotNull(ada.engine.memberships.value[notes])
        assertTrue(bob.engine.isAnchored(notes, null, realOwn, ada.device, null))
        val forgedSpace = Membership(spaceId, carl.device, version = 99, members = listOf(Member(carl.device, carlReplica, "Carl", Role.OWNER)), kind = MembershipKind.SPACE)
        assertTrue(!carl.engine.isAnchored(notes, spaceId, forgedSpace, carl.device, null))

        // Bob takes the notes out of the folder and puts them back: he brought them now, the anchor
        // stays the folder's naming of their owner
        val bobRoot = bob.library.spaces().single().rootFolderId
        assertTrue(bob.store.moveDocument(notes, null))
        advanceUntilIdle()
        exchange(ada, bob)
        assertTrue(bob.store.moveDocument(notes, bobRoot))
        advanceUntilIdle()
        exchange(ada, bob)
        assertEquals(bobReplica, ada.library.view(spaceId).single { it.id == notes }.broughtBy)
        assertEquals(SharedAs(notes, ada.device), bob.library.view(spaceId).single { it.id == notes }.sharedAs)
        assertTrue(!bob.engine.isAnchored(notes, null, forgedOwn, bob.device, null))

        // Bob removed from the folder: Carl learns it through Ada, and takes nothing more of Bob
        ada.engine.removeMember(spaceId, bob.device)
        advanceUntilIdle()
        exchange(ada, carl)
        val union = assertNotNull(carl.engine.unionOf(notes))
        assertTrue(union.mayWrite(bobReplica, 1), "what Bob wrote before stays")
        assertTrue(!union.mayWrite(bobReplica, 10_000), "nothing after")
    }

    /** The notes leaving the folder: the members of their own sharing take nothing more of the folder's members after. */
    @Test
    fun theNotesLeavingTheFolderTheirOwnSharingSaysUpToWhere() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        val carl = Device("carl", work, this, network)
        listOf(ada, bob, carl).forEach { it.library.start() }
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        val notes = ada.store.createDocument("Notes", folderId = course).id
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val ofFolder = assertNotNull(ada.engine.invite(spaceId, "Course", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        val ofNotes = assertNotNull(ada.engine.invite(notes, "Notes", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        bob.join(ofFolder, SpaceRoot.NewFolder)
        advanceUntilIdle()
        exchange(ada, bob)
        carl.join(ofNotes)
        advanceUntilIdle()
        exchange(ada, carl)
        val bobReplica = bob.store.replicaId()
        bob.rename(notes, "From Bob")
        exchange(ada, bob)
        exchange(ada, carl)
        assertEquals("From Bob", carl.snapshot(notes).title)

        assertTrue(ada.store.moveDocument(notes, null))
        advanceUntilIdle()
        exchange(ada, carl)
        val named = assertNotNull(carl.engine.memberships.value[notes]?.spaces?.single())
        val upTo = assertNotNull(named.writesUpTo, "the folder left, said by the owner")
        assertTrue((upTo[bobReplica] ?: 0L) >= 1)
        val union = assertNotNull(carl.engine.unionOf(notes))
        assertTrue(union.mayWrite(bobReplica, upTo.getValue(bobReplica)))
        assertTrue(!union.mayWrite(bobReplica, upTo.getValue(bobReplica) + 1), "nothing of Bob after the notes left the folder")
    }

    /** A document of a shared folder is shared on its own only by the owner of the folder or a member it granted. */
    @Test
    fun aDocumentOfAFolderIsSharedOnItsOwnAsTheFolderAllows() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        listOf(ada, bob).forEach { it.library.start() }
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        val notes = ada.store.createDocument("Notes", folderId = course).id
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val ofFolder = assertNotNull(ada.engine.invite(spaceId, "Course", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        bob.join(ofFolder, SpaceRoot.NewFolder)
        advanceUntilIdle()
        exchange(ada, bob)
        assertNull(bob.engine.invite(notes, "Notes", Role.EDITOR, addresses = mapOf(MEMORY to "bob")), "not granted")
        assertNull(bob.access(notes)?.own)

        ada.engine.setGrants(spaceId, listOf(Grant(Capability.SHARE_INSIDE, Role.EDITOR)))
        advanceUntilIdle()
        exchange(ada, bob)
        assertNotNull(bob.engine.invite(notes, "Notes", Role.EDITOR, addresses = mapOf(MEMORY to "bob")), "granted to the editors")
        advanceUntilIdle()
        exchange(ada, bob)
        assertEquals(SharedAs(notes, bob.device), ada.library.view(spaceId).single { it.id == notes }.sharedAs, "the folder names Bob's sharing")
    }

    /** A document of Carl's, shared with Ada and moved into her folder: out until Carl names the folder. */
    @Test
    fun aDocumentOwnedByAnotherDeviceComesInWithItsOwnersConsent() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        val carl = Device("carl", work, this, network)
        listOf(ada, bob, carl).forEach { it.library.start() }
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val ofFolder = assertNotNull(ada.engine.invite(spaceId, "Course", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        bob.join(ofFolder, SpaceRoot.NewFolder)
        advanceUntilIdle()
        exchange(ada, bob)

        val lab = carl.store.createDocument("Lab").id
        val ofLab = assertNotNull(carl.engine.invite(lab, "Lab", Role.EDITOR, addresses = mapOf(MEMORY to "carl")))
        ada.join(ofLab)
        advanceUntilIdle()
        exchange(ada, carl)
        assertTrue(ada.store.documents.first().any { it.id == lab })
        assertTrue(ada.store.moveDocument(lab, course))
        advanceUntilIdle()
        assertTrue(ada.library.view(spaceId).none { it.id == lab }, "Carl's: out of Ada's folder without his consent")

        // Carl names Ada's folder: it comes in, named as his sharing
        assertTrue(carl.engine.place(lab, spaceId, ada.device))
        advanceUntilIdle()
        exchange(ada, carl)
        assertTrue(ada.library.view(spaceId).any { it.id == lab }, "in with its owner's consent")
        assertEquals(SharedAs(lab, carl.device), ada.library.view(spaceId).single { it.id == lab }.sharedAs)
        exchange(ada, bob)
        assertNotNull(bob.access(lab)?.of(spaceId), "Bob reaches it through the folder")
        assertTrue(bob.store.documents.first().any { it.id == lab }, "Bob has it")
        assertTrue(bob.engine.isAnchored(lab, null, assertNotNull(carl.engine.memberships.value[lab]), carl.device, null), "Carl's own sharing anchored for Bob")
    }

    /** Carl had the notes directly, then joins the folder holding them: they go where the folder has them, still in it. */
    @Test
    fun aDocumentHadDirectlyThenThroughTheFolderStaysInIt() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val carl = Device("carl", work, this, network)
        listOf(ada, carl).forEach { it.library.start() }
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        val notes = ada.store.createDocument("Notes", folderId = course).id
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val ofNotes = assertNotNull(ada.engine.invite(notes, "Notes", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        carl.join(ofNotes)
        advanceUntilIdle()
        exchange(ada, carl)
        assertTrue(carl.store.documents.first().any { it.id == notes })

        val ofFolder = assertNotNull(ada.engine.invite(spaceId, "Course", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        carl.join(ofFolder, SpaceRoot.NewFolder)
        advanceUntilIdle()
        exchange(ada, carl)
        val carlRoot = carl.library.spaces().single().rootFolderId
        assertEquals(carlRoot, carl.store.documents.first().single { it.id == notes }.folderId, "where the folder has it")
        assertTrue(ada.library.view(spaceId).any { it.id == notes }, "never taken out of the folder")
        assertNotNull(carl.access(notes)?.own)
        assertEquals(listOf(spaceId), carl.access(notes)?.spaces?.map { it.space })
    }

    /**
     * The library shared between Ada's two devices, and one of its folders shared with Carl (a
     * shared folder inside the shared library, see the plan, phase 5): Ada's computer in both,
     * her phone in the library only, Carl in the folder only.
     */
    @Test
    fun aFolderOfTheSharedLibrarySharedWithAThirdDevice() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val phone = Device("phone", work, this, network)
        val carl = Device("carl", work, this, network)
        listOf(ada, phone, carl).forEach { it.library.start() }
        assertTrue(ada.store.createFolder("Lab", null))
        val lab = ada.store.folders.first().single().id
        val notes = ada.store.createDocument("Measures", folderId = lab).id
        val library = assertNotNull(ada.engine.shareSpace(null))
        val ofLibrary = assertNotNull(ada.engine.invite(library, "Library", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        phone.join(ofLibrary, SpaceRoot.Library)
        advanceUntilIdle()
        exchange(ada, phone)
        assertTrue(phone.store.documents.first().any { it.id == notes })

        // The lab shared with Carl, inside the library: the library names it, it names the library
        val labSpace = assertNotNull(ada.engine.shareSpace(lab), "a folder of the shared library shared on its own")
        advanceUntilIdle()
        assertEquals(SharedAs(labSpace, ada.device), ada.library.view(library).single { it.id == lab }.sharedAs)
        assertEquals(listOf(SpaceRef(library, ada.device)), ada.engine.spaceMemberships.value[labSpace]?.spaces)
        val ofLab = assertNotNull(ada.engine.invite(labSpace, "Lab", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        carl.join(ofLab, SpaceRoot.NewFolder)
        advanceUntilIdle()
        exchange(ada, carl)
        exchange(ada, phone)
        assertEquals(ada.snapshot(notes), carl.snapshot(notes))

        // Carl's change reaches the phone through Ada, the phone's reaches Carl
        carl.rename(notes, "From Carl")
        exchange(ada, carl)
        exchange(ada, phone)
        assertEquals("From Carl", phone.snapshot(notes).title)
        phone.rename(notes, "From the phone")
        exchange(ada, phone)
        exchange(ada, carl)
        assertEquals("From the phone", carl.snapshot(notes).title)

        // A folder Carl makes in the lab reaches the phone, through both catalogs
        val carlRoot = carl.library.spaces().single().rootFolderId
        assertTrue(carl.store.createFolder("Week 1", carlRoot))
        val week = carl.store.folders.first().single { it.name == "Week 1" }.id
        advanceUntilIdle()
        exchange(ada, carl)
        exchange(ada, phone)
        assertEquals(lab, phone.store.folders.first().singleOrNull { it.id == week }?.parentId, "in the lab on the phone")

        // The phone joins the lab where it is: no new folder
        val folders = phone.store.folders.first().size
        val forPhone = assertNotNull(ada.engine.invite(labSpace, "Lab", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        phone.join(forPhone, SpaceRoot.InPlace)
        advanceUntilIdle()
        exchange(ada, phone)
        assertEquals(lab, phone.library.spaces().single { it.id == labSpace }.rootFolderId, "its root where the library has it")
        assertEquals(folders, phone.store.folders.first().size)
    }

    /** An editor of a shared folder shares a folder inside it only as the owner allows. */
    @Test
    fun aFolderIsSharedInsideAsTheOwnerAllows() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        listOf(ada, bob).forEach { it.library.start() }
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        assertTrue(ada.store.createFolder("Lab", course))
        val lab = ada.store.folders.first().single { it.name == "Lab" }.id
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val ofFolder = assertNotNull(ada.engine.invite(spaceId, "Course", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        bob.join(ofFolder, SpaceRoot.NewFolder)
        advanceUntilIdle()
        exchange(ada, bob)
        assertNull(bob.engine.shareSpace(lab), "Only the owner of the course decides")
        ada.engine.setGrants(spaceId, listOf(Grant(Capability.SHARE_INSIDE, Role.EDITOR)))
        advanceUntilIdle()
        exchange(ada, bob)
        val inner = assertNotNull(bob.engine.shareSpace(lab), "the editors may now")
        advanceUntilIdle()
        exchange(ada, bob)
        assertEquals(SharedAs(inner, bob.device), ada.library.view(spaceId).single { it.id == lab }.sharedAs, "the course names Bob's folder")
    }

    private companion object {
        const val MEMORY = "memory"
    }
}
