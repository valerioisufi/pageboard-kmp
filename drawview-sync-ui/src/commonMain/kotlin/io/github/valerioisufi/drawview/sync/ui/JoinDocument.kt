package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.sync.Invitation
import io.github.valerioisufi.drawview.sync.JoinRequest
import io.github.valerioisufi.drawview.sync.JoinStatus
import io.github.valerioisufi.drawview.sync.Role
import io.github.valerioisufi.drawview.sync.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.session.LibraryAction
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The action of the library joining a document shared by another device (by its invitation): a
 * wide button of the navigation pane where there is one.
 */
fun Collaboration.joinAction(): LibraryAction = LibraryAction(
    icon = DrawViewIcons.Link,
    label = { stringResource(Res.string.sync_join) },
    prominent = true
) { onDone -> JoinDialog(this, link = "", onDismiss = onDone) }

/**
 * The dialogs of the collaboration shown wherever the application is: joining the invitations
 * opened from outside (see [InvitationLinks]), approving the devices joining the documents this
 * device owns.
 */
@Composable
fun CollaborationDialogs(collaboration: Collaboration) {
    val link by InvitationLinks.link.collectAsState()
    link?.let { JoinDialog(collaboration, it, onDismiss = InvitationLinks::consume) }
    val requests by collaboration.joinRequests.collectAsState()
    requests.firstOrNull()?.let { request -> JoinRequestDialog(collaboration, request) }
}

/** A device asks to join a document of this device: let in or refused. */
@Composable
private fun JoinRequestDialog(collaboration: Collaboration, request: JoinRequest) {
    val documents by collaboration.documentTitles.collectAsState(emptyMap())
    val role = stringResource(if (request.role == Role.READER) Res.string.sync_role_reader else Res.string.sync_role_editor)
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.GroupAdd, contentDescription = null) },
        title = { Text(stringResource(Res.string.sync_request_title)) },
        text = { Text(stringResource(Res.string.sync_request_message, request.name, documents[request.documentId].orEmpty(), role)) },
        confirmButton = { TextButton(onClick = { collaboration.answer(request, approve = true) }) { Text(stringResource(Res.string.sync_approve)) } },
        dismissButton = { TextButton(onClick = { collaboration.answer(request, approve = false) }) { Text(stringResource(Res.string.sync_refuse)) } }
    )
}

/** Joins the document of the invitation [link] (typed or pasted when empty). */
@Composable
private fun JoinDialog(collaboration: Collaboration, link: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember(link) { mutableStateOf(link) }
    var invalid by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf<Invitation?>(null) }
    val online by collaboration.isOnline.collectAsState()
    val join: () -> Unit = {
        scope.launch {
            val invitation = collaboration.join(text)
            invalid = invitation == null
            joined = invitation
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.GroupAdd, contentDescription = null) },
        title = { Text(stringResource(Res.string.sync_join)) },
        text = {
            val done = joined
            val joining by collaboration.joining.collectAsState()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (done != null) {
                    Text(
                        stringResource(
                            when (joining[done.documentId]) {
                                JoinStatus.WAITING_APPROVAL -> Res.string.sync_join_waiting_approval
                                JoinStatus.ADMITTED -> Res.string.sync_join_admitted
                                JoinStatus.REFUSED -> Res.string.sync_join_refused
                                else -> Res.string.sync_join_waiting
                            },
                            done.title
                        )
                    )
                    if (!online) Text(stringResource(Res.string.sync_no_network))
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            invalid = false
                        },
                        label = { Text(stringResource(Res.string.sync_join_hint)) },
                        isError = invalid,
                        supportingText = if (invalid) ({ Text(stringResource(Res.string.sync_join_invalid)) }) else null,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (joined != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_close)) }
            } else {
                TextButton(onClick = join, enabled = text.isNotBlank()) { Text(stringResource(Res.string.sync_join_action)) }
            }
        },
        dismissButton = {
            if (joined == null) TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_cancel)) }
        }
    )
}
