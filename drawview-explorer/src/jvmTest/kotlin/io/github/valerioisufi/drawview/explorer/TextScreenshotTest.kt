package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStoreOwner
import io.github.valerioisufi.drawview.core.model.TextAlignment
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.ui.adaptive.ReferenceWindow
import java.util.Locale
import java.util.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the texts (docs/text-editing.md): a text being written, the texts as the page
 * draws them, a text selected.
 */
@OptIn(ExperimentalTestApi::class)
class TextScreenshotTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** A text being written: its frame, its field, the formatting bar. */
    @Test
    fun textWriting() = WINDOWS.forEach { window ->
        capture("text-writing", window, dark = false) { viewModels ->
            openNote(viewModels)
            write(viewModels, 20f, 30f, "Area of a circle: \$\\pi r^2\$\n**Bold** and *italic*")
        }
    }

    /** Texts drawn by the page: aligned in their box, with a formula in the line and one on a line of its own. */
    @Test
    fun textSaved() = capture("text-saved", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 30f, "Area of a circle: \$\\pi r^2\$ and **bold**")
        save(viewModels)
        write(viewModels, 20f, 60f, "Centered title", TextAlignment.CENTER)
        save(viewModels)
        write(viewModels, 20f, 80f, "The integral\n\$\$\\int_0^1 x^2\\,dx = \\frac{1}{3}\$\$\nis a third.")
        save(viewModels)
    }

    /** A text selected: the frame of the selection. */
    @Test
    fun textSelected() = WINDOWS.forEach { window ->
        capture("text-selected", window, dark = false) { viewModels ->
            openNote(viewModels)
            write(viewModels, 20f, 30f, "Area of a circle: \$\\pi r^2\$")
            save(viewModels)
            runOnIdle {
                val controller = viewModels.controller()
                controller.selectTool(Tool.LAZO)
                controller.selectionManager.selectText(0, controller.document.pages[0].texts.single())
            }
        }
    }

    /** Starts a text at ([xMm], [yMm]) of the first page and types [text] in its field. */
    private fun DesktopComposeUiTest.write(
        viewModels: ViewModelStoreOwner,
        xMm: Float,
        yMm: Float,
        text: String,
        alignment: TextAlignment = TextAlignment.START
    ) {
        runOnIdle {
            val controller = viewModels.controller()
            controller.selectTool(Tool.TEXT)
            controller.textEditorManager.startNewText(0, xMm, yMm)
            controller.textEditorManager.changeAlignment(alignment)
        }
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput(text)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.save(viewModels: ViewModelStoreOwner) {
        runOnIdle { viewModels.controller().textEditorManager.finishEditing() }
        waitForIdle()
    }

    private companion object {
        val WINDOWS = listOf(ReferenceWindow.PHONE, ReferenceWindow.TABLET_PORTRAIT, ReferenceWindow.DESKTOP)
    }
}
