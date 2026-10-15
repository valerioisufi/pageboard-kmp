package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.controller.Collaborator
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.platform.DeviceProfile
import io.github.valerioisufi.drawview.sync.InvitationGrant
import io.github.valerioisufi.drawview.sync.protocol.Invitation
import io.github.valerioisufi.drawview.sync.protocol.Role
import io.github.valerioisufi.drawview.sync.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.screen.EditorActionButton
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The collaboration in the editor: the action sharing the document (with the number of the other
 * devices on it; also in the menus, "Share…"), and over the canvas where the others are and what
 * they are drawing (who is writing a text the user tried to edit too) and the sharing dialog.
 */
public fun Collaboration.editorExtension(): EditorExtension {
    // The document whose sharing is shown, asked by the button or by the command
    val sharing = SharingShown()
    fun shareCommand(documentId: String) = Command(
        id = "share",
        label = { stringResource(Res.string.sync_share) },
        group = CommandGroup.FILE,
        section = 1,
        icon = { rememberVectorPainter(DrawViewIcons.UserPlus) }
    ) { sharing.documentId = documentId }
    return EditorExtension(
        toolbarActions = { context ->
            val command = remember(context) { shareCommand(context.documentId) }
            ShareAction(this@editorExtension, context.documentId, command)
        },
        canvasOverlay = { context ->
            CollaboratorsLayer(this@editorExtension, context.documentId, context.controller)
            WritingElsewhereNotice(context.controller)
            if (sharing.documentId == context.documentId) {
                ShareDialog(this@editorExtension, context.documentId, context.controller.document.title, onDismiss = { sharing.documentId = null })
            }
        },
        commands = { context -> listOf(shareCommand(context.documentId)) },
        readOnlyNote = { context ->
            // The owner decides who edits a shared document
            val memberships by memberships.collectAsState()
            val membership = memberships[context.documentId]
            val owner = membership?.takeIf { it.owner != device }?.let { it.memberOf(it.owner)?.name }
            owner?.let { stringResource(Res.string.sync_read_only_note, it) }
        }
    )
}

/** The document whose sharing dialog the editor shows, `null` when none. */
private class SharingShown {
    var documentId by mutableStateOf<String?>(null)
}

/** How long the user is told who is writing the text they tried to edit. */
private const val WRITING_ELSEWHERE_NOTICE_MS = 2_500L

/**
 * Who is writing the text the user tried to edit or to select (left to them, see
 * [CollaboratorPresence.editsRefused]), for a moment at the top of the canvas.
 */
