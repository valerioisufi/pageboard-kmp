package io.github.valerioisufi.pageboard.sync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.valerioisufi.pageboard.sync.protocol.InvitationKind
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.extension.LibraryItemsAction
import io.github.valerioisufi.pageboard.ui.extension.LibrarySelection
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource

/** What the sharing dialog shares: a document, or a folder of the library (`null`: the library itself, see `docs/plans/shared-folders.md`). */
internal sealed interface ShareTarget {
    val title: String
    val kind: InvitationKind

    data class Document(val documentId: String, override val title: String) : ShareTarget {
        override val kind: InvitationKind get() = InvitationKind.DOCUMENT
    }

    data class Folder(val folderId: String?, override val title: String) : ShareTarget {
        override val kind: InvitationKind get() = if (folderId == null) InvitationKind.LIBRARY else InvitationKind.FOLDER
    }
}

/**
 * "Share…" on the items of the library and "Share folder…" / "Share library…" on the folder shown
 * (see `DocumentLibrary.itemsActions`): the sharing dialog of a note, of a folder, or of the whole
 * library, whose notes and folders, the ones added later too, are then kept the same on the
 * devices of its members. A folder is shared where the store keeps shared folders
 * ([Collaboration.sharesFolders]); a selection of several items shares nothing.
 */
public fun Collaboration.shareAction(): LibraryItemsAction = LibraryItemsAction(
    icon = PageboardIcons.UserPlus,
    label = { stringResource(Res.string.sync_share_items_action) },
    folderLabel = { home -> stringResource(if (home) Res.string.sync_share_library_action else Res.string.sync_share_folder_action) },
    content = { selection, onDone -> ShareSelection(this, selection, onDone) }
)

@Composable
private fun ShareSelection(collaboration: Collaboration, selection: LibrarySelection, onDone: () -> Unit) {
    val titles by collaboration.documentTitles.collectAsState(emptyMap())
    val folders by collaboration.folderNames.collectAsState(emptyMap())
    val libraryTitle = stringResource(Res.string.sync_library_title)
    val target = when (selection) {
        is LibrarySelection.Folder -> selection.folderId.let { id -> ShareTarget.Folder(id, id?.let { folders[it] } ?: libraryTitle) }
        is LibrarySelection.Items -> when {
            selection.documentIds.size == 1 && selection.folderIds.isEmpty() ->
                selection.documentIds.single().let { ShareTarget.Document(it, titles[it].orEmpty()) }
            selection.folderIds.size == 1 && selection.documentIds.isEmpty() ->
                selection.folderIds.single().let { ShareTarget.Folder(it, folders[it].orEmpty()) }
            else -> null
        }
    }
    if (target == null || (target is ShareTarget.Folder && !collaboration.sharesFolders)) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    ShareDialog(collaboration, target, onDismiss = onDone)
}
