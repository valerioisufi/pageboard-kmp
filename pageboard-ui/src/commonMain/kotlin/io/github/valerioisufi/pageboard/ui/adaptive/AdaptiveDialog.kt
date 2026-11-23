package io.github.valerioisufi.pageboard.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.valerioisufi.pageboard.ui.platform.PopupSystemBarsEffect

/**
 * A short dialog (a confirmation, a question, a field to fill, a progress): the slots of the
 * `AlertDialog` of Material 3, framed so that it fits any window (docs/plans/small-screen-modals.md).
 * It is as wide as its content up to 560 dp, and never wider than the window less its margins,
 * even under the 280 dp of Material. It is never higher than the window: the [text] scrolls between
 * the title and the buttons, which stay in sight. The buttons go on a second line rather than
 * being cut. A dialog in the middle of the window on every device, since it is short.
 *
 * @param icon above the title, which is then centred, as in Material 3.
 * @param text what the dialog says or asks: a column, scrolled by the dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun AdaptiveDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    text: (@Composable ColumnScope.() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    properties: DialogProperties = DialogProperties()
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier.imePadding().windowWidthLimit(DialogMargin).windowHeightLimit(DialogMargin).widthIn(max = DialogWidth),
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            usePlatformDefaultWidth = false
        )
    ) {
        PopupSystemBarsEffect()
        Surface(shape = RoundedCornerShape(28.dp), color = containerColor) {
            Column(modifier = Modifier.padding(vertical = 24.dp)) {
                if (icon != null) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.secondary) {
                        Box(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp), contentAlignment = Alignment.Center) { icon() }
                    }
                }
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(textAlign = if (icon != null) TextAlign.Center else TextAlign.Start)) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            contentAlignment = if (icon != null) Alignment.Center else Alignment.CenterStart
                        ) { title() }
                    }
                }
                if (text != null) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState())
                                    .padding(start = 24.dp, end = 24.dp, top = 16.dp),
                                content = text
                            )
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
