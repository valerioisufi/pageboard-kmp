package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.space.AddDocument
import io.github.valerioisufi.pageboard.store.space.CatalogOperation
import io.github.valerioisufi.pageboard.store.space.CompositeCatalogChange
import io.github.valerioisufi.pageboard.store.space.ConfirmGiver
import io.github.valerioisufi.pageboard.store.space.ShareItem
import io.github.valerioisufi.pageboard.store.space.UnshareItem
import io.github.valerioisufi.pageboard.store.space.MoveItem
import io.github.valerioisufi.pageboard.store.space.SpaceLibrary
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.sync.protocol.Capability
import io.github.valerioisufi.pageboard.sync.protocol.DeviceIdentity
import io.github.valerioisufi.pageboard.sync.protocol.Grant
import io.github.valerioisufi.pageboard.sync.protocol.DocumentKey
import io.github.valerioisufi.pageboard.sync.protocol.Membership
import io.github.valerioisufi.pageboard.sync.protocol.MembershipKind
import io.github.valerioisufi.pageboard.sync.protocol.OperationProof
import io.github.valerioisufi.pageboard.sync.protocol.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class SpaceSharingTest {

    /** A device: its store, its library of spaces, the sharing of its documents and of its spaces. */
    private class Device(scope: CoroutineScope, val name: String) {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val identity = DeviceIdentity.generate()
        val library = SpaceLibrary(store, store, scope, sharedApart = { _, secret -> SharingState.decode(secret)?.space == null })
        val documents = DocumentSharing(StoreSharedDocuments(store), identity)
        val sharing = SpaceSharing(library, identity, documents)
        val device: String get() = identity.publicKey
        suspend fun replica() = store.replicaId()
    }

    @Test
    fun derivedKeysAreOfEachDocument() {
        val key = DocumentKey.generate()
        val a = key.forDocument("a")
        assertEquals(a, key.forDocument("a"), "the same on every device")
        assertNotEquals(a, key.forDocument("b"))
        assertNotEquals(key, a)
        assertNotEquals(a.tag, key.forDocument("b").tag)
        assertNotEquals(a, DocumentKey.generate().forDocument("a"), "of the key of the space")
    }

    @Test
    fun theMembershipOfADocumentIsWrittenAsBefore() {
        val document = Membership("d", owner = "ada").encode().decodeToString()
        assertFalse("kind" in document, document)
        assertTrue("\"kind\":\"SPACE\"" in Membership("s", owner = "ada", kind = MembershipKind.SPACE).encode().decodeToString())
        assertFalse("space" in SharingState(owner = "ada").encode())
    }

    @Test
    fun theChangesOfACatalogAreSignedApart() {
        val ada = DeviceIdentity.generate()
        val content = byteArrayOf(1, 2, 3)
        val catalog = OperationProof.signCatalog(ada, "space", "r", 1, content)
        assertTrue(catalog.isCatalogBy(ada.publicKey, "space", "r", 1))
        assertFalse(catalog.isBy(ada.publicKey, "space", "r", 1), "not an operation of a document")
        assertFalse(catalog.isCatalogBy(ada.publicKey, "other", "r", 1))
        assertFalse(catalog.isCatalogBy(ada.publicKey, "space", "r", 2))
        assertFalse(OperationProof.sign(ada, "space", "r", 1, content).isCatalogBy(ada.publicKey, "space", "r", 1))
    }

    @Test
    fun theDocumentsOfASpaceFollowItsMembership() = runTest {
        val ada = Device(backgroundScope, "Ada")
        val bob = Device(backgroundScope, "Bob")
        ada.library.start()
        bob.library.start()
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.store.folders.first().single().id
        val notes = ada.store.createDocument("Notes", folderId = course)
        val apart = ada.store.createDocument("Apart", folderId = course)
        ada.documents.share(apart.id, ada.replica(), "Ada")

        // Shared: the notes get the sharing of the space, the document shared on its own keeps its own
        val space = assertNotNull(ada.library.share(course))
        val spaceState = ada.sharing.share(space.id, ada.replica(), "Ada")
        val notesState = assertNotNull(ada.documents.stateOf(notes.id))
        assertEquals(space.id, notesState.space)
        assertEquals(ada.device, notesState.owner)
        assertEquals(spaceState.key!!.forDocument(notes.id), notesState.key)
        assertEquals(MembershipKind.SPACE, notesState.members?.kind)
        assertNull(ada.documents.stateOf(apart.id)?.space)
        assertTrue(ada.store.documents.first().single { it.id == notes.id }.isShared)
        ada.library.reconcile()
        assertEquals(listOf(notes.id), ada.library.view(space.id).map { it.id }, "still in the space: its secret is the space's")

        // Bob admitted: on every document of the space
        val signed = assertNotNull(ada.sharing.spaces.admit(space.id, bob.device, bob.replica(), "Bob", Role.EDITOR))
        ada.sharing.follow(space.id)
        assertEquals(Role.EDITOR, ada.documents.membershipOf(notes.id)?.memberOf(bob.device)?.role)

        // Bob joins: the key and the membership of the space, then its catalog
        assertTrue(bob.store.createFolder("From Ada", null))
        val bobRoot = bob.store.folders.first().single().id
        assertNotNull(bob.library.join(space.id, bobRoot))
        val adaKey = assertNotNull(ada.sharing.spaces.stateOf(space.id))
        assertNotNull(bob.sharing.spaces.admitted(space.id, ada.device, adaKey.epoch, adaKey.keys.last().key, signed.membership!!))
        bob.library.receive(space.id, ada.store.catalogOperationsAfter(space.id, emptyMap()))
        bob.sharing.follow(space.id)
        assertEquals(notesState.key, bob.documents.stateOf(notes.id)?.key, "the same key on both devices, from the space")
        assertEquals(space.id, bob.documents.stateOf(notes.id)?.space)

        // The changes of the catalog: signed by the device of their replica, as its role allows
        val bobReplica = bob.replica()
        val change = CatalogOperation(OperationId(bobReplica, 1), Stamp(currentTimeMillis(), 0, bobReplica), MoveItem(notes.id, null))
        val content = byteArrayOf(7)
        assertTrue(ada.sharing.admitsChange(space.id, change, OperationProof.signCatalog(bob.identity, space.id, bobReplica, 1, content)))
        val carl = DeviceIdentity.generate()
        assertFalse(ada.sharing.admitsChange(space.id, change, OperationProof.signCatalog(carl, space.id, bobReplica, 1, content)), "as Bob")
        assertFalse(ada.sharing.admitsChange(space.id, change, OperationProof.sign(bob.identity, space.id, bobReplica, 1, content)), "signed as a document")
        val unknown = change.copy(id = OperationId("unknown", 1), stamp = Stamp(1, 0, "unknown"))
        assertFalse(ada.sharing.admitsChange(space.id, unknown, OperationProof.signCatalog(carl, space.id, "unknown", 1, content)), "of no member")

        // A document Bob brings in: given by Bob or by Ada, not by another member
        val brought = CatalogOperation(OperationId(bobReplica, 1), Stamp(currentTimeMillis(), 0, bobReplica), AddDocument("bobs", null))
        ada.library.receive(space.id, listOf(brought))
        assertTrue(ada.sharing.mayGive(space.id, "bobs", bob.device))
        assertTrue(ada.sharing.mayGive(space.id, "bobs", ada.device))
        assertTrue(ada.sharing.mayGive(space.id, notes.id, ada.device))
        assertFalse(ada.sharing.mayGive(space.id, notes.id, bob.device), "brought by Ada")
        assertFalse(ada.sharing.mayGive(space.id, "bobs", carl.publicKey))

        // Bob adding again the notes Ada brought, while they are in the space: Ada still brought
        // them, and Bob may not give them (see docs/plans/brought-by.md)
        val again = CatalogOperation(OperationId(bobReplica, 2), Stamp(currentTimeMillis() + 1, 0, bobReplica), AddDocument(notes.id, null))
        ada.library.receive(space.id, listOf(again))
        assertFalse(ada.sharing.mayGive(space.id, notes.id, bob.device), "added again by Bob, brought by Ada")
        assertTrue(ada.sharing.mayGive(space.id, notes.id, ada.device))
        assertTrue(ada.sharing.mayGive(space.id, "bobs", bob.device))

        // Who gives a document: the owner confirms it (its notes, which it has), and only the owner
        assertEquals(ada.replica(), ada.library.view(space.id).single { it.id == notes.id }.giver)
        val confirm = CatalogOperation(OperationId(bobReplica, 3), Stamp(currentTimeMillis() + 2, 0, bobReplica), ConfirmGiver("bobs", bobReplica))
        assertFalse(ada.sharing.admitsChange(space.id, confirm, OperationProof.signCatalog(bob.identity, space.id, bobReplica, 3, content)), "not the owner")
        val inside = confirm.copy(change = CompositeCatalogChange(listOf(MoveItem("bobs", null), ConfirmGiver("bobs", bobReplica))))
        assertFalse(ada.sharing.admitsChange(space.id, inside, OperationProof.signCatalog(bob.identity, space.id, bobReplica, 3, content)), "in a composite")
        val adaReplica = ada.replica()
        val ofAda = CatalogOperation(OperationId(adaReplica, 99), Stamp(currentTimeMillis() + 2, 0, adaReplica), ConfirmGiver("bobs", bobReplica))
        assertTrue(ada.sharing.admitsChange(space.id, ofAda, OperationProof.signCatalog(ada.identity, space.id, adaReplica, 99, content)))
        // Bob's document, not here: confirmed once Ada has it
        assertEquals("", ada.library.view(space.id).single { it.id == "bobs" }.giver)

        // A sharing of its own of an item named in the folder: from the owner, or a member it granted
        // (see docs/plans/shared-access.md)
        val share = CatalogOperation(OperationId(bobReplica, 3), Stamp(currentTimeMillis() + 3, 0, bobReplica), ShareItem("bobs", "bobs", bob.device))
        val shareProof = OperationProof.signCatalog(bob.identity, space.id, bobReplica, 3, content)
        assertFalse(ada.sharing.admitsChange(space.id, share, shareProof), "not granted")
        assertFalse(ada.sharing.admitsChange(space.id, share.copy(change = UnshareItem("bobs")), shareProof), "nor its end")
        assertNotNull(ada.sharing.spaces.setGrants(space.id, listOf(Grant(Capability.SHARE_INSIDE, Role.EDITOR)), mapOf(bobReplica to 2L)))
        assertTrue(ada.sharing.admitsChange(space.id, share, shareProof), "granted to the editors")
        // Taken back after Bob's third change: that one stays allowed, the next ones not
        assertNotNull(ada.sharing.spaces.setGrants(space.id, emptyList(), mapOf(bobReplica to 3L)))
        assertTrue(ada.sharing.admitsChange(space.id, share, shareProof), "made before")
        val later = share.copy(id = OperationId(bobReplica, 4))
        assertFalse(ada.sharing.admitsChange(space.id, later, OperationProof.signCatalog(bob.identity, space.id, bobReplica, 4, content)), "made after")
        assertEquals(mapOf(bobReplica to 3L), ada.sharing.spaces.membershipOf(space.id)?.grantsOf(Capability.SHARE_INSIDE)?.single()?.upTo)

        // Bob made reader: his changes after the second one refused, the documents read only
        assertNotNull(ada.sharing.spaces.setRole(space.id, bob.device, Role.READER, lastSequence = 2))
        ada.sharing.follow(space.id)
        val second = change.copy(id = OperationId(bobReplica, 3))
        assertFalse(ada.sharing.admitsChange(space.id, second, OperationProof.signCatalog(bob.identity, space.id, bobReplica, 3, content)))
        assertTrue(ada.sharing.admitsChange(space.id, change, OperationProof.signCatalog(bob.identity, space.id, bobReplica, 1, content)))
        assertEquals(Role.READER, ada.documents.membershipOf(notes.id)?.memberOf(bob.device)?.role)

        // The sharing of a document of the space is the space's to change
        assertNull(ada.documents.admit(notes.id, carl.publicKey, "carl", "Carl", Role.EDITOR))
        assertFalse(ada.documents.adoptKey(notes.id, space.id, 2, DocumentKey.generate().encode()))
        val ownMembership = Membership(notes.id, owner = ada.device, version = 99).signedBy(ada.identity)
        assertNull(ada.documents.adopt(notes.id, space.id, ownMembership), "a membership of the document, not of its space")

        // Bob removed: a new key for the space, so for every document
        val removed = assertNotNull(ada.sharing.spaces.remove(space.id, bob.device, lastSequence = 1))
        ada.sharing.follow(space.id)
        assertEquals(2, removed.epoch)
        assertEquals(removed.key!!.forDocument(notes.id), ada.documents.stateOf(notes.id)?.key)
        assertEquals(setOf(1, 2), ada.documents.stateOf(notes.id)?.keys?.map { it.epoch }?.toSet())
        assertNull(ada.documents.membershipOf(notes.id)?.memberOf(bob.device))

        // The newer membership of the space, given on a document of it: taken
        assertNotNull(bob.documents.adopt(notes.id, space.id, removed.membership!!))

        // The notes moved out of the course: shared no more, kept
        assertTrue(ada.store.moveDocument(notes.id, null))
        ada.library.reconcile()
        ada.sharing.follow(space.id)
        assertNull(ada.documents.stateOf(notes.id))
        assertTrue(ada.store.documents.first().any { it.id == notes.id })
        assertNotNull(ada.documents.stateOf(apart.id), "the document shared on its own as it was")

        // Leaving the space: neither it nor its documents shared, the library as it is
        bob.sharing.forget(space.id)
        assertEquals(emptyList(), bob.library.spaces())
        assertTrue(bob.documents.all().values.none { it.space == space.id })
        assertEquals(setOf(bobRoot), bob.store.folders.first().map { it.id }.toSet())
        ada.library.stop()
        bob.library.stop()
    }
}
