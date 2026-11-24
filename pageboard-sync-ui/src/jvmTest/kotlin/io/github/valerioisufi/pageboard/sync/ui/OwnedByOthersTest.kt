package io.github.valerioisufi.pageboard.sync.ui

import io.github.valerioisufi.pageboard.sync.SharingState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PencilNotes's rule: the documents owned by others stay out of its shared folders (see `docs/plans/shared-access.md`). */
class OwnedByOthersTest {
    @Test
    fun aDocumentSharedByAnotherDeviceIsOwnedByOthers() {
        assertTrue(isOwnedByOthers(SharingState(owner = "carl").encode(), "ada"))
        assertFalse(isOwnedByOthers(SharingState(owner = "ada").encode(), "ada"), "shared by this device")
        assertFalse(isOwnedByOthers(SharingState(owner = "ada", space = "s").encode(), "ada"), "through a folder only")
        assertFalse(isOwnedByOthers(SharingState(owner = "carl", space = "s").encode(), "ada"), "a folder of another owner, not the document's own sharing")
        // Its own sharing among others: the first one
        val both = SharingState(owner = "ada", space = "s", through = listOf(SharingState(owner = "carl")))
        assertTrue(isOwnedByOthers(both.encode(), "ada"))
        // What was learned of an access this device is not in does not count
        val learned = SharingState(owner = "ada", space = "s", through = listOf(SharingState(owner = "carl", learned = true)))
        assertFalse(isOwnedByOthers(learned.encode(), "ada"))
        assertFalse(isOwnedByOthers("not a sharing", "ada"))
    }
}
