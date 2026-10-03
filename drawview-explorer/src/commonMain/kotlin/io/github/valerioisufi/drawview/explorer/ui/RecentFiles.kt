package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_recents_title
import org.jetbrains.compose.resources.stringResource

/**
 * The most recently opened documents.
 */
@Composable
fun RecentFiles(
    fileExplorerViewModel: FileExplorerViewModel,
    onOpenDocument: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(Res.string.explorer_recents_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(fileExplorerViewModel.recentFiles, key = { it.key }) { file ->
                FileListItem(
                    dataFile = file,
                    fileExplorerViewModel = fileExplorerViewModel,
                    onOpenDocument = onOpenDocument
                )
            }
        }
    }
}
