package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.agent.LibraryItem
import io.github.valerioisufi.drawview.agent.LibraryUndo
import io.github.valerioisufi.drawview.agent.ProposalItem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The serial names of the sealed classes of this module, read and written by the models, and kept in the journal of the agents. A subclass without
 * `@SerialName` would be named after its package: these names are recorded so that moving or
 * renaming a class never changes what is written.
 */
class SerialNamesTest {
    @Test
    fun libraryItem() {
        assertEquals(
            setOf(
                "create_document",
                "create_folder",
                "duplicate_document",
                "import_archive",
                "move_document",
                "move_folder",
                "rename_folder",
                "restore",
                "trash_document",
                "trash_folder"
            ),
            subclassNames(serializer<LibraryItem>()),
        )
    }

    @Test
    fun libraryUndo() {
        assertEquals(
            setOf(
                "created_document",
                "created_folder",
                "imported_archive",
                "moved_document",
                "moved_folder",
                "renamed_folder",
                "restored",
                "trashed"
            ),
            subclassNames(serializer<LibraryUndo>()),
        )
    }

    @Test
    fun proposalItem() {
        assertEquals(
            setOf(
                "add_image",
                "add_markup",
                "add_page",
                "add_shape",
                "add_stroke",
                "copy_elements",
                "copy_pages",
                "duplicate_page",
                "import_pdf",
                "insert_text",
                "move_page",
                "place_elements",
                "remove_elements",
                "remove_page",
                "remove_text",
                "rename_document",
                "reorder_elements",
                "replace_text",
                "restore_pages",
                "restyle_markups",
                "restyle_stroke",
                "restyle_text",
                "set_document_defaults",
                "set_page_format",
                "update_shape",
                "update_text"
            ),
            subclassNames(serializer<ProposalItem>()),
        )
    }

    /** The serial names of the subclasses of the sealed class of [serializer]. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun subclassNames(serializer: KSerializer<*>): Set<String> =
        serializer.descriptor.getElementDescriptor(1).elementNames.toSet()
}
