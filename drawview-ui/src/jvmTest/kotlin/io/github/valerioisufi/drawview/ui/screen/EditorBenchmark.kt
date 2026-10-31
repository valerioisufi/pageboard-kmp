package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * What the editor composes again while it is used (see `docs/performance.md`, phase 5): at every
 * stroke drawn, and at every frame of a pan while a text is written.
 */
@OptIn(ExperimentalTestApi::class)
class EditorBenchmark {

    @Test
    fun drawingStrokes() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val counter = CompositionCounter()
        setContent {
            DrawViewTheme(dynamicColor = false) {
                counter.Observe { DrawScreen(controller, onNavigateBack = {}) }
            }
        }
        waitForIdle()
        val page = controller.document.pages.first()
        fun stroke() = runOnIdle { controller.session.submit(AddElements.of(page, listOf(CompositionCounter.stroke()))) }
        // The first strokes enable Undo
        repeat(WARM_UP) { stroke(); waitForIdle() }
        counter.reset()
        repeat(STROKES) { stroke(); waitForIdle() }
        val time = counter.describeTime(STROKES)
        counter.reset()
        counter.named = true
        repeat(NAMED) { stroke(); waitForIdle() }
        report("M8 a stroke drawn, in the editor", "$time, ${counter.describeScopes(NAMED)}")
    }

    @Test
    fun panningWhileWriting() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val counter = CompositionCounter()
        setContent {
            DrawViewTheme(dynamicColor = false) {
                counter.Observe { DrawScreen(controller, onNavigateBack = {}) }
            }
        }
        waitForIdle()
        runOnIdle { controller.textEditorManager.startNewText(pageIndex = 0, xMm = 40f, yMm = 60f) }
        waitForIdle()
        fun pan() = runOnIdle { controller.viewport.panBy(0f, -2f) }
        repeat(WARM_UP) { pan(); waitForIdle() }
        counter.reset()
        repeat(FRAMES) { pan(); waitForIdle() }
        val time = counter.describeTime(FRAMES)
        counter.reset()
        counter.named = true
        repeat(NAMED) { pan(); waitForIdle() }
        report("M8 a frame of a pan while a text is written, in the editor", "$time, ${counter.describeScopes(NAMED)}")
    }

    private fun report(measure: String, value: String) = println("BENCHMARK $measure: $value")

    private companion object {
        const val WARM_UP = 10
        const val STROKES = 100
        const val FRAMES = 100
        const val NAMED = 10
    }
}
