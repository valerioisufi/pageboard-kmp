package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.github.valerioisufi.drawview.sync.protocol.InvitationKind
import io.github.valerioisufi.drawview.sync.protocol.Role
import io.github.valerioisufi.drawview.sync.protocol.Route
import io.github.valerioisufi.drawview.sync.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.OutlineButton
import io.github.valerioisufi.drawview.ui.components.SegmentedChoice
import io.github.valerioisufi.drawview.ui.components.TonalButton
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.InkPalette
import kotlin.math.abs
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A device of a shared document as the sharing shows it: a member, or one asking to join with
 * [role]; [route]: what the connection with it goes through, `null` when not connected.
 */
internal data class SharingPerson(
    val device: String,
    val name: String,
    val role: Role,
    val color: Color,
    val me: Boolean = false,
    val route: Route? = null
)

/** The invitation of a document: its [link] (and its QR code), the [role] of who joins with it, whether the owner approves them first. */
internal data class SharingInvitation(val link: String, val role: Role, val approval: Boolean)

/**
 * What the sharing dialog shows of a document (gathered from the collaboration by the dialog).
 *
 * @param shared whether the document is shared; else the options of its first invitation.
 * @param owner the name of its owner.
 * @param invitation the invitation of the owner, `null` when revoked.
 * @param requests the devices waiting for the owner to let them in.
 * @param internet the synchronization over the internet of this device, `null` when the
 *   application does not give it.
 */
internal class SharingState(
    val shared: Boolean,
    val isOwner: Boolean,
    val owner: String,
    val invitation: SharingInvitation?,
    val requests: List<SharingPerson>,
    val members: List<SharingPerson>,
    val offline: Boolean,
    val otherVersion: Boolean,
    val internet: InternetSettings? = null,
    /** What is shared: a document, a folder or the library (see `docs/shared-folders.md`). */
    val kind: InvitationKind = InvitationKind.DOCUMENT,
    /** Whether sharing the folder or the library was refused: it is in a shared folder, or holds one. */
    val refused: Boolean = false
) {
    /** Whether the invitation is shown: to the owner, or before sharing. */
    val invites: Boolean get() = !shared || isOwner
}

/** What the user does in the sharing dialog, done by the dialog with the collaboration. */
internal interface SharingActions {
    /** Shares the document, or gives a new invitation in place of the current one, with [role] and [approval]. */
    fun invite(role: Role, approval: Boolean)

    /** Revokes the invitation: nobody joins with it anymore (the devices joined stay). */
    fun revoke()

    /** Lets in the device [device] asking to join, or refuses it. */
    fun answer(device: String, approve: Boolean)

    fun setRole(device: String, role: Role)

    fun remove(device: String)

    /** Ends the sharing (the owner) or leaves the document (the others), once confirmed. */
    fun stop()

    /** Works with the others over the internet or not, through the relay of [settings]. */
    fun setInternet(settings: InternetSettings) = Unit

    fun close()
}

/** The widest sharing dialog: the invitation beside the people. */
internal val ShareDialogWidth: Dp = 860.dp

/** The sharing dialog without an invitation (a member who is not the owner). */
internal val ShareDialogNarrowWidth: Dp = 560.dp

/**
 * The sharing of a document: its title and what sharing does, the invitation (its QR code, its
 * link, the role of who joins and whether the owner approves them), who asks to join, who is on
 * the document with their roles, the name of this device, how to stop. The invitation beside the
 * people where it is wide, above them else (a phone).
 *
 * @param name the name chosen for this device ([deviceName] when empty).
 */
@Composable
internal fun ShareContent(
    state: SharingState,
    actions: SharingActions,
    name: String,
    deviceName: String,
    onRename: (String) -> Unit,
    compact: Boolean
) {
    // The options of the next invitation (the first one, or one after revoking)
    var role by remember { mutableStateOf(Role.EDITOR) }
    var approval by remember { mutableStateOf(false) }
    BoxWithConstraints {
        val wide = !compact && state.invites && maxWidth >= 720.dp
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = if (compact) 16.dp else 0.dp)) {
            // On a phone what sharing does is said before sharing only
            ShareHeader(compact, explained = !compact || !state.shared, overInternet = state.internet?.enabled == true, kind = state.kind, onClose = actions::close)
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 20.dp)
            ) {
                val invitation: @Composable () -> Unit = {
                    InvitationCard(state, actions, role, approval, onRole = { role = it }, onApproval = { approval = it })
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(modifier = Modifier.width(360.dp)) { invitation() }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Notices(state)
                            People(state, actions, name, deviceName, onRename)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Notices(state)
                        // Who asks to join before the invitation, seen first
                        Requests(state, actions)
                        if (state.invites) invitation()
                        People(state, actions, name, deviceName, onRename, withRequests = false)
                    }
                }
            }
            ShareFooter(state, actions, compact, onShare = { actions.invite(role, approval) })
        }
    }
}

