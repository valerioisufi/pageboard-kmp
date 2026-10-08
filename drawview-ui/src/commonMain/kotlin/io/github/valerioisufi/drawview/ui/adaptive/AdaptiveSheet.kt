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
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A sheet of the application (a configurator, the details of a file, settings): a bottom sheet in
 * a compact window (a phone), a dialog in a wider one (a tablet, a desktop), where a sheet rising
 * from the bottom of a large screen would be far from everything. The same [content] in both.
 *
 * @param title its title, if any.
 * @param actions its buttons (e.g. cancel, save), at the end of its bottom.
 * @param content what it shows; it scrolls its own content and gives it its margins.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveSheet(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (currentAdaptiveInfo().window.isCompact) {
        ModalBottomSheet(onDismissRequest = onDismissRequest) {
            SheetTitle(title)
            Column(modifier = Modifier.weight(1f, fill = false)) { content() }
            SheetActions(actions, Modifier.padding(bottom = 16.dp))
        }
    } else {
        BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = Modifier.widthIn(max = 560.dp)) {
            Surface(
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation
            ) {
                Column(modifier = Modifier.padding(vertical = 24.dp)) {
                    SheetTitle(title)
                    Column(modifier = Modifier.weight(1f, fill = false)) { content() }
                    SheetActions(actions)
                }
            }
        }
    }
}

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
