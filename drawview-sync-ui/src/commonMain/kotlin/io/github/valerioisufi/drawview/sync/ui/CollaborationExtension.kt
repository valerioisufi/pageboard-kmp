package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.github.valerioisufi.drawview.core.controller.Collaborator
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.platform.DeviceProfile
import io.github.valerioisufi.drawview.sync.Invitation
import io.github.valerioisufi.drawview.sync.InvitationGrant
import io.github.valerioisufi.drawview.sync.Membership
import io.github.valerioisufi.drawview.sync.Role
import io.github.valerioisufi.drawview.sync.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.components.ToolButton
import io.github.valerioisufi.drawview.ui.session.EditorExtension
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The collaboration in the editor: the action sharing the document (with the number of the other
 * devices on it; also in the menus, "Share…"), and over the canvas where the others are and what
 * they are drawing (who is writing a text the user tried to edit too) and the sharing dialog.
 */
fun Collaboration.editorExtension(): EditorExtension {
    // The document whose sharing is shown, asked by the button or by the command
    val sharing = SharingShown()
    fun shareCommand(documentId: String) = Command(
        id = "share",
        label = { stringResource(Res.string.sync_share) },
        group = CommandGroup.FILE,
        section = 1,
        icon = { rememberVectorPainter(Icons.Outlined.PersonAdd) }
    ) { sharing.documentId = documentId }
    return EditorExtension(
        toolbarActions = { session, _ ->
            val command = remember(session) { shareCommand(session.documentId) }
            ShareAction(this@editorExtension, session.documentId, command)
        },
        canvasOverlay = { session, controller ->
            CollaboratorsLayer(this@editorExtension, session.documentId, controller)
            WritingElsewhereNotice(controller)
            if (sharing.documentId == session.documentId) {
                ShareDialog(this@editorExtension, session.documentId, controller.document.title, onDismiss = { sharing.documentId = null })
            }
        },
        commands = { session, _ -> listOf(shareCommand(session.documentId)) }
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
 * [DrawViewController.editsRefused]), for a moment at the top of the canvas.
 */
@Composable
private fun BoxScope.WritingElsewhereNotice(controller: DrawViewController) {
    var writer by remember(controller) { mutableStateOf<Collaborator?>(null) }
    LaunchedEffect(controller) {
        controller.editsRefused.collectLatest {
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
            .padding(top = 16.dp)
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(Modifier.size(10.dp)) { drawCircle(shown.color) }
        Text(
            text = stringResource(Res.string.sync_writing_elsewhere, shown.name),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** The button sharing the document ([share]), with the number of the other devices on it. */
@Composable
private fun ShareAction(collaboration: Collaboration, documentId: String, share: Command) {
    val peers by collaboration.peers.collectAsState()
    val others = peers.filter { it.documentId == documentId }.distinctBy { it.replica }
    Box {
        ToolButton(onClick = share.perform, command = share) {
            Icon(imageVector = Icons.Outlined.PersonAdd, contentDescription = stringResource(Res.string.sync_share))
        }
        if (others.isNotEmpty()) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-2).dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Text(others.size.toString(), maxLines = 1)
            }
        }
    }
}

/**
 * Shares the document, or shows its sharing: the name of this device, the invitation (its QR code
 * and its link, given by the owner with the role of the devices joining and whether it approves
 * them), the devices waiting for an approval, the members with their roles (changed or removed by
 * the owner), and how to stop sharing (for everyone, the owner; leaving it, the others).
 */
@Composable
private fun ShareDialog(collaboration: Collaboration, documentId: String, title: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val memberships by collaboration.memberships.collectAsState()
    val membership = memberships[documentId]
    val isOwner = membership?.owner == collaboration.device
    val online by collaboration.isOnline.collectAsState()
    val otherVersion by collaboration.otherVersionMet.collectAsState()
    var role by remember { mutableStateOf(Role.EDITOR) }
    var approval by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Group, contentDescription = null) },
        title = { Text(stringResource(Res.string.sync_share_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                NameField(collaboration)
                when {
                    membership == null -> {
                        Text(stringResource(Res.string.sync_share_explanation))
                        InvitationOptions(role, approval, onRole = { role = it }, onApproval = { approval = it })
                    }
                    isOwner -> InvitationSection(collaboration, documentId, title)
                    else -> {
                        val owner = membership.memberOf(membership.owner)?.name.orEmpty()
                        Text(stringResource(Res.string.sync_invite_owner_only, owner), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (membership != null) {
                    if (isOwner) JoinRequestsSection(collaboration, documentId)
                    MembersSection(collaboration, documentId, membership, isOwner)
                }
                if (!online && membership != null) {
                    Text(stringResource(Res.string.sync_no_network), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (otherVersion) {
                    Text(stringResource(Res.string.sync_other_version), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (membership == null) {
                TextButton(onClick = { scope.launch { collaboration.share(documentId, title, role, approval) } }) {
                    Text(stringResource(Res.string.sync_share))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_close)) }
            }
        },
        dismissButton = {
            if (membership == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_cancel)) }
            } else {
                TextButton(onClick = { confirmStop = true }) {
                    Text(
                        stringResource(if (isOwner) Res.string.sync_end_sharing else Res.string.sync_leave),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(if (isOwner) Res.string.sync_end_sharing else Res.string.sync_leave)) },
            text = { Text(stringResource(if (isOwner) Res.string.sync_end_confirm else Res.string.sync_leave_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    scope.launch { collaboration.stopSharing(documentId) }
                }) {
                    Text(stringResource(if (isOwner) Res.string.sync_end_sharing else Res.string.sync_leave), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(Res.string.sync_cancel)) } }
        )
    }
}

/** The name this device is shown with to the others. */
@Composable
private fun NameField(collaboration: Collaboration) {
    val scope = rememberCoroutineScope()
    val name by DeviceProfile.name.collectAsState()
    var text by remember { mutableStateOf(DeviceProfile.chosenName.value.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.take(DeviceProfile.MAX_LENGTH)
            scope.launch { collaboration.rename(text) }
        },
        label = { Text(stringResource(Res.string.sync_your_name)) },
        placeholder = { Text(name) },
        singleLine = true,
        supportingText = { Text(stringResource(Res.string.sync_your_name_hint)) },
        modifier = Modifier.fillMaxWidth()
    )
}

/** The role of the devices joining with an invitation, and whether the owner approves them first. */
@Composable
private fun InvitationOptions(role: Role, approval: Boolean, onRole: (Role) -> Unit, onApproval: (Boolean) -> Unit) {
    Column {
        for (option in listOf(Role.EDITOR, Role.READER)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onRole(option) }
            ) {
                RadioButton(selected = role == option, onClick = { onRole(option) })
                Text(stringResource(roleLabel(option)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onApproval(!approval) }
        ) {
            Checkbox(checked = approval, onCheckedChange = onApproval)
            Text(stringResource(Res.string.sync_approval), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The last invitation of the document (its QR code and its link), and a new one with other options. */
@Composable
private fun InvitationSection(collaboration: Collaboration, documentId: String, title: String) {
    val scope = rememberCoroutineScope()
    var invitations by remember { mutableStateOf<List<Pair<InvitationGrant, Invitation>>>(emptyList()) }
    var version by remember { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    var newOne by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf(Role.EDITOR) }
    var approval by remember { mutableStateOf(false) }
    LaunchedEffect(documentId, version) { invitations = collaboration.invitationsOf(documentId, title) }
    // The clipboard of every platform (the Clipboard API has no common plain text entry)
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val current = invitations.lastOrNull()

    Text(stringResource(Res.string.sync_share_link), style = MaterialTheme.typography.labelLarge)
    if (current != null && !newOne) {
        val (grant, invitation) = current
        val link = invitation.toLink()
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Dark on white, as the cameras read it best
            Image(
                painter = rememberQrCodePainter(link),
                contentDescription = stringResource(Res.string.sync_qr_code),
                modifier = Modifier.size(180.dp).background(Color.White).padding(8.dp)
            )
        }
        Text(stringResource(Res.string.sync_scan_hint), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(Res.string.sync_invitation_role, stringResource(roleLabel(grant.role))) +
                if (grant.approval) " · " + stringResource(Res.string.sync_approval) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(link))
                copied = true
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(if (copied) Res.string.sync_link_copied else Res.string.sync_copy_link),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            TextButton(onClick = {
                scope.launch {
                    collaboration.revokeInvitation(documentId, grant)
                    version++
                }
            }) { Text(stringResource(Res.string.sync_revoke_invitation)) }
        }
        TextButton(onClick = { newOne = true }) { Text(stringResource(Res.string.sync_new_invitation)) }
    } else {
        InvitationOptions(role, approval, onRole = { role = it }, onApproval = { approval = it })
        TextButton(onClick = {
            scope.launch {
                collaboration.share(documentId, title, role, approval)
                newOne = false
                copied = false
                version++
            }
        }) { Text(stringResource(Res.string.sync_new_invitation)) }
    }
}

/** The devices waiting for this device, the owner, to let them in. */
@Composable
private fun JoinRequestsSection(collaboration: Collaboration, documentId: String) {
    val requests by collaboration.joinRequests.collectAsState()
    for (request in requests.filter { it.documentId == documentId }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.sync_request_short, request.name, stringResource(roleLabel(request.role))),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { collaboration.answer(request, approve = false) }) { Text(stringResource(Res.string.sync_refuse)) }
            TextButton(onClick = { collaboration.answer(request, approve = true) }) { Text(stringResource(Res.string.sync_approve)) }
        }
    }
}

/** The members of the document, connected or not, with their roles (the owner changes them, or removes a member). */
@Composable
private fun MembersSection(collaboration: Collaboration, documentId: String, membership: Membership, isOwner: Boolean) {
    val scope = rememberCoroutineScope()
    val peers by collaboration.peers.collectAsState()
    val connected = peers.filter { it.documentId == documentId }.mapTo(mutableSetOf()) { it.device }
    Text(stringResource(Res.string.sync_participants), style = MaterialTheme.typography.labelLarge)
    if (membership.members.size <= 1) Text(stringResource(Res.string.sync_nobody), style = MaterialTheme.typography.bodyMedium)
    for (member in membership.members) {
        val me = member.device == collaboration.device
        var menu by remember(member.device) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Filled when connected now
            val color = colorOf(member.replica)
            Canvas(Modifier.size(12.dp)) {
                if (me || member.device in connected) drawCircle(color) else drawCircle(color, style = Stroke(width = 2.dp.toPx()))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (me) stringResource(Res.string.sync_you, DeviceProfile.name.value) else member.name,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(stringResource(roleLabel(member.role)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isOwner && !me) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val other = if (member.role == Role.READER) Role.EDITOR else Role.READER
                        DropdownMenuItem(
                            text = { Text(stringResource(if (other == Role.READER) Res.string.sync_make_reader else Res.string.sync_make_editor)) },
                            onClick = {
                                menu = false
                                scope.launch { collaboration.setRole(documentId, member.device, other) }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.sync_remove), color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                scope.launch { collaboration.removeMember(documentId, member.device) }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun roleLabel(role: Role): StringResource = when (role) {
    Role.OWNER -> Res.string.sync_role_owner
    Role.EDITOR -> Res.string.sync_role_editor
    Role.READER -> Res.string.sync_role_reader
}

private val palette = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF6D4C41)
)

/** The color of the device [replica], the same on every device. */
internal fun colorOf(replica: String): Color = palette[abs(replica.fold(0) { hash, char -> 31 * hash + char.code } % palette.size)]