/**
 * "Share the document", what sharing does ([explained]: with the devices of the same network, or
 * wherever they are [overInternet]), the close button of the dialog.
 */
@Composable
private fun ShareHeader(compact: Boolean, explained: Boolean, overInternet: Boolean, kind: InvitationKind, onClose: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    when (kind) {
                        InvitationKind.DOCUMENT -> Res.string.sync_share_title
                        InvitationKind.FOLDER -> Res.string.sync_share_folder_title
                        InvitationKind.LIBRARY -> Res.string.sync_share_library_title
                    }
                ),
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (explained) {
                Text(
                    stringResource(
                        when (kind) {
                            InvitationKind.DOCUMENT -> if (overInternet) Res.string.sync_share_explanation_internet else Res.string.sync_share_explanation
                            InvitationKind.FOLDER -> Res.string.sync_share_folder_explanation
                            InvitationKind.LIBRARY -> Res.string.sync_share_library_explanation
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!compact) DesignIconButton(DrawViewIcons.Close, stringResource(Res.string.sync_close), onClick = onClose)
    }
}

/** Why the devices may not work together, and who invites when it is not this device. */
@Composable
private fun Notices(state: SharingState) {
    if (state.refused) {
        Notice(
            DrawViewIcons.TriangleAlert,
            stringResource(if (state.kind == InvitationKind.LIBRARY) Res.string.sync_share_library_refused else Res.string.sync_share_folder_refused),
            error = true
        )
    }
    if (state.shared && !state.isOwner) {
        Notice(DrawViewIcons.Info, stringResource(Res.string.sync_invite_owner_only, state.owner))
    }
    if (state.shared && state.offline) {
        Notice(DrawViewIcons.WifiOff, stringResource(Res.string.sync_no_network))
    }
    if (state.otherVersion) {
        Notice(DrawViewIcons.TriangleAlert, stringResource(Res.string.sync_other_version), error = true)
    }
}

/** A message in a box: the secondary container, or the one of the errors. */
@Composable
internal fun Notice(icon: ImageVector, text: String, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val container = if (error) colors.errorContainer else colors.secondaryContainer
    val content = if (error) colors.onErrorContainer else colors.onSecondaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
    }
}

/**
 * The invitation on its card: its QR code and its link with their actions (copy, revoke); the
 * role of who joins and whether the owner approves them (changed: a new invitation in its place).
 * Revoked, or before sharing, the options of the next one.
 */
