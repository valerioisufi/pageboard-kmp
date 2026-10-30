package io.github.valerioisufi.drawview.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The width of [AdaptiveSheet] in a window that is not compact: a wide dialog as
 * wide as it asks (or as the window less its margins), a narrow one as wide as its content, as in
 * Material 3 (docs/ui-fit.md).
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveSheetWidthTest {

    private fun dialogWidth(windowWidth: Int, maxWidth: Dp, content: @Composable () -> Unit): Dp {
        var width = 0.dp
        runDesktopComposeUiTest(width = windowWidth, height = 800) {
            setContent {
                DrawViewTheme(dynamicColor = false) {
                    AdaptiveSheet(onDismissRequest = {}, maxWidth = maxWidth) { content() }
                }
            }
            // The box of the dialog of Material 3, which names its pane
            val bounds = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).getBoundsInRoot()
            width = bounds.right - bounds.left
        }
        return width
    }

    @Test
    fun aWideDialogIsAsWideAsItAsks() {
        assertEquals(940.dp, dialogWidth(1280, 940.dp) { Box(Modifier.fillMaxWidth().height(40.dp)) })
    }

    @Test
    fun aWideDialogInANarrowerWindowKeepsItsMargins() {
        assertEquals(752.dp, dialogWidth(800, 940.dp) { Box(Modifier.fillMaxWidth().height(40.dp)) })
    }

    @Test
    fun aWideDialogIsAsWideWithANarrowContent() {
        assertEquals(760.dp, dialogWidth(1280, 760.dp) { Text("A few words") })
    }

    @Test
    fun aNarrowDialogIsAsWideAsItsContent() {
        assertEquals(320.dp, dialogWidth(1280, DialogWidth) { Box(Modifier.width(320.dp).height(40.dp)) })
    }

    @Test
    fun aNarrowDialogStopsAtTheWidthOfMaterial() {
        assertEquals(DialogWidth, dialogWidth(1280, DialogWidth) { Box(Modifier.fillMaxWidth().height(40.dp)) })
    }
}