@Composable
private fun BoxScope.WritingElsewhereNotice(controller: DrawViewController) {
    var writer by remember(controller) { mutableStateOf<Collaborator?>(null) }
    LaunchedEffect(controller) {
        controller.presence.editsRefused.collectLatest {
            writer = it
            delay(WRITING_ELSEWHERE_NOTICE_MS)
            writer = null
        }
    }
    val shown = writer ?: return
    // Not a Surface: the canvas keeps the touches around it
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
            .background(MaterialTheme.colorScheme.inverseSurface, CircleShape)
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(DrawViewIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(16.dp))
        Text(
            text = stringResource(Res.string.sync_writing_elsewhere, shown.name),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * Who else is on the document now (their avatars, not on a phone) and the button sharing it
 * ([share]), with their number.
 */
@Composable
private fun ShareAction(collaboration: Collaboration, documentId: String, share: Command) {
    val peers by collaboration.peers.collectAsState()
    val others = peers.filter { it.documentId == documentId }.distinctBy { it.replica }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (others.isNotEmpty() && !currentAdaptiveInfo().window.isCompact) {
            val names = others.joinToString { it.name }
            Row(
                horizontalArrangement = Arrangement.spacedBy((-8).dp),
                modifier = Modifier
                    .padding(start = 4.dp, end = 8.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = stringResource(Res.string.sync_share), onClick = share.perform)
                    .semantics(mergeDescendants = true) { contentDescription = names }
            ) {
                for (peer in others.take(MAX_AVATARS)) MemberAvatar(peer.name, colorOf(peer.replica), ring = MaterialTheme.colorScheme.surface)
            }
        }
        Box {
            EditorActionButton(DrawViewIcons.UserPlus, stringResource(Res.string.sync_share), onClick = share.perform)
            if (others.isNotEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Text(others.size.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
                }
            }
        }
    }
}

/** The avatars beside the share button at most. */
private const val MAX_AVATARS = 3

/**
 * Shares the document, or shows its sharing (see [ShareContent]): gathers what it shows from
 * [collaboration] and does there what the user chooses; ending the sharing or leaving the
 * document is asked first. A new role or approval of the invitation gives a new one in its place.
 */
@Composable
private fun ShareDialog(collaboration: Collaboration, documentId: String, title: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val memberships by collaboration.memberships.collectAsState()
    val membership = memberships[documentId]
    val isOwner = membership?.owner == collaboration.device
    val online by collaboration.isOnline.collectAsState()
    val otherVersion by collaboration.otherVersionMet.collectAsState()
    val requests by collaboration.joinRequests.collectAsState()
    val deviceName by DeviceProfile.name.collectAsState()
    var name by remember { mutableStateOf(DeviceProfile.chosenName.value.orEmpty()) }
    var invitations by remember { mutableStateOf<List<Pair<InvitationGrant, Invitation>>>(emptyList()) }
    var version by remember { mutableStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }
    LaunchedEffect(documentId, version, isOwner) {
        invitations = if (isOwner) collaboration.invitationsOf(documentId, title) else emptyList()
    }
    val current = invitations.lastOrNull()

    val state = SharingState(
        shared = membership != null,
        isOwner = isOwner,
        owner = membership?.memberOf(membership.owner)?.name.orEmpty(),
        invitation = current?.let { (grant, invitation) -> SharingInvitation(invitation.toLink(), grant.role, grant.approval) },
        requests = requests.filter { it.documentId == documentId }.map { SharingPerson(it.device, it.name, it.role, colorOf(it.device)) },
        members = membership?.members.orEmpty().map { member ->
            val me = member.device == collaboration.device
            SharingPerson(member.device, if (me) deviceName else member.name, member.role, colorOf(member.replica), me = me)
        },
        offline = !online,
        otherVersion = otherVersion
    )
    val actions = remember(collaboration, documentId, title) {
        object : SharingActions {
            override fun invite(role: Role, approval: Boolean) {
                scope.launch {
                    // The invitation with other options takes the place of the current one
                    invitations.lastOrNull()?.let { (grant, _) -> collaboration.revokeInvitation(documentId, grant) }
                    collaboration.share(documentId, title, role, approval)
                    version++
                }
            }

            override fun revoke() {
                val (grant, _) = invitations.lastOrNull() ?: return
                scope.launch {
                    collaboration.revokeInvitation(documentId, grant)
                    version++
                }
            }

            override fun answer(device: String, approve: Boolean) {
                collaboration.joinRequests.value.firstOrNull { it.documentId == documentId && it.device == device }
                    ?.let { collaboration.answer(it, approve) }
            }

            override fun setRole(device: String, role: Role) {
                scope.launch { collaboration.setRole(documentId, device, role) }
            }

            override fun remove(device: String) {
                scope.launch { collaboration.removeMember(documentId, device) }
            }

            override fun stop() {
                confirmStop = true
            }

            override fun close() = onDismiss()
        }
    }

    AdaptiveSheet(onDismissRequest = onDismiss, maxWidth = if (state.invites) ShareDialogWidth else ShareDialogNarrowWidth) {
        ShareContent(
            state = state,
            actions = actions,
            name = name,
            deviceName = deviceName,
            onRename = {
                name = it.take(DeviceProfile.MAX_LENGTH)
                scope.launch { collaboration.rename(name) }
            },
            compact = currentAdaptiveInfo().window.isCompact
        )
    }

    if (confirmStop) {
        StopSharingDialog(
            isOwner = isOwner,
            onConfirm = {
                confirmStop = false
                scope.launch { collaboration.stopSharing(documentId) }
            },
            onDismiss = { confirmStop = false }
        )
    }
}

/** Ending the sharing (the owner) or leaving the document (the others), asked first: each keeps a copy. */
@Composable
private fun StopSharingDialog(isOwner: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val label = stringResource(if (isOwner) Res.string.sync_end_sharing else Res.string.sync_leave)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(DrawViewIcons.LogOut, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(label, textAlign = TextAlign.Center) },
        text = {
            Text(
                stringResource(if (isOwner) Res.string.sync_end_confirm else Res.string.sync_leave_confirm),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
            ) { Text(label) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_cancel)) } }
    )
}
