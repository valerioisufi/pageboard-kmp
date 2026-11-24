package io.github.valerioisufi.pageboard.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The sharing states of a copy of a database read as accesses (see `docs/plans/shared-access.md`,
 * phase 1), never run by jvmTest. The states are given as a file of lines `<document>\t<secret>`,
 * taken from the copy (`sqlite3 -separator $'\t' <copy> "SELECT documentId, secret FROM
 * shared_documents"`): every one must be read, and what it is is printed (never its keys).
 *
 *     ./gradlew :pageboard-sync:jvmTest --tests '*SharingOfACopyCheck*' -Ppageboard.sharingCopy=<file>
 */
class SharingOfACopyCheck {
    @Test
    fun check() {
        val file = System.getProperty("pageboard.sharingCopy")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        var own = 0
        var ofSpaces = 0
        var several = 0
        for (line in file.readLines().filter { it.isNotBlank() }) {
            val (documentId, secret) = line.split('\t', limit = 2)
            val state = assertNotNull(SharingState.decode(secret), "the sharing of $documentId read")
            val access = DocumentAccess.decode(state)
            check(access.encode()?.encode() == state.encode()) { "the sharing of $documentId written again as it was" }
            if (access.own != null) own++
            if (access.spaces.isNotEmpty()) ofSpaces++
            if (access.all.size > 1) several++
            val kinds = access.all.joinToString { if (it.space == null) "own" + (if (it.closed) " (closed)" else "") else "space ${it.space}" + (if (it.closed) " (closed)" else "") }
            println("$documentId: $kinds, ${access.all.sumOf { it.members?.members?.size ?: 0 }} members")
        }
        println("Read: $own shared on their own, $ofSpaces through a shared folder, $several with several accesses")
    }
}
