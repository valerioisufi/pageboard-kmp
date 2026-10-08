package io.github.valerioisufi.drawview.ui.command

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.ui.adaptive.PlatformConventions
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import io.github.valerioisufi.drawview.ui.screen.EditorScreenActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    private fun editorCommands(controller: DrawViewController) = EditorCommands(
        controller = controller,
        clipboard = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) = Unit
            override fun getText(): AnnotatedString? = null
        },
        actions = EditorScreenActions(close = {}, importPdf = {}, importImage = {}, editDocumentBackground = {}, showAbout = {})
    )
}
