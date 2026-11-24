package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.model.change.DocumentSnapshot
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.model.change.snapshot
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.space.CatalogItemKind
import io.github.valerioisufi.pageboard.store.space.SpaceLibrary
import io.github.valerioisufi.pageboard.sync.protocol.DeviceIdentity
import io.github.valerioisufi.pageboard.sync.protocol.InMemoryNetwork
import io.github.valerioisufi.pageboard.sync.protocol.Invitation
import io.github.valerioisufi.pageboard.sync.protocol.InvitationKind
import io.github.valerioisufi.pageboard.sync.protocol.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** Shared folders between devices, through their engines (see `docs/plans/shared-folders.md`). */
class SharedFoldersSyncTest {

    /** A device keeping spaces: its store, its library of spaces, its engine, reachable at [name]. */
    private class Device(val name: String, scope: CoroutineScope, test: TestScope, network: InMemoryNetwork) {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val shared = StoreSharedDocuments(store, documents)
        val identity = DeviceIdentity.generate()
        val library = SpaceLibrary(store, store, scope, sharedApart = { _, secret -> SharingState.decode(secret)?.space == null })
        val engine = SyncEngine(documents, shared, scope, { name }, identity = identity, keepAliveMs = 0, spaces = library)
        val transport = network.transport(name)
        private val connections = mutableListOf<Job>()

        init {
            scope.launch { transport.incoming.collect { engine.accept(it) } }
        }

        /** Joins the shared folder of [invitation] through its owner, its root where [root] says. */
        suspend fun join(invitation: Invitation, root: SpaceRoot) {
            engine.joinSpace(invitation, root)
            connections += engine.connect(transport.connect(invitation.addresses.getValue(MEMORY)), invitation.documentId, invitation.key)
        }

        /**
         * Connects to [address] about what this device connects with now and is not connected
         * about (as the controllers of the transports do): its shared folders, the documents of
         * them wanted (see `SyncEngine.reachable`).
         */
        suspend fun connectTo(address: String) {
            val connected = engine.peers.value.mapTo(HashSet()) { it.documentId }
            for ((id, key) in engine.connectionKeys().distinctBy { it.first }) {
                if (id !in connected) connections += engine.connect(transport.connect(address), id, key)
            }
        }

        /** The documents this device has a connection about now (its shared folders aside). */
        fun documentsConnected(spaceId: String): Set<String> = engine.peers.value.map { it.documentId }.filterTo(HashSet()) { it != spaceId }

        suspend fun snapshot(documentId: String): DocumentSnapshot {
            val open = assertNotNull(documents.open(documentId))
            return open.edit { it.document.snapshot() }.also { documents.close(open) }
        }

        suspend fun rename(documentId: String, title: String) {
            val open = assertNotNull(documents.open(documentId))
            open.edit { it.submit(RenameDocument(title)) }
            documents.close(open)
        }

        suspend fun folderOf(id: String): String? =
            store.folders.first().firstOrNull { it.id == id }?.parentId ?: store.documents.first().firstOrNull { it.id == id }?.folderId
    }

