package io.github.valerioisufi.pageboard.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.valerioisufi.pageboard.ui.components.DesignIconButton
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_close
import io.github.valerioisufi.pageboard.ui.platform.PopupSystemBarsEffect
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource

/** How the body of an [AdaptiveSheet] scrolls. */
public enum class SheetBody {
    /**
     * The sheet scrolls the body itself, in one scroll, and gives it its margins at the sides: the
     * content is a plain column.
     */
    Scrolling,

    /**
     * The body is given the room left by the header and the actions, and fills it its own way: a
     * lazy list, panes side by side, its own margins.
     */
    Fixed
}

/**
 * A sheet of the application (a configurator, the details of a file, settings): framed as
 * [currentModalFrame] tells, a bottom sheet on a phone, a dialog in a wider window, a dialog
 * taking the window in a narrow one reached with a mouse. The same parts in each, and the same
 * scrolling: the header (the [title], the close button) and the [actions] stay in sight, the body
 * scrolls between them; a bottom sheet opens at the height of its content (up to the window,
 * never half way) and makes room for the keyboard.
 *
 * @param title its title, if any.
 * @param actions its buttons (e.g. cancel, save), at the end of its bottom.
 * @param maxWidth the widest the dialog is (a wider window). Up to 560 dp the dialog is as wide
 *   as its content, as in Material 3; above it, the dialog is that wide, or as wide as the window
 *   less its margins, since its content is laid out for it (panes, rows side by side). A wide dialog
 *   keeps its margins above and below too.
 * @param padded whether the dialog keeps its margins above and below the content (else the content
 *   reaches its edges, e.g. a pane of navigation).
 * @param onClose shows a close button at the end of the header, calling it.
 * @param body whether the sheet scrolls [content] itself ([SheetBody.Scrolling]) or gives it the
 *   room left ([SheetBody.Fixed]).
 * @param content what it shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun AdaptiveSheet(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    maxWidth: Dp = 560.dp,
    padded: Boolean = true,
    onClose: (() -> Unit)? = null,
    body: SheetBody = SheetBody.Scrolling,
    content: @Composable ColumnScope.() -> Unit
) {
    val frame = currentModalFrame()
    if (frame == ModalFrame.BottomSheet) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            PopupSystemBarsEffect()
            Column(modifier = Modifier.imePadding()) {
                SheetHeader(title, onClose)
                SheetBodyOf(body, content)
                SheetActions(actions, Modifier.padding(bottom = 16.dp))
            }
        }
    } else {
        // BasicAlertDialog bounds its box to DialogWidth: only a fixed width before it wins
        val fullWindow = frame == ModalFrame.FullWindowDialog
        val wide = fullWindow || maxWidth > DialogWidth
        val margin = if (fullWindow) FullWindowMargin else DialogMargin
        BasicAlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = if (wide) {
                Modifier.padding(margin).imePadding().windowHeightLimit(margin).widthIn(max = if (fullWindow) Dp.Unspecified else maxWidth).fillMaxWidth()
            } else {
                Modifier.imePadding().windowHeightLimit(DialogMargin).widthIn(max = maxWidth)
            },
            properties = DialogProperties(usePlatformDefaultWidth = !wide)
        ) {
            PopupSystemBarsEffect()
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(vertical = if (padded) 24.dp else 0.dp)) {
                    SheetHeader(title, onClose)
                    SheetBodyOf(body, content)
                    SheetActions(actions)
                }
            }
        }
    }
}

/** The widest a dialog of Material 3 is, and the widest [AdaptiveSheet] is as wide as its content. */
internal val DialogWidth: Dp = 560.dp

/** The margin between a wide dialog and the edges of the window, which the platform gives a narrow one. */
internal val DialogMargin: Dp = 24.dp

/** The margin of a [ModalFrame.FullWindowDialog]. */
private val FullWindowMargin: Dp = 8.dp

/** The margin at the sides of the parts of a sheet. */
private val SheetMargin: Dp = 24.dp

@Composable
private fun SheetHeader(title: (@Composable () -> Unit)?, onClose: (() -> Unit)?) {
    if (title == null && onClose == null) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = SheetMargin, end = if (onClose != null) 12.dp else SheetMargin, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (title != null) ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
        }
        if (onClose != null) DesignIconButton(PageboardIcons.Close, stringResource(Res.string.common_action_close), onClose)
    }
}

@Composable
private fun ColumnScope.SheetBodyOf(body: SheetBody, content: @Composable ColumnScope.() -> Unit) {
    when (body) {
        SheetBody.Scrolling -> Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = SheetMargin),
            content = content
        )
        SheetBody.Fixed -> Column(modifier = Modifier.weight(1f, fill = false), content = content)
    }
}

@Composable
private fun SheetActions(actions: (@Composable RowScope.() -> Unit)?, modifier: Modifier = Modifier) {
    if (actions == null) return
    // On a second line rather than cut, when the buttons do not fit side by side
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = SheetMargin, end = SheetMargin, top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        actions()
    }
}
