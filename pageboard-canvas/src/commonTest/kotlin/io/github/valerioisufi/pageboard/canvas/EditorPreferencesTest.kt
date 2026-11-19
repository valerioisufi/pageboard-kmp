package io.github.valerioisufi.pageboard.canvas

import io.github.valerioisufi.pageboard.canvas.controller.EditorPreferences
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.controller.ShapeToolStyle
import io.github.valerioisufi.pageboard.canvas.controller.ToolManager
import io.github.valerioisufi.pageboard.canvas.controller.ToolPreset
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.model.shape.ShapeDash
import io.github.valerioisufi.pageboard.model.shape.ShapeHead
import io.github.valerioisufi.pageboard.model.shape.ShapeKind
import io.github.valerioisufi.pageboard.model.text.TextDefaults
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
        val preferences = EditorPreferences(
            isStylusOnlyMode = true,
            lastSelectedTool = Tool.LAZO,
            textDefaults = TextDefaults(18f, 0xFF00FF00.toInt(), TextAlignment.END),
            isSnapEnabled = false,
            presets = ToolManager.defaultPresets() + (Tool.TEXT_MARKUP to listOf(ToolPreset(0f, 0xFFE53935.toInt(), StrokeTool.HIGHLIGHTER, TextMarkupKind.STRIKEOUT)))
        )
        assertEquals(preferences, EditorPreferences.decode(preferences.encode()))
        assertNull(EditorPreferences.decode("not the preferences"))
    }

    @Test
    fun thePreferencesMovedByTheMigrationOfTheDatabaseAreRead() {
        // As the migration 17 -> 18 of pageboard-store-room writes them: the tools without presets left out
        val migrated = """
            {"isStylusOnlyMode":false,"lastSelectedTool":"TEXT_MARKUP",
             "textDefaults":{"fontSize":11.0,"color":-14722394,"alignment":"START"},"isSnapEnabled":true,
             "presets":{"INK_PEN":[{"sizeMm":0.5,"color":-15066088,"family":"PRESSURE_PEN"}],
                        "TEXT_MARKUP":[{"sizeMm":0.0,"color":-2999493,"family":"HIGHLIGHTER","markup":"UNDERLINE"}]}}
        """
        val preferences = EditorPreferences.decode(migrated)
        assertEquals(
            EditorPreferences(
                lastSelectedTool = Tool.TEXT_MARKUP,
                textDefaults = TextDefaults(11f, -14722394, TextAlignment.START),
                presets = mapOf(
                    Tool.INK_PEN to listOf(ToolPreset(0.5f, -15066088, StrokeTool.PRESSURE_PEN)),
                    Tool.TEXT_MARKUP to listOf(ToolPreset(0f, -2999493, StrokeTool.HIGHLIGHTER, TextMarkupKind.UNDERLINE))
                )
            ),
            preferences
        )
        // Written before the shapes: they are on, the shapes tool draws its default
        assertTrue(preferences!!.isShapeRecognitionEnabled)
        assertEquals(ShapeToolStyle(), preferences.shapeTool.toStyle())
        val tools = ToolManager().apply { applyPreferences(preferences) }
        // The presets are a snapshot list, equal to a plain list on the JVM only: their content is compared
        assertEquals(ToolManager.defaultPresets().getValue(Tool.ERASER), tools.presetsOf(Tool.ERASER).toList())
    }

    @Test
    fun theStyleOfTheShapesToolIsKeptAndWhatIsNotUnderstoodIsItsDefault() = runTest(UnconfinedTestDispatcher()) {
        val controller = PageboardController(document = Document(), coroutineScope = backgroundScope)
        val style = ShapeToolStyle(ShapeKind.LINE, 0xFFE53935.toInt(), 1f, ShapeDash.DASHED, fill = 0x33E53935, heads = 2, head = ShapeHead.FILLED)
        controller.shapeInserter.updateStyle(style)
        val text = controller.preferences.value.encode()
        val other = PageboardController(document = Document(), coroutineScope = backgroundScope)
        other.applyPreferences(EditorPreferences.decode(text)!!)
        assertEquals(style, other.shapeInserter.style)
        assertEquals(ShapeHead.FILLED, style.element().startHead)
        assertNull(style.element().fill, "a line has no fill")

        // A kind of a later version, a width out of bounds, heads not understood: the defaults
        val later = """{"shapeTool":{"kind":"STAR","widthMm":99.0,"dash":"WAVY","heads":7,"head":"NONE","stroke":-1}}"""
        val read = EditorPreferences.decode(later)!!.shapeTool.toStyle()
        assertEquals(ShapeToolStyle(stroke = -1), read)
    }

    @Test
    fun theEditorTellsItsPreferencesAsTheyChangeAndTakesThemBack() = runTest(UnconfinedTestDispatcher()) {
        val controller = PageboardController(document = Document(), coroutineScope = backgroundScope)
        controller.updateStylusOnlyMode(true)
        controller.snapManager.updateEnabled(false)
        controller.shapeSnapper.updateEnabled(false)
        controller.selectTool(Tool.INK_HIGHLIGHTER)
        val told = controller.preferences.value
        assertTrue(told.isStylusOnlyMode)
        assertEquals(false, told.isSnapEnabled)
        assertEquals(Tool.INK_HIGHLIGHTER, told.lastSelectedTool)

        // Another editor takes them back
        val other = PageboardController(document = Document(), coroutineScope = backgroundScope)
        other.applyPreferences(EditorPreferences.decode(told.encode())!!)
        assertTrue(other.isStylusOnlyMode)
        assertEquals(false, other.snapManager.isEnabled)
        assertEquals(false, other.shapeSnapper.isEnabled)
        assertEquals(Tool.INK_HIGHLIGHTER, other.selectedTool)
        assertEquals(told, other.preferences.value)
    }

    @Test
    fun thePreferencesWrittenBeforeTheSuggestionsTurnThemOn() {
        // As the version before the suggestions while writing writes them (the defaults left out)
        val written = """{"isStylusOnlyMode":true,"lastSelectedTool":"TEXT","isShapeRecognitionEnabled":false}"""
        val preferences = EditorPreferences.decode(written)!!
        assertTrue(preferences.areTextSuggestionsEnabled)
        assertEquals(Tool.TEXT, preferences.lastSelectedTool)
        val off = EditorPreferences(areTextSuggestionsEnabled = false)
        assertEquals(off, EditorPreferences.decode(off.encode()))
    }

    @Test
    fun theSuggestionsTurnedOffAreKept() = runTest {
        val controller = PageboardController(document = Document(), coroutineScope = backgroundScope)
        assertTrue(controller.areTextSuggestionsEnabled)
        controller.updateTextSuggestionsEnabled(false)
        assertEquals(false, controller.preferences.value.areTextSuggestionsEnabled)
        val reopened = PageboardController(document = Document(), coroutineScope = backgroundScope)
        reopened.applyPreferences(controller.preferences.value)
        assertEquals(false, reopened.areTextSuggestionsEnabled)
    }
}
