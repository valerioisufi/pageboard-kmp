package io.github.valerioisufi.pageboard.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.pageboard.explorer.ui.LibraryAppBar
import io.github.valerioisufi.pageboard.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.pageboard.ui.adaptive.InputHints
import io.github.valerioisufi.pageboard.ui.components.SearchField
import io.github.valerioisufi.pageboard.ui.extension.LibraryAction
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import io.github.valerioisufi.pageboard.ui.theme.PageboardTheme
import kotlin.test.Test

/**
 * Screenshots of the bar of a narrow library with a brand as wide as the one of PencilNotes and
 * three actions: the brand whole, the search narrower, the buttons left out in the "more" menu.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryBarScreenshotTest {

    @Test
    fun theBarOfAList() = listOf(320, 360, 412).forEach { width -> capture(width, withSearch = false) }

    @Test
    fun theBarOfAGrid() = listOf(600, 700, 840).forEach { width -> capture(width, withSearch = true) }

    private fun capture(width: Int, withSearch: Boolean) = runDesktopComposeUiTest(width = width, height = 72) {
        val actions = listOf(PageboardIcons.Link, PageboardIcons.Search, PageboardIcons.Info).map { icon ->
            LibraryAction(icon = icon, label = { icon.name }) { }
        }
        setContent {
            PageboardTheme(darkTheme = false, dynamicColor = false) {
                AdaptiveRoot(hints = InputHints.Touch) {
                    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
                        LibraryAppBar(
                            brand = { Brand() },
                            actions = actions,
                            onAction = {},
                            onOpenTrash = {},
                            search = if (withSearch) { modifier -> SearchField("", {}, "Search notes", modifier = modifier) } else null
                        )
                    }
                }
            }
        }
        waitForIdle()
        val layout = if (withSearch) "grid" else "list"
        onRoot().captureRoboImage("screenshots/library-bar-$layout-$width.png")
    }

    /** A mark 40 dp wide and a name of 28 sp, as the brand of PencilNotes. */
    @androidx.compose.runtime.Composable
    private fun Brand() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)))
            Text("PencilNotes", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
