package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.ElementData
import io.github.valerioisufi.pageboard.model.change.Placement
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The serial names of the sealed classes of this module, stored in the documents and sent between devices. A subclass without
 * `@SerialName` would be named after its package: these names are recorded so that moving or
 * renaming a class never changes what is written.
 */
class SerialNamesTest {
    @Test
    fun pageBackground() {
        assertEquals(
            setOf(
                "Dotted",
                "Grid",
                "Ruled",
                "Solid"
            ),
            subclassNames(serializer<PageBackground>()),
        )
    }

    @Test
    fun documentChange() {
        assertEquals(
            setOf(
                "addElements",
                "addPage",
                "addResource",
                "composite",
                "movePages",
                "none",
                "placeElements",
                "removeElements",
                "removePage",
                "renameDocument",
                "reorderElements",
                "restyleMarkups",
                "setDocumentDefaults",
                "setPageFormat",
                "updateContent",
                "updateTexts"
            ),
            subclassNames(serializer<DocumentChange>()),
        )
    }

    @Test
    fun placement() {
        assertEquals(
            setOf(
                "box",
                "stroke",
                "text"
            ),
            subclassNames(serializer<Placement>()),
        )
    }

    @Test
    fun elementData() {
        assertEquals(
            setOf(
                "custom",
                "image",
                "pdf",
                "stroke",
                "text",
                "textMarkup"
            ),
            subclassNames(serializer<ElementData>()),
        )
    }

    /** The serial names of the subclasses of the sealed class of [serializer]. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun subclassNames(serializer: KSerializer<*>): Set<String> =
        serializer.descriptor.getElementDescriptor(1).elementNames.toSet()
}
