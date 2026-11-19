package io.github.valerioisufi.drawview.export.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.export.ArchiveException
import io.github.valerioisufi.drawview.export.ArchiveFormat
import io.github.valerioisufi.drawview.export.ArchiveImporter
import io.github.valerioisufi.drawview.export.ui.generated.resources.Res
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_cancel
import io.github.valerioisufi.drawview.export.ui.generated.resources.export_format_archive_hint
import io.github.valerioisufi.drawview.export.ui.generated.resources.import_archive_action
import io.github.valerioisufi.drawview.export.ui.generated.resources.import_archive_done
import io.github.valerioisufi.drawview.export.ui.generated.resources.import_archive_done_title
import io.github.valerioisufi.drawview.export.ui.generated.resources.import_archive_failed_title
import io.github.valerioisufi.drawview.export.ui.generated.resources.import_archive_not_an_archive
import io.github.valerioisufi.drawview.export.ui.generated.resources.import_archive_too_new
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_close
import io.github.valerioisufi.drawview.ui.platform.PickerType
import io.github.valerioisufi.drawview.ui.platform.rememberFilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Where the import of an archive is. */
private sealed interface ImportState {
    data object Choosing : ImportState
    data object Importing : ImportState
    class Done(val documents: Int, val folder: String) : ImportState
    class Failed(val reason: ArchiveException.Reason?) : ImportState
}

/**
 * Importing an archive of the application ([ArchiveFormat]) into the library: the user chooses the
 * file, its notes are added as new notes in a new folder named after the file ([ArchiveImporter]),
 * then the dialog says how many, or why it could not. Nothing of the library is changed.
 */
@Composable
internal fun ImportArchiveDialog(importer: ArchiveImporter, onDone: () -> Unit) {
    var state by remember { mutableStateOf<ImportState>(ImportState.Choosing) }
    val scope = rememberCoroutineScope()
    val pick = rememberFilePicker(PickerType.ARCHIVE) { file ->
        state = ImportState.Importing
        val folder = ArchiveFormat.folderNameOf(file.name)
        scope.launch {
            state = try {
                val result = withContext(Dispatchers.Default) { importer.import(file.bytes, folder) }
                ImportState.Done(result.documents, folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ArchiveException) {
                ImportState.Failed(e.reason)
            } catch (e: Throwable) {
                println("DrawView: cannot import ${file.name}: ${e.message}")
                ImportState.Failed(null)
            }
        }
    }
    val current = state
    AlertDialog(
        onDismissRequest = { if (current != ImportState.Importing) onDone() },
        title = {
            Text(
                stringResource(
                    when (current) {
                        is ImportState.Done -> Res.string.import_archive_done_title
                        is ImportState.Failed -> Res.string.import_archive_failed_title
                        else -> Res.string.import_archive_action
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (current) {
                    ImportState.Choosing -> Text(stringResource(Res.string.export_format_archive_hint))
                    ImportState.Importing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    is ImportState.Done -> Text(pluralStringResource(Res.plurals.import_archive_done, current.documents, current.documents, current.folder))
                    is ImportState.Failed -> Text(
                        stringResource(if (current.reason == ArchiveException.Reason.TOO_NEW) Res.string.import_archive_too_new else Res.string.import_archive_not_an_archive)
                    )
                }
            }
        },
        confirmButton = {
            when (current) {
                ImportState.Choosing -> Button(onClick = pick) { Text(stringResource(Res.string.import_archive_action)) }
                ImportState.Importing -> Unit
                else -> Button(onClick = onDone) { Text(stringResource(DrawViewRes.string.common_action_close)) }
            }
        },
        dismissButton = {
            if (current == ImportState.Choosing) TextButton(onClick = onDone) { Text(stringResource(Res.string.export_cancel)) }
        }
    )
}