@Composable
private fun InvitationCard(
    state: SharingState,
    actions: SharingActions,
    role: Role,
    approval: Boolean,
    onRole: (Role) -> Unit,
    onApproval: (Boolean) -> Unit
) {
    val invitation = state.invitation
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(Res.string.sync_share_link), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        if (invitation != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Dark on white in both themes, as the cameras read it best
                Image(
                    painter = rememberQrCodePainter(invitation.link),
                    contentDescription = stringResource(Res.string.sync_qr_code),
                    modifier = Modifier
                        .size(168.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(9.dp)
                )
                Text(stringResource(Res.string.sync_scan_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinkField(invitation.link)
        } else if (state.shared) {
            Text(stringResource(Res.string.sync_no_invitation), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.sync_joining_as), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedChoice(
                options = listOf(Role.EDITOR, Role.READER),
                selected = invitation?.role ?: role,
                label = { stringResource(roleLabel(it)) },
                icon = { if (it == Role.READER) DrawViewIcons.Eye else DrawViewIcons.Pencil },
                onSelect = { if (invitation != null) actions.invite(it, invitation.approval) else onRole(it) }
            )
        }
        ApprovalSwitch(
            checked = invitation?.approval ?: approval,
            onCheckedChange = { if (invitation != null) actions.invite(invitation.role, it) else onApproval(it) }
        )

        if (invitation != null) {
            @Suppress("DEPRECATION") // The Clipboard API has no common plain text entry
            val clipboard = LocalClipboardManager.current
            var copied by remember(invitation.link) { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton(onClick = {
                    clipboard.setText(AnnotatedString(invitation.link))
                    copied = true
                }) {
                    Icon(if (copied) DrawViewIcons.Check else DrawViewIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(if (copied) Res.string.sync_link_copied else Res.string.sync_copy_link))
                }
                TextButton(onClick = actions::revoke) { Text(stringResource(Res.string.sync_revoke_invitation)) }
            }
        } else if (state.shared) {
            TonalButton(onClick = { actions.invite(role, approval) }) {
                Icon(DrawViewIcons.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.sync_new_invitation))
            }
        }
    }
}

/** The link of the invitation in its field, copied by the button at its end. */
@Composable
private fun LinkField(link: String) {
    @Suppress("DEPRECATION") // The Clipboard API has no common plain text entry
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(link, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        DesignIconButton(DrawViewIcons.Copy, stringResource(Res.string.sync_copy_link), onClick = { clipboard.setText(AnnotatedString(link)) })
    }
}

/** "Approve who joins" and its switch. */
@Composable
private fun ApprovalSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = SemanticsRole.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(Res.string.sync_approval), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            thumbContent = if (checked) {
                { Icon(DrawViewIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
            } else null
        )
    }
}

