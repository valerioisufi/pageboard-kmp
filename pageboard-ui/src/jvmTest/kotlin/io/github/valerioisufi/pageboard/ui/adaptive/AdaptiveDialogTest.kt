package io.github.valerioisufi.pageboard.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ui.theme.PageboardTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [AdaptiveDialog] in small windows: never wider or higher than the window less its margins, its
 * buttons in sight below a long text (docs/plans/small-screen-modals.md).
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveDialogTest {

    private class Bounds(val dialog: DpRect, val confirm: DpRect, val dismiss: DpRect)

    private fun bounds(width: Int, height: Int, textHeight: Int, confirm: String = "Delete", dismiss: String = "Cancel"): Bounds {
        lateinit var bounds: Bounds
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                PageboardTheme(dynamicColor = false) {
                    AdaptiveDialog(
                        onDismissRequest = {},
                        title = { Text("Delete the note?") },
                        text = { Box(Modifier.fillMaxWidth().height(textHeight.dp)) },
                        confirmButton = { TextButton(onClick = {}) { Text(confirm) } },
                        dismissButton = { TextButton(onClick = {}) { Text(dismiss) } }
                    )
                }
            }
            bounds = Bounds(
                onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).getBoundsInRoot(),
                onNodeWithText(confirm).getBoundsInRoot(),
                onNodeWithText(dismiss).getBoundsInRoot()
            )
        }
        return bounds
    }

    @Test
    fun inAWindowNarrowerThanMaterialItKeepsItsMargins() {
        val dialog = bounds(width = 300, height = 600, textHeight = 40).dialog
        assertTrue(dialog.left >= 24.dp && dialog.right <= 276.dp, "dialog from ${dialog.left} to ${dialog.right}")
    }

    @Test
    fun aLongTextScrollsAndTheButtonsStayInSight() {
        val bounds = bounds(width = 400, height = 360, textHeight = 900)
        assertTrue(bounds.dialog.top >= 24.dp && bounds.dialog.bottom <= 336.dp, "dialog from ${bounds.dialog.top} to ${bounds.dialog.bottom}")
        assertTrue(bounds.confirm.bottom <= bounds.dialog.bottom, "confirm ending at ${bounds.confirm.bottom}")
    }

    @Test
    fun longButtonsGoOnASecondLine() {
        val bounds = bounds(width = 320, height = 600, textHeight = 40, confirm = "Replace every background of the document", dismiss = "Keep the custom backgrounds")
        assertTrue(bounds.confirm.right <= bounds.dialog.right && bounds.dismiss.right <= bounds.dialog.right)
        assertTrue(bounds.confirm.top >= bounds.dismiss.bottom, "confirm at ${bounds.confirm.top}, cancel ending at ${bounds.dismiss.bottom}")
    }
}