    /** The two devices connect about what they want, again and again, until nothing is left to connect about (as the transports do). */
    private suspend fun TestScope.exchange(a: Device, b: Device) {
        // A few connections at a time are taken from devices not known yet: the others again next time
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
        // What fails in an engine without being caught fails the test
        val failures = mutableListOf<Throwable>()
        val work = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> failures += e })
        try {
            block(work)
        } finally {
            work.cancel()
        }
        assertEquals(emptyList(), failures.map { it.toString() }, "failures not caught by the engines")
    }

    @Test
    fun aFolderSharedKeepsItsDocumentsAndFoldersTheSameOnBothDevices() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        ada.library.start()
        bob.library.start()

        // Ada's course: a week, and notes in it
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        assertTrue(ada.store.createFolder("Week 1", course))
        val week = ada.store.folders.first().single { it.name == "Week 1" }.id
        val notes = ada.store.createDocument("Notes", folderId = week)
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val invitation = assertNotNull(ada.engine.invite(spaceId, "Course", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        assertEquals(InvitationKind.FOLDER, invitation.kind)
        assertEquals(invitation, Invitation.parse(invitation.toLink()), "the link of a folder read back")
        assertTrue(ada.store.documents.first().single().isShared)

        // Bob joins into a new folder: the folders come, then the document from Ada
        bob.join(invitation, SpaceRoot.NewFolder)
        advanceUntilIdle()
        val bobRoot = assertNotNull(bob.library.spaces().singleOrNull()).rootFolderId
        assertEquals("Course", bob.store.folders.first().single { it.id == bobRoot }.name)
        assertEquals(bobRoot, bob.folderOf(week))
        exchange(ada, bob)
        assertEquals(week, bob.folderOf(notes.id), "the document where the folder has it")
        assertEquals(ada.snapshot(notes.id), bob.snapshot(notes.id))
        assertEquals(setOf("ada", "bob"), ada.engine.spaceMemberships.value.getValue(spaceId).members.map { it.name }.toSet())

        // Bob makes a folder and a document in it: they reach Ada, the document from Bob
        assertTrue(bob.store.createFolder("Exercises", week))
        val exercises = bob.store.folders.first().single { it.name == "Exercises" }.id
        val bobs = bob.store.createDocument("Bob's", folderId = exercises)
        advanceUntilIdle()
        assertEquals(week, ada.folderOf(exercises))
        exchange(ada, bob)
        assertEquals(exercises, ada.folderOf(bobs.id))
        assertEquals(bob.snapshot(bobs.id), ada.snapshot(bobs.id))

        // A change of a document of the folder, live
        ada.rename(notes.id, "Waves")
        exchange(ada, bob)
        assertEquals("Waves", bob.snapshot(notes.id).title)

        // A folder moved and the notes put in the trash on Ada: so on Bob
        assertTrue(ada.store.moveFolder(exercises, course))
        assertTrue(ada.store.trashDocument(notes.id))
        advanceUntilIdle()
        assertEquals(bobRoot, bob.folderOf(exercises))
        assertTrue(bob.store.trash.first().any { it.id == notes.id })

        // Bob removed: every document gets a new key, Bob keeps his copies, out of the folder
        ada.engine.removeMember(spaceId, bob.device())
        advanceUntilIdle()
        assertEquals(emptyList(), bob.library.spaces())
        assertTrue(bob.shared.all().values.none { it.space == spaceId })
        assertEquals(2, assertNotNull(ada.shared.stateOf(bobs.id)).epoch)
        assertTrue(bob.store.documents.first().any { it.id == bobs.id }, "Bob keeps his copies")
        assertNull(ada.engine.spaceMemberships.value.getValue(spaceId).memberOf(bob.device()))
    }

    @Test
    fun aDocumentIsTakenFromTheOwnerWhenTheOwnerIsThere() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        val carl = Device("carl", work, this, network)
        listOf(ada, bob, carl).forEach { it.library.start() }
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val invitation = assertNotNull(ada.engine.invite(spaceId, "Course", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        bob.join(invitation, SpaceRoot.NewFolder)
        advanceUntilIdle()
        exchange(ada, bob)
        // Bob's notes, given to Ada
        val notes = bob.store.createDocument("Bob's", folderId = bob.library.spaces().single().rootFolderId)
        advanceUntilIdle()
        exchange(ada, bob)
        assertEquals(bob.snapshot(notes.id), ada.snapshot(notes.id))

        // Carl joins through Ada, then meets Bob: Ada has the notes and is connected, so Carl waits for hers
        carl.join(invitation, SpaceRoot.NewFolder)
        advanceUntilIdle()
        exchange(carl, bob)
        assertTrue(carl.store.documents.first().none { it.id == notes.id }, "not taken from Bob while Ada is there")
        exchange(carl, ada)
        assertEquals(ada.snapshot(notes.id), carl.snapshot(notes.id))

        // Dan joins while Ada is away: the notes from Bob, who brought them
        val dan = Device("dan", work, this, network)
        dan.library.start()
        dan.join(invitation, SpaceRoot.NewFolder)
        advanceUntilIdle()
        ada.engine.stop()
        advanceUntilIdle()
        exchange(dan, bob)
        assertEquals(bob.snapshot(notes.id), dan.snapshot(notes.id))
    }

    @Test
    fun theLibraryJoinedIntoTheLibraryBringsBothTogether() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        ada.library.start()
        bob.library.start()
        val adas = ada.store.createDocument("Ada's")
        assertTrue(bob.store.createFolder("Bob's folder", null))
        val bobsFolder = bob.store.folders.first().single().id
        val bobs = bob.store.createDocument("Bob's", folderId = bobsFolder)

        val spaceId = assertNotNull(ada.engine.shareSpace(null))
        val invitation = assertNotNull(ada.engine.invite(spaceId, "Library", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        assertEquals(InvitationKind.LIBRARY, invitation.kind)
        bob.join(invitation, SpaceRoot.Library)
        exchange(ada, bob)

        // Each library has what both had, in the same places
        assertNull(ada.folderOf(adas.id))
        assertNull(bob.folderOf(adas.id))
        assertEquals(bobsFolder, ada.folderOf(bobs.id))
        assertEquals(ada.snapshot(bobs.id), bob.snapshot(bobs.id))
        assertEquals(ada.snapshot(adas.id), bob.snapshot(adas.id))
        val view = ada.library.view(spaceId)
        assertEquals(setOf(adas.id, bobs.id, bobsFolder), view.map { it.id }.toSet())
        assertEquals(view.filter { it.kind == CatalogItemKind.DOCUMENT }.map { it.id }.toSet(), bob.library.view(spaceId).filter { it.kind == CatalogItemKind.DOCUMENT }.map { it.id }.toSet())
    }

    @Test
    fun theLibraryAdmittedOverTwoConnectionsMakesNoFolder() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        ada.library.start()
        bob.library.start()
        val adas = ada.store.createDocument("Ada's")
        val spaceId = assertNotNull(ada.engine.shareSpace(null))
        val invitation = assertNotNull(ada.engine.invite(spaceId, "Library", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))

        // Two connections at once (the devices reaching each other on the local network): two admissions
        bob.engine.joinSpace(invitation, SpaceRoot.Library)
        repeat(2) { bob.engine.connect(bob.transport.connect("ada"), invitation.documentId, invitation.key) }
        advanceUntilIdle()

        // Into the library as chosen, once: no folder named after the library
        assertNull(bob.library.spaces().single().rootFolderId)
        assertEquals(emptyList(), bob.store.folders.first().map { it.name })
        assertEquals(emptyList(), ada.store.folders.first().map { it.name })
        assertNull(bob.folderOf(adas.id))
    }

    @Test
    fun aFolderInvitationOpenedAgainIsJoinedAlready() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        ada.library.start()
        bob.library.start()
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val invitation = assertNotNull(ada.engine.invite(spaceId, "Course", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        bob.join(invitation, SpaceRoot.NewFolder)
        advanceUntilIdle()
        val root = bob.library.spaces().single().rootFolderId

        // Bob opens the invitation again: in at once, the folder where it is, no other one
        bob.engine.joinSpace(invitation, SpaceRoot.NewFolder)
        assertEquals(JoinStatus.ADMITTED, bob.engine.joining.value[spaceId])
        advanceUntilIdle()
        assertEquals(root, bob.library.spaces().single().rootFolderId)
        assertEquals(listOf("Course"), bob.store.folders.first().map { it.name })
    }

    @Test
    fun aReaderChangesNothingOfTheFolder() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        ada.library.start()
        bob.library.start()
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        assertTrue(ada.store.createFolder("Week 1", course))
        val week = ada.store.folders.first().single { it.name == "Week 1" }.id
        val spaceId = assertNotNull(ada.engine.shareSpace(course))
        val invitation = assertNotNull(ada.engine.invite(spaceId, "Course", Role.READER, addresses = mapOf(MEMORY to "ada")))
        bob.join(invitation, SpaceRoot.NewFolder)
        advanceUntilIdle()
        val bobRoot = assertNotNull(bob.library.spaces().single().rootFolderId)
        assertTrue(bob.library.spaces().single().readOnly)

        // Bob renames the week: put back, nothing reaches Ada
        assertTrue(bob.store.renameFolder(week, "Mine"))
        advanceUntilIdle()
        assertEquals("Week 1", bob.store.folders.first().single { it.id == week }.name)
        assertEquals("Week 1", ada.store.folders.first().single { it.id == week }.name)
        assertEquals(bobRoot, bob.folderOf(week))
    }

    /**
     * A library of 200 documents shared: once both devices have them all, they keep a connection
     * about the library only; a document changed gets a connection of its own until both have it,
     * and so do the ones changed while a device was away.
     */
    @Test
    fun aLargeLibraryConnectsAboutWhatChangedOnly() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        ada.library.start()
        bob.library.start()
        val notes = (1..200).map { ada.store.createDocument("Note $it") }
        val spaceId = assertNotNull(ada.engine.shareSpace(null))
        val invitation = assertNotNull(ada.engine.invite(spaceId, "Library", Role.EDITOR, addresses = mapOf(MEMORY to "ada")))
        assertEquals(2, ada.engine.announcedTags().size, "the library and its invitation announced, not its documents")
        bob.join(invitation, SpaceRoot.Library)
        exchange(ada, bob)
        for (note in listOf(notes.first(), notes[99], notes.last())) assertEquals(ada.snapshot(note.id), bob.snapshot(note.id))
        assertEquals(200, bob.store.documents.first().size)

        // Both have everything: a connection about the library only, once the others are idle
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(emptySet(), ada.documentsConnected(spaceId))
        assertEquals(listOf(spaceId), ada.engine.connectionKeys().map { it.first }.distinct())

        // A document changed: connected about it only, until Bob has it
        ada.rename(notes[42].id, "Changed")
        advanceUntilIdle()
        assertEquals(setOf(spaceId, notes[42].id), ada.engine.connectionKeys().map { it.first }.toSet())
        assertEquals(setOf(spaceId, notes[42].id), bob.engine.connectionKeys().map { it.first }.toSet())
        exchange(ada, bob)
        assertEquals("Changed", bob.snapshot(notes[42].id).title)
        assertEquals(listOf(spaceId), ada.engine.connectionKeys().map { it.first }.distinct())

        // Bob away while Ada changes two: back, connected about those two only
        bob.engine.stop()
        advanceUntilIdle()
        ada.rename(notes[7].id, "Seven")
        ada.rename(notes[8].id, "Eight")
        advanceUntilIdle()
        bob.connectTo("ada")
        advanceUntilIdle()
        assertEquals(setOf(spaceId, notes[7].id, notes[8].id), bob.engine.connectionKeys().map { it.first }.toSet())
        exchange(ada, bob)
        assertEquals("Seven", bob.snapshot(notes[7].id).title)
        assertEquals("Eight", bob.snapshot(notes[8].id).title)
    }

    private companion object {
        const val MEMORY = "memory"

        fun Device.device(): String = identity.publicKey
    }
}
