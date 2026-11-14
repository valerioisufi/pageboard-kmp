package io.github.valerioisufi.drawview.ui.extension

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The extensions made by the builder, by a subclass, and combined. */
class EditorExtensionTest {

    private fun command(id: String) = Command(id, { id }, CommandGroup.entries.first()) {}

    @Test
    fun theBuilderSetsWhatItIsGivenAndLeavesTheRestEmpty() = runTest {
        val context = EditorContext(DrawViewController(DrawDocument(), backgroundScope))
        val extension = EditorExtension {
            commands { listOf(command("a")) }
        }
        assertEquals(listOf("a"), extension.commands(context).map { it.id })
        assertTrue(extension.panels(context).isEmpty())
        assertTrue(extension.pageGridActions(context).isEmpty())
    }

    @Test
    fun aSubclassAndABuiltExtensionCombineInOrder() = runTest {
        val context = EditorContext(DrawViewController(DrawDocument(), backgroundScope))
        val first = object : EditorExtension() {
            override val commands: (EditorContext) -> List<Command> = { listOf(command("first")) }
            override val pageGridActions: (EditorContext) -> List<Command> = { listOf(command("grid")) }
        }
        val second = EditorExtension {
            commands { listOf(command("second")) }
        }
        val both = first + second
        assertEquals(listOf("first", "second"), both.commands(context).map { it.id })
        assertEquals(listOf("grid"), both.pageGridActions(context).map { it.id })
    }
}
