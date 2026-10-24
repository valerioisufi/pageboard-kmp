package io.github.valerioisufi.drawview.ui.command

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.ui.adaptive.PlatformConventions
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.drawview.ui.platform.PencilAction
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import io.github.valerioisufi.drawview.ui.screen.EditorScreenActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

// The key events of the tests are built as the desktop builds them
@OptIn(InternalComposeUiApi::class)
class CommandTest {

    private val apple = PlatformConventions(ShortcutModifier.COMMAND)
    private val others = PlatformConventions(ShortcutModifier.CONTROL)

    private fun press(key: Key, ctrl: Boolean = false, meta: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isMetaPressed = meta, isShiftPressed = shift, isAltPressed = alt)

    @Test
    fun aShortcutUsesTheModifierOfThePlatform() {
        val undo = Shortcut(Key.Z, primary = true)
        assertTrue(undo.matches(press(Key.Z, meta = true), apple))
        assertFalse(undo.matches(press(Key.Z, ctrl = true), apple))
        assertTrue(undo.matches(press(Key.Z, ctrl = true), others))
        assertFalse(undo.matches(press(Key.Z, meta = true), others))
        // No other modifier
        assertFalse(undo.matches(press(Key.Z, meta = true, shift = true), apple))
        assertFalse(undo.matches(press(Key.Z), apple))
        assertTrue(Shortcut(Key.Z, primary = true, shift = true).matches(press(Key.Z, meta = true, shift = true), apple))
        // A single key, not with a modifier
        assertTrue(Shortcut(Key.P).matches(press(Key.P), apple))
        assertFalse(Shortcut(Key.P).matches(press(Key.P, meta = true), apple))
    }

    @Test
    fun aShortcutIsWrittenAsThePlatformWritesIt() {
        val redo = Shortcut(Key.Z, primary = true, shift = true)
        assertEquals("⇧⌘Z", redo.label(apple))
        assertEquals("Ctrl+Shift+Z", redo.label(others))
        assertEquals("⌥⌘I", Shortcut(Key.I, primary = true, alt = true).label(apple))
        assertEquals("⌫", Shortcut(Key.Backspace).label(apple))
        assertEquals("Backspace", Shortcut(Key.Backspace).label(others))
        assertEquals("⌘↑", Shortcut(Key.DirectionUp, primary = true).label(apple))
        assertEquals("P", Shortcut(Key.P).label(others))
    }

    @Test
    fun theHostRunsTheEnabledCommandOfTheScreenInFront() {
        val host = CommandHost()
        val runs = mutableListOf<String>()
        var libraryEnabled = true
        val unregisterLibrary = host.register(
            listOf(Command("library-delete", { "" }, CommandGroup.EDIT, shortcuts = listOf(Shortcut(Key.Backspace)), isEnabled = { libraryEnabled }) { runs += "library" })
        )
        val unregisterEditor = host.register(
            listOf(Command("editor-delete", { "" }, CommandGroup.EDIT, shortcuts = listOf(Shortcut(Key.Backspace)), isEnabled = { false }) { runs += "editor" })
        )

        // The command in front cannot run: the next one does
        assertTrue(host.dispatch(press(Key.Backspace), apple))
        assertEquals(listOf("library"), runs)

        // No enabled command: the key is left to the others
        libraryEnabled = false
        assertFalse(host.dispatch(press(Key.Backspace), apple))
        // A key released, or without a command
        assertFalse(host.dispatch(KeyEvent(Key.Backspace, KeyEventType.KeyUp), apple))
        assertFalse(host.dispatch(press(Key.Q), apple))

        unregisterEditor()
        unregisterLibrary()
        assertTrue(host.commands.isEmpty())
    }