/** Who asks to join, who is on the document, the name of this device. */
@Composable
private fun People(
    state: SharingState,
    actions: SharingActions,
    name: String,
    deviceName: String,
    onRename: (String) -> Unit,
    withRequests: Boolean = true
) {
    if (withRequests) Requests(state, actions)
    if (state.shared) {
        Text(stringResource(Res.string.sync_participants), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (member in state.members) MemberRow(member, ownerView = state.isOwner, actions = actions)
        }
        if (state.members.size <= 1) {
            Text(stringResource(Res.string.sync_nobody), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    OutlinedTextField(
        value = name,
        onValueChange = onRename,
        label = { Text(stringResource(Res.string.sync_your_name)) },
        placeholder = { Text(deviceName) },
        singleLine = true,
        supportingText = { Text(stringResource(Res.string.sync_your_name_hint)) },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
    state.internet?.let { settings -> InternetSection(settings, actions::setInternet) }
}

/**
 * Working with the others over the internet: its switch, then the relay (its address and its
 * access token), used once confirmed; without a relay of its own, the public relays of the engine
 * (said under the field).
 */
@Composable
private fun InternetSection(settings: InternetSettings, onChange: (InternetSettings) -> Unit) {
    var relay by remember(settings) { mutableStateOf(settings.relay) }
    var token by remember(settings) { mutableStateOf(settings.token) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .toggleable(value = settings.enabled, role = SemanticsRole.Switch, onValueChange = { onChange(settings.copy(enabled = it)) }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(DrawViewIcons.Globe, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(Res.string.sync_internet), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(Res.string.sync_internet_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = null,
                thumbContent = if (settings.enabled) {
                    { Icon(DrawViewIcons.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else null
            )
        }
        if (!settings.enabled) return@Column
        OutlinedTextField(
            value = relay,
            onValueChange = { relay = it.trim() },
            label = { Text(stringResource(Res.string.sync_relay)) },
            placeholder = { Text("relay.example.com") },
            singleLine = true,
            supportingText = { Text(stringResource(if (relay.isBlank()) Res.string.sync_relay_public else Res.string.sync_relay_hint)) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text(stringResource(Res.string.sync_relay_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        if (relay != settings.relay || token != settings.token) {
            TonalButton(onClick = { onChange(settings.copy(relay = relay, token = token)) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.sync_relay_use))
            }
        }
    }
}

/** The devices asking to join, let in or refused by the owner. */
@Composable
private fun Requests(state: SharingState, actions: SharingActions) {
    for (request in state.requests) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(18.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MemberAvatar(request.name, request.color)
            Text(
                stringResource(Res.string.sync_request_short, request.name, stringResource(roleLabel(request.role))),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { actions.answer(request.device, approve = false) }) { Text(stringResource(Res.string.sync_refuse)) }
            Button(onClick = { actions.answer(request.device, approve = true) }) { Text(stringResource(Res.string.sync_approve)) }
        }
    }
}

/**
 * A member of the document: its avatar, its name and its role; the crown of the owner; seen by the
 * owner, the menu changing the role of the others or removing them.
 */
@Composable
private fun MemberRow(member: SharingPerson, ownerView: Boolean, actions: SharingActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MemberAvatar(member.name, member.color)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (member.me) stringResource(Res.string.sync_you, member.name) else member.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val route = member.route?.let { routeLabel(it) }?.let { stringResource(it) }
            Text(
                listOfNotNull(stringResource(roleLabel(member.role)), route).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            member.role == Role.OWNER -> Icon(
                DrawViewIcons.Crown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(end = 8.dp).size(18.dp)
            )
            ownerView && !member.me -> MemberMenu(member, actions)
        }
    }
}

/** The menu of a member for the owner: reading only or editing, removing it from the document. */
@Composable
private fun MemberMenu(member: SharingPerson, actions: SharingActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DesignIconButton(DrawViewIcons.MoreVertical, member.name, onClick = { expanded = true }, selected = expanded)
        DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val other = if (member.role == Role.READER) Role.EDITOR else Role.READER
            MenuItem(
                text = stringResource(if (other == Role.READER) Res.string.sync_make_reader else Res.string.sync_make_editor),
                icon = rememberVectorPainter(if (other == Role.READER) DrawViewIcons.Eye else DrawViewIcons.Pencil),
                onClick = {
                    expanded = false
                    actions.setRole(member.device, other)
                }
            )
            MenuItem(
                text = stringResource(Res.string.sync_remove),
                icon = rememberVectorPainter(DrawViewIcons.LogOut),
                destructive = true,
                onClick = {
                    expanded = false
                    actions.remove(member.device)
                }
            )
        }
    }
}

/**
 * The bottom of the dialog: before sharing, cancel and share; then stopping (ending the sharing,
 * the owner; leaving the document, the others) and close.
 */
@Composable
private fun ShareFooter(state: SharingState, actions: SharingActions, compact: Boolean, onShare: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
    ) {
        if (!state.shared) {
            TextButton(onClick = actions::close) { Text(stringResource(Res.string.sync_cancel)) }
            Button(onClick = onShare) {
                Icon(DrawViewIcons.UserPlus, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.sync_share))
            }
            return@Row
        }
        val stop = stringResource(if (state.isOwner) Res.string.sync_end_sharing else Res.string.sync_leave)
        if (compact) {
            OutlineButton(onClick = actions::stop, contentColor = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f)) {
                Icon(DrawViewIcons.LogOut, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stop, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            TextButton(onClick = actions::stop) {
                Icon(DrawViewIcons.LogOut, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stop, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Button(onClick = actions::close) { Text(stringResource(Res.string.sync_close)) }
    }
}

/** The avatar of a device: the initials of its [name] on its [color], ringed by [ring] when given (avatars overlapping). */
@Composable
internal fun MemberAvatar(name: String, color: Color, size: Dp = 32.dp, ring: Color? = null) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .padding(if (ring != null) 1.dp else 0.dp)
            .background(color, CircleShape)
    ) {
        Text(initialsOf(name), style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
    }
}

/** "Marta’s iPad": "MI"; the first letters of the first two words. */
internal fun initialsOf(name: String): String =
    name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/** What a connection goes through, as the participants show it; `null` when not known. */
internal fun routeLabel(route: Route): StringResource? = when (route) {
    Route.LOCAL_NETWORK -> Res.string.sync_route_local
    Route.INTERNET_DIRECT -> Res.string.sync_route_direct
    Route.INTERNET_RELAYED -> Res.string.sync_route_relayed
    Route.UNKNOWN -> null
}

internal fun roleLabel(role: Role): StringResource = when (role) {
    Role.OWNER -> Res.string.sync_role_owner
    Role.EDITOR -> Res.string.sync_role_editor
    Role.READER -> Res.string.sync_role_reader
}

/** The colors of the devices: the inks of the design, then a few more. */
private val palette = listOf(
    InkPalette.Green, InkPalette.Violet, InkPalette.Blue, InkPalette.Orange,
    InkPalette.Red, InkPalette.Guide, Color(0xFF00897B), Color(0xFF6D4C41)
)

/** The color of the device [replica], the same on every device. */
internal fun colorOf(replica: String): Color = palette[abs(replica.fold(0) { hash, char -> 31 * hash + char.code } % palette.size)]
