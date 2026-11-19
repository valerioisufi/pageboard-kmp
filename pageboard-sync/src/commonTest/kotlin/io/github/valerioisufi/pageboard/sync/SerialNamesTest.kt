package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.sync.SyncMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The serial names of the sealed classes of this module, sent between devices. A subclass without
 * `@SerialName` would be named after its package: these names are recorded so that moving or
 * renaming a class never changes what is written.
 */
class SerialNamesTest {
    @Test
    fun syncMessage() {
        assertEquals(
            setOf(
                "addresses",
                "admission",
                "attachmentPart",
                "attachmentRequest",
                "attachments",
                "catalogOperations",
                "challenge",
                "documentHeads",
                "hello",
                "joinRefused",
                "key",
                "leave",
                "membership",
                "membershipQuery",
                "operations",
                "ping",
                "presence",
                "resourceChunk",
                "resourceMissing",
                "resourceRequest",
                "snapshot",
                "versions"
            ),
            subclassNames(serializer<SyncMessage>()),
        )
    }

    /** The serial names of the subclasses of the sealed class of [serializer]. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun subclassNames(serializer: KSerializer<*>): Set<String> =
        serializer.descriptor.getElementDescriptor(1).elementNames.toSet()
}