    @Test
    fun theEditorCommandsRunThroughTheirExperts() {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val commands = editorCommands(controller)
        val host = CommandHost().apply { register(commands.all) }

        // Nothing to undo, nothing selected
        assertFalse(commands.undo.isEnabled())
        assertFalse(commands.copy.isEnabled())
        assertFalse(host.dispatch(press(Key.Z, meta = true), apple))

        // A tool by its key, in drawing mode
        assertTrue(host.dispatch(press(Key.E), apple))
        assertEquals(Tool.ERASER, controller.selectedTool)
        assertTrue(commands.eraser.isChecked!!.invoke())
        assertFalse(commands.pen.isChecked!!.invoke())

        // The page grid shown and hidden
        assertTrue(host.dispatch(press(Key.G, meta = true), apple))
        assertTrue(controller.isPageGridVisible)
        commands.pageGrid.perform()
        assertFalse(controller.isPageGridVisible)
    }

    @Test
    fun theFormatCommandsWorkOnTheTextWrittenAndOnTheTextsSelected() {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val commands = editorCommands(controller)
        val host = CommandHost().apply { register(commands.all) }
        val editor = controller.textEditorManager
        assertFalse(commands.bold.isEnabled(), "no text")

        // The text being written: its characters, its style
        editor.startNewText(0, 20f, 20f)
        editor.updateValue(TextFieldValue("Hello world", TextRange(6, 11)))
        assertTrue(host.dispatch(press(Key.B, meta = true), apple))
        assertEquals("Hello **world**", editor.value.text)
        assertTrue(host.dispatch(press(Key.C, meta = true, shift = true), apple))
        assertEquals(TextAlignment.CENTER, editor.alignment)
        assertTrue(commands.alignCenter.isChecked!!.invoke())
        assertTrue(host.dispatch(press(Key.Period, meta = true, shift = true), apple))
        assertEquals(18f, editor.fontSize, "the next size")
        editor.finishEditing()

        // The texts selected: their style only
        val text = controller.document.pages[0].texts.single()
        controller.selectionManager.selectText(0, text)
        assertFalse(commands.bold.isEnabled())
        assertTrue(commands.alignEnd.isEnabled())
        commands.alignEnd.perform()
        assertEquals(TextAlignment.END, text.alignment)
        commands.smallerText.perform()
        assertEquals(16f, text.fontSize)
    }

    @Test
    fun copiedTextsReachTheOtherApplicationsAndTheirTextComesBack() {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val clipboard = FakeClipboard()
        val commands = editorCommands(controller, clipboard)
        val page = controller.document.pages[0]
        val editor = controller.textEditorManager
        editor.startNewText(0, 20f, 20f)
        editor.updateValue(TextFieldValue("Copied **here**", TextRange(15)))
        editor.finishEditing()

        // Copied: its text in the clipboard of the system; pasted: the text copied here, again
        controller.selectionManager.selectText(0, page.texts.single())
        commands.copy.perform()
        assertEquals("Copied **here**", clipboard.text)
        commands.paste.perform()
        assertEquals(2, page.texts.size)

        // A text copied in another application: a new text
        clipboard.text = "From another app"
        commands.paste.perform()
        assertEquals(listOf("Copied **here**", "Copied **here**", "From another app"), page.texts.map { it.text })
    }

    @Test
    fun theGesturesOfThePencilSwitchTheTools() {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val commands = editorCommands(controller)
        assertEquals(Tool.INK_PEN, controller.selectedTool)

        // The eraser, and back to the pen
        commands.perform(PencilAction.SWITCH_ERASER)
        assertEquals(Tool.ERASER, controller.selectedTool)
        commands.perform(PencilAction.SWITCH_ERASER)
        assertEquals(Tool.INK_PEN, controller.selectedTool)

        // The tool before, back and forth
        controller.selectTool(Tool.INK_HIGHLIGHTER)
        commands.perform(PencilAction.SWITCH_PREVIOUS)
        assertEquals(Tool.INK_PEN, controller.selectedTool)
        commands.perform(PencilAction.SWITCH_PREVIOUS)
        assertEquals(Tool.INK_HIGHLIGHTER, controller.selectedTool)

        // The palette is asked to the bar of the tools
        commands.perform(PencilAction.SHOW_PALETTE)
        assertEquals(1, commands.paletteRequests)
        commands.perform(PencilAction.NONE)
        assertEquals(Tool.INK_HIGHLIGHTER, controller.selectedTool)
    }

