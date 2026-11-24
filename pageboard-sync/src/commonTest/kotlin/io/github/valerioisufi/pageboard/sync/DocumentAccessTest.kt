package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.sync.protocol.DeviceIdentity
import io.github.valerioisufi.pageboard.sync.protocol.DocumentKey
import io.github.valerioisufi.pageboard.sync.protocol.Member
import io.github.valerioisufi.pageboard.sync.protocol.Membership
import io.github.valerioisufi.pageboard.sync.protocol.MembershipKind
import io.github.valerioisufi.pageboard.sync.protocol.Role
import io.github.valerioisufi.pageboard.sync.protocol.SignedMembership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The accesses of a document as they are kept (see `docs/plans/shared-access.md`, phase 1). */
class DocumentAccessTest {

    @Test
    fun aStateOfBeforeIsOneAccessAndStaysAsItWas() {
        val own = DocumentAccess.decode(assertNotNull(SharingState.decode(SharingStateFixtures.OWN)))
        assertNotNull(own.own)
        assertEquals(emptyList(), own.spaces)
        assertEquals(Role.READER, own.roleOf("bob"))
        assertEquals(mapOf("bob" to mapOf("rb" to 3L)), own.own?.versions)

        val ofSpace = DocumentAccess.decode(assertNotNull(SharingState.decode(SharingStateFixtures.OF_SPACE)))
        assertNull(ofSpace.own)
        assertEquals(listOf("s"), ofSpace.spaces.map { it.space })
        assertEquals(MembershipKind.SPACE, ofSpace.spaces.single().members?.kind)

        val closed = DocumentAccess.decode(assertNotNull(SharingState.decode(SharingStateFixtures.CLOSED)))
        assertEquals(true, closed.own?.closed)

        // Written again as one access: byte for byte as before
        for (text in listOf(SharingStateFixtures.OWN, SharingStateFixtures.OF_SPACE, SharingStateFixtures.CLOSED)) {
            assertEquals(text, DocumentAccess.decode(SharingState.decode(text)!!).encode()?.encode())
        }
    }

    @Test
    fun severalAccessesAreKeptBothWays() {
        val own = SharingState.decode(SharingStateFixtures.OWN)!!
        val ofSpace = SharingState.decode(SharingStateFixtures.OF_SPACE)!!
        val other = ofSpace.copy(space = "t")
        val access = DocumentAccess(own = own).with(ofSpace).with(other)
        val text = assertNotNull(access.encode()).encode()
        val read = DocumentAccess.decode(SharingState.decode(text)!!)
        // Compared as they are written (a signed membership is compared by its bytes only there)
        assertEquals(text, read.encode()?.encode())
        assertEquals(own.encode(), read.of(null)?.encode())
        assertEquals(listOf("s", "t"), read.spaces.map { it.space })

        // Without its own sharing: the innermost folder first
        val spacesOnly = read.without(null)
        assertEquals("s", spacesOnly.encode()?.space)
        assertEquals(spacesOnly.encode()?.encode(), DocumentAccess.decode(spacesOnly.encode()!!).encode()?.encode())
        assertEquals(listOf("t"), spacesOnly.without("s").spaces.map { it.space })
        assertTrue(spacesOnly.without("s").without("t").isEmpty)
        assertNull(DocumentAccess().encode())

        // An access given again replaces the one of its sharing
        val updated = read.with(ofSpace.copy(versions = mapOf("x" to mapOf("r" to 1L))))
        assertEquals(2, updated.spaces.size)
        assertEquals(mapOf("x" to mapOf("r" to 1L)), updated.of("s")?.versions)
    }

    @Test
    fun aDecoderOfBeforeReadsTheFirstAccess() {
        val own = SharingState.decode(SharingStateFixtures.OWN)!!
        val text = DocumentAccess(own = own).with(SharingState.decode(SharingStateFixtures.OF_SPACE)!!).encode()!!.encode()
        val before = Json { ignoreUnknownKeys = true }.decodeFromString(SharingStateOfBefore.serializer(), text)
        assertEquals(own.keys, before.keys)
        assertEquals(own.owner, before.owner)
        assertEquals(own.invitations, before.invitations)
        assertEquals(own.versions, before.versions)
        assertNull(before.space)
    }

    @Test
    fun aDeviceMayWriteWhenAnyAccessLetsIt() {
        val ada = DeviceIdentity.generate()
        fun state(role: Role, space: String?) = SharingState(
            keys = listOf(EpochKey(1, DocumentKey.generate().encode())),
            owner = ada.publicKey,
            membership = Membership(space ?: "d", ada.publicKey, members = listOf(Member(ada.publicKey, "ra", "Ada", Role.OWNER), Member("bob", "rb", "Bob", role)), kind = if (space == null) MembershipKind.DOCUMENT else MembershipKind.SPACE).signedBy(ada),
            space = space
        )
        val readerHere = DocumentAccess(own = state(Role.READER, null))
        assertTrue(readerHere.isReadOnly("bob"))
        val editorThere = readerHere.with(state(Role.EDITOR, "s"))
        assertFalse(editorThere.isReadOnly("bob"), "an editor through the folder")
        assertEquals(Role.EDITOR, editorThere.roleOf("bob"))
        assertEquals(Role.OWNER, editorThere.roleOf(ada.publicKey))
        assertNull(editorThere.roleOf("carl"))
        assertFalse(editorThere.isReadOnly("carl"), "not a member: not read only")
    }

    @Test
    fun severalAccessesAreStoredWithTheirReadOnly() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val notes = store.createDocument("Notes")
        val shared = StoreSharedDocuments(store)
        val state = DocumentAccess(own = SharingState.decode(SharingStateFixtures.OWN)!!).with(SharingState.decode(SharingStateFixtures.OF_SPACE)!!).encode()!!
        shared.save(notes.id, state, readOnly = false)
        val read = assertNotNull(StoreSharedDocuments(store).stateOf(notes.id))
        assertEquals(state.encode(), read.encode())
        assertEquals(listOf("s"), DocumentAccess.decode(read).spaces.map { it.space })
        assertEquals(false, store.isReadOnly(notes.id))
    }

    /** [SharingState] as a build of before decodes it. */
    @Serializable
    private data class SharingStateOfBefore(
        val keys: List<EpochKey> = emptyList(),
        val owner: String = "",
        val membership: SignedMembership? = null,
        val invitations: List<InvitationGrant> = emptyList(),
        val joining: String? = null,
        val versions: Map<String, Map<String, Long>> = emptyMap(),
        val closed: Boolean = false,
        val space: String? = null
    )
}
