package io.github.valerioisufi.drawview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.valerioisufi.drawview.explorer.DocumentLibrary
import io.github.valerioisufi.drawview.ui.command.CommandHost
import io.github.valerioisufi.drawview.ui.command.CommandRoot
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.app_name
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import org.jetbrains.compose.resources.stringResource

/**
 * DrawView sample application: the document library and the editor of the open document, with
 * the DrawView theme.
 *
 * @param commandHost the commands of the screen shown: the desktop gives them to the menu bar and
 *   to the shortcuts of its window.
 */
@Composable
fun App(commandHost: CommandHost = remember { CommandHost() }) {
    DrawViewTheme {
        CommandRoot(host = commandHost) {
            DocumentLibrary(
                title = { Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