    @Test
    fun theHostKeepsTheLabelsWrittenForTheMenusOfTheSystem() {
        val host = CommandHost()
        val undo = Command("undo", { "" }, CommandGroup.EDIT) {}
        val unregister = host.register(listOf(undo), listOf("Annulla"))
        assertEquals(listOf(undo to "Annulla"), host.labeledCommands)
        unregister()
        assertTrue(host.labeledCommands.isEmpty())
    }

    @Test
    fun everyKeyOfTheCommandsHasAName() {
        val commands = editorCommands(DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined)))
        for (command in commands.all) {
            for (shortcut in command.shortcuts) {
                for (conventions in listOf(apple, others)) {
                    assertNotEquals(shortcut.key.toString(), keyName(shortcut.key, conventions == apple), "${command.id}: ${shortcut.key}")
                }
            }
        }
        // Each shortcut runs one command only
        val shortcuts = commands.all.flatMap { command -> command.shortcuts.map { it to command.id } }
        assertEquals(shortcuts.size, shortcuts.map { it.first }.toSet().size, shortcuts.groupBy { it.first }.filter { it.value.size > 1 }.toString())
    }

    @Test
    fun listCommandsToggleListsOnTheEditedText() {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val commands = editorCommands(controller)
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 10f)
        editor.updateValue(TextFieldValue("My item", TextRange(0)))
        assertTrue(commands.bulletList.isEnabled())
        assertTrue(commands.numberedList.isEnabled())

        commands.bulletList.perform()
        assertEquals("- My item", editor.value.text)

        commands.numberedList.perform()
        assertEquals("1. My item", editor.value.text)

        commands.numberedList.perform()
        assertEquals("My item", editor.value.text)
    }

    @Test
    fun theBlockOfCodeIsToggledByItsShortcutAndStopsTheFormats() {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val commands = editorCommands(controller)
        val host = CommandHost().apply { register(commands.all) }
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 10f)
        editor.updateValue(TextFieldValue("x = 1", TextRange(0)))
        assertTrue(commands.bold.isEnabled())

        assertTrue(host.dispatch(press(Key.C, meta = true, alt = true), apple))
        assertEquals("```\nx = 1\n```", editor.value.text)
        assertFalse(commands.bold.isEnabled(), "no format in a block of code")
        assertFalse(commands.heading.isEnabled())
        assertTrue(commands.codeBlock.isEnabled(), "the block can be taken out")

        assertTrue(host.dispatch(press(Key.C, meta = true, alt = true), apple))
        assertEquals("x = 1", editor.value.text)
        assertTrue(commands.bold.isEnabled())
    }

    /** The clipboard of the system in a test: the text set last. */
    private class FakeClipboard : ClipboardManager {
        var text: String? = null
        override fun setText(annotatedString: AnnotatedString) {
            text = annotatedString.text
        }
        override fun getText(): AnnotatedString? = text?.let(::AnnotatedString)
    }

    private fun editorCommands(controller: DrawViewController, clipboard: ClipboardManager = FakeClipboard()) = EditorCommands(
        controller = controller,
        clipboard = clipboard,
        actions = EditorScreenActions(close = {}, importPdf = {}, importImage = {}, editDocumentBackground = {}, showAbout = {})
    )

    @Test
    fun theLineNumbersOfTheBlockAreToggledByTheirShortcut() {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val commands = editorCommands(controller)
        val host = CommandHost().apply { register(commands.all) }
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 10f)
        editor.updateValue(TextFieldValue("x = 1", TextRange(0)))
        assertFalse(commands.lineNumbers.isEnabled(), "not out of a block")

        editor.updateValue(TextFieldValue("```py\nx = 1\n```", TextRange(7)))
        assertTrue(commands.lineNumbers.isEnabled())
        assertFalse(commands.lineNumbers.isChecked!!.invoke())
        assertTrue(host.dispatch(press(Key.L, meta = true, alt = true), apple))
        assertEquals("```py showLineNumbers\nx = 1\n```", editor.value.text)
        assertTrue(commands.lineNumbers.isChecked!!.invoke())
    }
}
