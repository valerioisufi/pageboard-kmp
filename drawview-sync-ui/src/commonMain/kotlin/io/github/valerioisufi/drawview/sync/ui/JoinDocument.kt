package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.sync.Invitation
import io.github.valerioisufi.drawview.sync.JoinRequest
import io.github.valerioisufi.drawview.sync.JoinStatus
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
    val online by collaboration.isOnline.collectAsState()
    val joining by collaboration.joining.collectAsState()
    val join: () -> Unit = {
        scope.launch {
            val invitation = collaboration.join(text)
            invalid = invitation == null
            joined = invitation
        }
    }
    val status = joined?.let { joining[it.documentId] }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sync_join)) },
        text = {
            val done = joined
            if (done != null) {
                JoinProgress(status, done.title, online)
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
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

/** How joining the document [title] goes ([status]), with its icon or its spinner. */
@Composable
internal fun JoinProgress(status: JoinStatus?, title: String, online: Boolean) {
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
                    JoinStatus.ADMITTED -> Res.string.sync_join_admitted
                    JoinStatus.REFUSED -> Res.string.sync_join_refused
                    else -> Res.string.sync_join_waiting
                },
                title
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
