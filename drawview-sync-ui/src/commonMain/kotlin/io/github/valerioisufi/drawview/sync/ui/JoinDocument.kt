package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.sync.JoinRequest
import io.github.valerioisufi.drawview.sync.JoinStatus
import io.github.valerioisufi.drawview.sync.SpaceRoot
import io.github.valerioisufi.drawview.sync.protocol.Invitation
import io.github.valerioisufi.drawview.sync.protocol.InvitationKind
import io.github.valerioisufi.drawview.sync.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.extension.LibraryAction
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The action of the library joining a document shared by another device (by its invitation): a
 * wide button of the navigation pane where there is one.
 */
public fun Collaboration.joinAction(): LibraryAction = LibraryAction(
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
public fun CollaborationDialogs(collaboration: Collaboration) {
    val link by InvitationLinks.link.collectAsState()
    link?.let { JoinDialog(collaboration, it, onDismiss = InvitationLinks::consume) }
    val requests by collaboration.joinRequests.collectAsState()
    requests.firstOrNull()?.let { request -> JoinRequestDialog(collaboration, request) }
}

/** A device asks to join a document of this device: who it is and the role it asks, let in or refused. */
@Composable
private fun JoinRequestDialog(collaboration: Collaboration, request: JoinRequest) {
    val documents by collaboration.documentTitles.collectAsState(emptyMap())
    val role = stringResource(roleLabel(request.role))
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(DrawViewIcons.UserPlus, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
        title = { Text(stringResource(Res.string.sync_request_title), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MemberAvatar(request.name, colorOf(request.device))
                    Column {
                        Text(request.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    stringResource(Res.string.sync_request_message, request.name, documents[request.documentId].orEmpty(), role),
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = { Button(onClick = { collaboration.answer(request, approve = true) }) { Text(stringResource(Res.string.sync_approve)) } },
        dismissButton = { TextButton(onClick = { collaboration.answer(request, approve = false) }) { Text(stringResource(Res.string.sync_refuse)) } }
    )
}

/**
 * Joins the document of the invitation [link] (typed or pasted when empty), then tells how it
 * goes: looking for the owner, waiting for it to let this device in, in, refused; without the
 * local network, why the devices do not find each other.
 */
@Composable
private fun JoinDialog(collaboration: Collaboration, link: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember(link) { mutableStateOf(link) }
    var invalid by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf<Invitation?>(null) }
    // A shared library: into a new folder, or into this library (both become one)
    var intoLibrary by remember { mutableStateOf(false) }
    val online by collaboration.isOnline.collectAsState()
    val joining by collaboration.joining.collectAsState()
    val parsed = remember(text) { Invitation.parse(text) }
    val join: () -> Unit = {
        scope.launch {
            val root = if (parsed?.kind == InvitationKind.LIBRARY && intoLibrary) SpaceRoot.Library else SpaceRoot.NewFolder
            val invitation = collaboration.join(text, root)
            invalid = invitation == null
            joined = invitation
        }
    }
    val status = joined?.let { joining[it.documentId] }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val kind = joined?.kind ?: parsed?.kind
            Text(
                stringResource(
                    when (kind) {
                        InvitationKind.FOLDER -> Res.string.sync_join_folder
                        InvitationKind.LIBRARY -> Res.string.sync_join_library
                        else -> Res.string.sync_join
                    }
                )
            )
        },
        text = {
            val done = joined
            if (done != null) {
                JoinProgress(status, done.title, online, done.kind)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (parsed?.kind == InvitationKind.LIBRARY) LibraryRootChoice(parsed.title, intoLibrary) { intoLibrary = it }
                }
            }
        },
        confirmButton = {
            when {
                joined == null -> Button(onClick = join, enabled = text.isNotBlank()) { Text(stringResource(Res.string.sync_join_action)) }
                status == JoinStatus.ADMITTED -> Button(onClick = onDismiss) { Text(stringResource(Res.string.sync_close)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_close)) }
            }
        },
        dismissButton = {
            if (joined == null) TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_cancel)) }
        }
    )
}

/** Where a shared library joined goes: a new folder named [title], or this library ([intoLibrary]: both become one). */
@Composable
internal fun LibraryRootChoice(title: String, intoLibrary: Boolean, onChange: (Boolean) -> Unit) {
    Column(modifier = Modifier.selectableGroup()) {
        Text(stringResource(Res.string.sync_join_library_where), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(false to stringResource(Res.string.sync_join_library_new_folder, title), true to stringResource(Res.string.sync_join_library_merge)).forEach { (choice, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = intoLibrary == choice, onClick = { onChange(choice) }, role = androidx.compose.ui.semantics.Role.RadioButton)
                    .padding(vertical = 6.dp)
            ) {
                RadioButton(selected = intoLibrary == choice, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** How joining the document or the shared folder [title] goes ([status]), with its icon or its spinner. */
@Composable
internal fun JoinProgress(status: JoinStatus?, title: String, online: Boolean, kind: InvitationKind = InvitationKind.DOCUMENT) {
    if (!online && status != JoinStatus.ADMITTED && status != JoinStatus.REFUSED) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Notice(DrawViewIcons.WifiOff, stringResource(Res.string.sync_no_network))
            Text(stringResource(Res.string.sync_join_waiting, title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        when (status) {
            JoinStatus.ADMITTED -> Icon(DrawViewIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            JoinStatus.REFUSED -> Icon(DrawViewIcons.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(26.dp))
            else -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp, trackColor = MaterialTheme.colorScheme.primaryContainer)
        }
        Text(
            stringResource(
                when (status) {
                    JoinStatus.WAITING_APPROVAL -> Res.string.sync_join_waiting_approval
                    JoinStatus.ADMITTED -> if (kind == InvitationKind.DOCUMENT) Res.string.sync_join_admitted else Res.string.sync_join_folder_admitted
                    JoinStatus.REFUSED -> Res.string.sync_join_refused
                    else -> if (kind == InvitationKind.DOCUMENT) Res.string.sync_join_waiting else Res.string.sync_join_folder_waiting
                },
                title
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
