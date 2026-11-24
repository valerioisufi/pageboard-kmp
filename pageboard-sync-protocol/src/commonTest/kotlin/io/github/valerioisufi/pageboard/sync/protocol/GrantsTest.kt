package io.github.valerioisufi.pageboard.sync.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the owner of a sharing allows beyond the roles (see `docs/plans/shared-access.md`, phase 3). */
class GrantsTest {
    private val ada = DeviceIdentity.generate()
    private val members = listOf(
        Member(ada.publicKey, "ra", "Ada", Role.OWNER),
        Member("bob", "rb", "Bob", Role.EDITOR),
        Member("carl", "rc", "Carl", Role.READER)
    )

    @Test
    fun theOwnerHoldsEveryCapabilityAndTheOthersWhatIsGranted() {
        val none = Membership("s", ada.publicKey, members = members, kind = MembershipKind.SPACE)
        assertTrue(none.holds(Capability.SHARE_INSIDE, "ra", 1), "the owner")
        assertFalse(none.holds(Capability.SHARE_INSIDE, "rb", 1), "no grant: the owner's alone")

        val toEditors = none.copy(grants = listOf(Grant(Capability.SHARE_INSIDE, Role.EDITOR)))
        assertTrue(toEditors.holds(Capability.SHARE_INSIDE, "rb", 1))
        assertFalse(toEditors.holds(Capability.SHARE_INSIDE, "rc", 1), "a reader is not an editor")
        assertFalse(toEditors.holds(Capability.BRING_SHARED, "rb", 1), "another capability")

        val toCarl = none.copy(grants = listOf(Grant(Capability.BRING_SHARED, devices = listOf("carl"))))
        assertTrue(toCarl.holds(Capability.BRING_SHARED, "rc", 1), "named")
        assertFalse(toCarl.holds(Capability.BRING_SHARED, "rb", 1))
        assertFalse(toCarl.holds(Capability.BRING_SHARED, "unknown", 1), "not a member")
    }

    @Test
    fun aGrantTakenBackKeepsWhatItAllowedBefore() {
        val taken = Membership("s", ada.publicKey, members = members, grants = listOf(Grant(Capability.SHARE_INSIDE, upTo = mapOf("rb" to 4L))))
        assertTrue(taken.holds(Capability.SHARE_INSIDE, "rb", 4), "up to its last operation")
        assertFalse(taken.holds(Capability.SHARE_INSIDE, "rb", 5), "nothing after")
    }

    @Test
    fun aCapabilityNotKnownAllowsNothingAndIsCarried() {
        val ofAnApplication = Grant("com.example.comments", Role.READER)
        val membership = Membership("d", ada.publicKey, members = members, grants = listOf(ofAnApplication, Grant("pageboard.later", Role.READER)))
        assertTrue(membership.holds("com.example.comments", "rc", 1), "read by the application that gave it")
        assertFalse(membership.holds(Capability.SHARE_INSIDE, "rc", 1), "no capability of the library given by it")
        assertFalse(membership.holds(Capability.PLACE_IN_SPACES, "rc", 1))
        // Signed and carried as it is
        val signed = membership.signedBy(ada)
        assertEquals(membership, signed.open(ada.publicKey))
    }

    @Test
    fun aMembershipOfBeforeKeepsItsBytes() {
        val text = Membership("d", ada.publicKey, members = members).encode().decodeToString()
        assertFalse("grants" in text, text)
        assertFalse("spaces" in text, text)
        val named = Membership("d", ada.publicKey, members = members, spaces = listOf(SpaceRef("s", "owner")))
        assertEquals(named, Membership.json.decodeFromString(Membership.serializer(), named.encode().decodeToString()))
    }

    @Test
    fun aPlacementIsTakenFromAMemberGrantedOnly() {
        val bob = DeviceIdentity.generate()
        val withBob = members.map { if (it.device == "bob") it.copy(device = bob.publicKey) else it }
        val ref = SpaceRef("s", "someone")
        val placement = Placement.sign(bob, "d", ref)
        val granted = Membership("d", ada.publicKey, members = withBob, grants = listOf(Grant(Capability.PLACE_IN_SPACES, devices = listOf(bob.publicKey))))
        assertTrue(placement.isAllowedBy(granted))
        assertFalse(placement.isAllowedBy(granted.copy(grants = emptyList())), "not granted")
        assertFalse(placement.isAllowedBy(granted.copy(documentId = "other")), "of another item")
        val forged = Placement(placement.itemId, SpaceRef("t", "someone"), placement.device, placement.signature)
        assertFalse(forged.isAllowedBy(granted), "the folder changed after it was signed")
        val ofCarl = Placement.sign(DeviceIdentity.generate(), "d", ref)
        assertFalse(ofCarl.isAllowedBy(granted), "of a device not a member")
    }
}
