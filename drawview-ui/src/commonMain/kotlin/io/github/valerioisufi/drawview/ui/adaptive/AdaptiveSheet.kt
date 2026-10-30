package io.github.valerioisufi.drawview.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * A sheet of the application (a configurator, the details of a file, settings): a bottom sheet in
 * a compact window (a phone), a dialog in a wider one (a tablet, a desktop), where a sheet rising
 * from the bottom of a large screen would be far from everything. The same [content] in both.
 *
 * @param title its title, if any.
 * @param actions its buttons (e.g. cancel, save), at the end of its bottom.
 * @param maxWidth the widest the dialog is (a wider window). Up to 560 dp the dialog is as wide
 *   as its content, as in Material 3; above it, the dialog is that wide, or as wide as the window
 *   less its margins, since its content is laid out for it (panes, rows side by side). A wide dialog
 *   keeps its margins above and below too.
 * @param padded whether the dialog keeps its margins above and below the content (else the content
 *   reaches its edges, e.g. a pane of navigation).
 * @param content what it shows; it scrolls its own content and gives it its margins.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun AdaptiveSheet(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    maxWidth: Dp = 560.dp,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    if (currentAdaptiveInfo().window.isCompact) {
        ModalBottomSheet(onDismissRequest = onDismissRequest, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            SheetTitle(title)
            Column(modifier = Modifier.weight(1f, fill = false)) { content() }
            SheetActions(actions, Modifier.padding(bottom = 16.dp))
        }
    } else {
        // BasicAlertDialog bounds its box to DialogWidth: only a fixed width before it wins
        val wide = maxWidth > DialogWidth
        BasicAlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = if (wide) {
                Modifier.padding(DialogMargin).widthIn(max = maxWidth).fillMaxWidth()
            } else {
                Modifier.widthIn(max = maxWidth)
            },
            properties = DialogProperties(usePlatformDefaultWidth = !wide)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(vertical = if (padded) 24.dp else 0.dp)) {
                    SheetTitle(title)
                    Column(modifier = Modifier.weight(1f, fill = false)) { content() }
                    SheetActions(actions)
                }
            }
        }
    }
}

/** The widest a dialog of Material 3 is, and the widest [AdaptiveSheet] is as wide as its content. */
internal val DialogWidth: Dp = 560.dp

/** The margin between a wide dialog and the edges of the window, which the platform gives a narrow one. */
private val DialogMargin: Dp = 24.dp

@Composable
private fun SheetTitle(title: (@Composable () -> Unit)?) {
    if (title == null) return
    Box(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
        ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
    }
}

@Composable
private fun SheetActions(actions: (@Composable RowScope.() -> Unit)?, modifier: Modifier = Modifier) {
    if (actions == null) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
    ) {
        actions()
    }
}
