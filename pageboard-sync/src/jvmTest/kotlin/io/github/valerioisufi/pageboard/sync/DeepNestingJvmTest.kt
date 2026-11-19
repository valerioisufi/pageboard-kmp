package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.sync.protocol.SyncException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A change nested very deep (composite changes in composite changes), as a member with an
 * application of its own could send, is refused before it is decoded: decoding it would run out of
 * stack, which cannot be caught on every platform. The ones of the applications are decoded.
 */
class DeepNestingJvmTest {

    /** The operation of [depth] composite changes around a rename. */
    private fun nested(depth: Int): Operation {
        var change: DocumentChange = RenameDocument("deep")
        repeat(depth) { change = CompositeChange(listOf(change)) }
        return Operation(OperationId("bob", 1), Stamp(1, 0, "bob"), change)
    }

    /** [block] on a thread with a stack large enough for it (the one of the device sending). */
    private fun <T> onLargeStack(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread(null, { result = runCatching(block) }, "large stack", 1L shl 31)
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }

    @Test
    fun aChangeNestedTooDeepIsRefusedBeforeItIsDecoded() {
        val operation = onLargeStack { SyncCodec.encodeOperation(nested(100_000)) }
        assertFailsWith<SyncException> { SyncCodec.decodeOperation(operation) }
        val frame = onLargeStack { SyncCodec.encode(Ping) } + operation
        assertNull(SyncCodec.decode(frame))
    }

    @Test
    fun theNestingOfTheApplicationsIsDecoded() {
        val operation = nested(30)
        assertEquals(operation, SyncCodec.decodeOperation(SyncCodec.encodeOperation(operation)))
    }
}
