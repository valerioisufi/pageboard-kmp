package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * A row of the tools that does not fit ends in the middle of a tool (see `peekingHorizontalScroll`),
 * whatever its content is, and a row that fits keeps its width.
 */
@OptIn(ExperimentalTestApi::class)
class PeekingScrollTest {

    /** Ten items 50 dp apart in 200 dp: three whole ones and half of the fourth, 175 dp. */
    @Test
    fun aRowThatDoesNotFitEndsInTheMiddleOfAnItemWithAnyContent() = runDesktopComposeUiTest {
        setContent {
            Box(modifier = Modifier.width(200.dp)) {
                PeekingRow(items = 10, withConstraints = true)
            }
        }
        onNodeWithTag(ROW).assertWidthIsEqualTo(175.dp)
    }

    /** Three items, 148 dp of content in 200 dp: as they are. */
    @Test
    fun aRowThatFitsKeepsItsWidth() = runDesktopComposeUiTest {
        setContent {
            Box(modifier = Modifier.width(200.dp)) {
                PeekingRow(items = 3, withConstraints = false)
            }
        }
        onNodeWithTag(ROW).assertWidthIsEqualTo(148.dp)
    }

    /** [items] boxes 48 dp wide and 2 dp apart, the last one in a `BoxWithConstraints` when [withConstraints]. */
    @androidx.compose.runtime.Composable
    private fun PeekingRow(items: Int, withConstraints: Boolean) {
        val content = remember { ScrolledContent() }
        Row(
            modifier = Modifier.testTag(ROW).peekingHorizontalScroll(rememberScrollState(), 50.dp, content),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            repeat(if (withConstraints) items - 1 else items) { Box(modifier = Modifier.size(48.dp)) }
            // A SubcomposeLayout, which has no intrinsic size
            if (withConstraints) BoxWithConstraints(modifier = Modifier.size(48.dp)) { Box(modifier = Modifier.size(maxWidth)) }
        }
    }

    private companion object {
        const val ROW = "row"
    }
}
