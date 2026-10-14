package io.github.valerioisufi.drawview.canvas

import io.github.valerioisufi.drawview.canvas.controller.DrawPreferences
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.ToolManager
import io.github.valerioisufi.drawview.canvas.controller.ToolPreset
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.text.TextDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The editor tells the preferences of the user as they change and takes them back: whoever keeps
 * them writes them as text.
 */
class EditorPreferencesTest {

    @Test
    fun thePreferencesAreWrittenAsTextAndReadAgain() {
        val preferences = DrawPreferences(
            isStylusOnlyMode = true,
            lastSelectedTool = Tool.LAZO,
            textDefaults = TextDefaults(18f, 0xFF00FF00.toInt(), TextAlignment.END),
            isSnapEnabled = false,
            presets = ToolManager.defaultPresets() + (Tool.TEXT_MARKUP to listOf(ToolPreset(0f, 0xFFE53935.toInt(), StrokeTool.HIGHLIGHTER, TextMarkupKind.STRIKEOUT)))
        )
        assertEquals(preferences, DrawPreferences.decode(preferences.encode()))
        assertNull(DrawPreferences.decode("not the preferences"))
    }

    @Test
    fun theEditorTellsItsPreferencesAsTheyChangeAndTakesThemBack() = runTest(UnconfinedTestDispatcher()) {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = backgroundScope)
        controller.updateStylusOnlyMode(true)
        controller.snapManager.updateEnabled(false)
        controller.selectTool(Tool.INK_HIGHLIGHTER)
        val told = controller.preferences.value
        assertTrue(told.isStylusOnlyMode)
        assertEquals(false, told.isSnapEnabled)
        assertEquals(Tool.INK_HIGHLIGHTER, told.lastSelectedTool)

        // Another editor takes them back
        val other = DrawViewController(document = DrawDocument(), coroutineScope = backgroundScope)
        other.applyPreferences(DrawPreferences.decode(told.encode())!!)
        assertTrue(other.isStylusOnlyMode)
        assertEquals(false, other.snapManager.isEnabled)
        assertEquals(Tool.INK_HIGHLIGHTER, other.selectedTool)
        assertEquals(told, other.preferences.value)
    }
}
