package io.github.valerioisufi.pageboard.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.explorer.ui.LibraryOverlayLayout
import io.github.valerioisufi.pageboard.explorer.ui.bottomReach
import kotlin.test.Test
import kotlin.test.assertEquals

/** The room the navigation pane keeps for what the application draws over the library. */
@OptIn(ExperimentalTestApi::class)
class LibraryOverlayLayoutTest {

    private fun rect(x: Int, y: Int, width: Int, height: Int) = IntRect(IntOffset(x, y), IntSize(width, height))

    @Test
    fun whatIsOverTheBandCounts() {
        // A layer 1000 × 800, the band 264 wide: a pill at the bottom start, 52 high and 16 from the edges
        assertEquals(68, bottomReach(listOf(rect(16, 732, 300, 52)), 1000, 800, 264, LayoutDirection.Ltr))
    }

    @Test
    fun whatIsBesideTheBandDoesNot() {
        // The status of the exports at the bottom center
        assertEquals(0, bottomReach(listOf(rect(400, 700, 200, 84)), 1000, 800, 264, LayoutDirection.Ltr))
        assertEquals(0, bottomReach(emptyList(), 1000, 800, 264, LayoutDirection.Ltr))
    }

    @Test
    fun theBandIsOnTheStartSide() {
        assertEquals(0, bottomReach(listOf(rect(16, 732, 200, 52)), 1000, 800, 264, LayoutDirection.Rtl))
        assertEquals(68, bottomReach(listOf(rect(784, 732, 200, 52)), 1000, 800, 264, LayoutDirection.Rtl))
    }

    @Test
    fun theHighestCounts() {
        assertEquals(120, bottomReach(listOf(rect(16, 732, 200, 52), rect(0, 680, 100, 20)), 1000, 800, 264, LayoutDirection.Ltr))
    }

    @Test
    fun theLayoutFollowsWhatIsDrawn() = runComposeUiTest {
        var shown by mutableStateOf(true)
        var reach = (-1).dp
        setContent {
            Box(modifier = Modifier.size(1000.dp, 800.dp)) {
                LibraryOverlayLayout(bandWidth = 264.dp, onBottomReach = { reach = it }, modifier = Modifier.matchParentSize()) {
                    if (shown) Box(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).size(200.dp, 52.dp))
                    Box(modifier = Modifier.matchParentSize())
                }
            }
        }
        waitForIdle()
        // The pill and its padding: 16 + 52 + 16
        assertNear(84.dp, reach)
        shown = false
        waitForIdle()
        assertNear(0.dp, reach)
    }

    private fun assertNear(expected: Dp, actual: Dp) = assertEquals(expected.value, actual.value, 1f)
}
