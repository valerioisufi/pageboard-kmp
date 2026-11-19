package io.github.valerioisufi.pageboard.store

import io.github.valerioisufi.pageboard.store.space.CatalogChange
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The serial names of the sealed classes of this module, stored in the catalog and sent between devices. A subclass without
 * `@SerialName` would be named after its package: these names are recorded so that moving or
 * renaming a class never changes what is written.
 */
class SerialNamesTest {
    @Test
    fun catalogChange() {
        assertEquals(
            setOf(
                "addDocument",
                "addFolder",
                "catalogComposite",
                "moveItem",
                "removeFromSpace",
                "renameFolder",
                "restoreItem",
                "trashItem"
            ),
            subclassNames(serializer<CatalogChange>()),
        )
    }

    /** The serial names of the subclasses of the sealed class of [serializer]. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun subclassNames(serializer: KSerializer<*>): Set<String> =
        serializer.descriptor.getElementDescriptor(1).elementNames.toSet()
}
