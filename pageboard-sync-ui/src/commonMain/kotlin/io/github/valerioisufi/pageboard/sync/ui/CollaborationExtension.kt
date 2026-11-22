package io.github.valerioisufi.pageboard.sync.ui

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
import io.github.valerioisufi.pageboard.canvas.controller.Collaborator
import io.github.valerioisufi.pageboard.canvas.controller.CollaboratorPresence
import io.github.valerioisufi.pageboard.platform.DeviceProfile
import io.github.valerioisufi.pageboard.sync.InvitationGrant
import io.github.valerioisufi.pageboard.sync.lan.LanCheck
import io.github.valerioisufi.pageboard.sync.protocol.Invitation
import io.github.valerioisufi.pageboard.sync.protocol.Role
import io.github.valerioisufi.pageboard.sync.protocol.Route
import io.github.valerioisufi.pageboard.sync.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.pageboard.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.command.CommandGroup
import io.github.valerioisufi.pageboard.ui.extension.EditorExtension
import io.github.valerioisufi.pageboard.ui.screen.EditorActionButton
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
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
        icon = { rememberVectorPainter(PageboardIcons.UserPlus) }
    ) { sharing.documentId = documentId }
    return EditorExtension {
        toolbarActions { context ->
            val command = remember(context) { shareCommand(context.documentId) }
            ShareAction(this@editorExtension, context.documentId, command)
        }
        canvasOverlay { context ->
            CollaboratorsLayer(this@editorExtension, context.documentId, context.document, context.presence)
            WritingElsewhereNotice(context.presence)
            if (sharing.documentId == context.documentId) {
                ShareDialog(this@editorExtension, ShareTarget.Document(context.documentId, context.document.document.title), onDismiss = { sharing.documentId = null })
            }
        }
        commands { context -> listOf(shareCommand(context.documentId)) }
        readOnlyNote { context ->
            // The owner decides who edits a shared document
            val memberships by memberships.collectAsState()
            val membership = memberships[context.documentId]
            val owner = membership?.takeIf { it.owner != device }?.let { it.memberOf(it.owner)?.name }
            owner?.let { stringResource(Res.string.sync_read_only_note, it) }
        }
    }
}

/** The document whose sharing dialog the editor shows, `null` when none. */
private class SharingShown {
    var documentId by mutableStateOf<String?>(null)
}

/** How long the user is told who is writing the text they tried to edit. */
private const val WRITING_ELSEWHERE_NOTICE_MS = 2_500L

/**
 * Who is writing the text the user tried to edit or to select (left to them, see
 * [io.github.valerioisufi.pageboard.canvas.controller.CollaboratorPresence.editsRefused]), for a moment at the top of the canvas.
 */
@Composable
private fun BoxScope.WritingElsewhereNotice(presence: CollaboratorPresence) {
    var writer by remember(presence) { mutableStateOf<Collaborator?>(null) }
    LaunchedEffect(presence) {
        presence.editsRefused.collectLatest {
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
        Icon(PageboardIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(16.dp))
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
            EditorActionButton(PageboardIcons.UserPlus, stringResource(Res.string.sync_share), onClick = share.perform)
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

/** How often the sharing dialog looks again at what each connection goes through. */
private const val ROUTES_MS = 1_000L

/**
 * Shares the document, or shows its sharing (see [ShareContent]): gathers what it shows from
 * [collaboration] and does there what the user chooses; ending the sharing or leaving the
 * document is asked first. A new role or approval of the invitation gives a new one in its place.
 */
@Composable
internal fun ShareDialog(collaboration: Collaboration, target: ShareTarget, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val title = target.title
    // A folder has its identifier of shared folder once shared (see Collaboration.shareFolder)
    var shareId by remember(target) { mutableStateOf((target as? ShareTarget.Document)?.documentId) }
    val folder = target as? ShareTarget.Folder
    LaunchedEffect(target) { if (folder != null) shareId = collaboration.spaceOf(folder.folderId) }
    val documentId = shareId.orEmpty()
    val memberships by (if (folder != null) collaboration.spaceMemberships else collaboration.memberships).collectAsState()
    val membership = shareId?.let { memberships[it] }
    val isOwner = membership?.owner == collaboration.device
    val online by collaboration.isOnline.collectAsState()
    val otherVersion by collaboration.otherVersionMet.collectAsState()
    val problems by collaboration.problems.collectAsState(emptyList())
    // The check of the connection: running (no report yet) or done, and whether the network could be used
    var check by remember { mutableStateOf<Pair<LanCheck?, Boolean>?>(null) }
    val requests by collaboration.joinRequests.collectAsState()
    val deviceName by DeviceProfile.name.collectAsState()
    var name by remember { mutableStateOf(DeviceProfile.chosenName.value.orEmpty()) }
    var invitations by remember { mutableStateOf<List<Pair<InvitationGrant, Invitation>>>(emptyList()) }
    var version by remember { mutableStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }
    // Sharing the folder refused (it is in a shared folder or holds one): told in the dialog
    var refused by remember(target) { mutableStateOf(false) }
    val internet by collaboration.internetSettings.collectAsState()
    var routes by remember { mutableStateOf<Map<String, Route>>(emptyMap()) }
    LaunchedEffect(documentId, version, isOwner) {
        invitations = if (isOwner && documentId.isNotEmpty()) collaboration.invitationsOf(documentId, title) else emptyList()
    }
    // What each connection goes through (a relay, then a direct path...): looked at again and again
    LaunchedEffect(documentId, membership) {
        while (true) {
            routes = membership?.members.orEmpty().mapNotNull { member ->
                collaboration.routeOf(documentId, member.device)?.let { member.device to it }
            }.toMap()
            delay(ROUTES_MS)
        }
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
            SharingPerson(member.device, if (me) deviceName else member.name, member.role, colorOf(member.replica), me = me, route = routes[member.device])
        },
        offline = !online,
        otherVersion = otherVersion,
        internet = internet.takeIf { collaboration.isInternetAvailable },
        kind = target.kind,
        refused = refused,
        // Before sharing only what stops everything
        problems = if (membership != null) problems else problems.filter { it.blocksEverything }
    )
    val actions = remember(collaboration, documentId, target) {
        object : SharingActions {
            override fun invite(role: Role, approval: Boolean) {
                scope.launch {
                    // The invitation with other options takes the place of the current one
                    invitations.lastOrNull()?.let { (grant, _) -> collaboration.revokeInvitation(documentId, grant) }
                    if (folder != null) {
                        refused = collaboration.shareFolder(folder.folderId, title, role, approval) == null
                        shareId = collaboration.spaceOf(folder.folderId)
                    } else {
                        collaboration.share(documentId, title, role, approval)
                    }
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

            override fun setInternet(settings: InternetSettings) {
                scope.launch { collaboration.setInternet(settings) }
            }

            override fun check() {
                check = null to true
                scope.launch { check = collaboration.check() }
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

    check?.let { (report, started) -> CheckDialog(report, started, onDismiss = { check = null }) }

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
        icon = { Icon(PageboardIcons.LogOut, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
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
