package io.github.valerioisufi.pageboard.sync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.pageboard.sync.RefusedChanges
import io.github.valerioisufi.pageboard.sync.WaitingChanges
import io.github.valerioisufi.pageboard.sync.JoinStatus
import io.github.valerioisufi.pageboard.sync.lan.LanDevice
import io.github.valerioisufi.pageboard.sync.protocol.InvitationKind
import io.github.valerioisufi.pageboard.sync.protocol.Role
import io.github.valerioisufi.pageboard.sync.protocol.Route
import io.github.valerioisufi.pageboard.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.pageboard.ui.adaptive.SheetBody
import io.github.valerioisufi.pageboard.ui.theme.PageboardTheme
import io.github.valerioisufi.pageboard.ui.theme.InkPalette
import io.github.valerioisufi.pageboard.ui.theme.LocalCanvasColors
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the sharing as the dialogs draw it (the dialogs themselves open in windows the
 * screenshots do not take): the owner, a member, before sharing, joining (docs/plans/ui-design.md), over
 * the internet (docs/plans/internet-sync.md).
 *
 * `./gradlew :pageboard-sync-ui:recordRoborazziJvm` records them in `screenshots/`.
 */
@OptIn(ExperimentalTestApi::class)
class SharingScreenshotTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    private fun capture(name: String, width: Int, height: Int, dark: Boolean = false, content: @Composable () -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                PageboardTheme(darkTheme = dark, dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk).padding(24.dp)) { content() }
                }
            }
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/$name.png")
        }

    private val noActions = object : SharingActions {
        override fun invite(role: Role, approval: Boolean) = Unit
        override fun revoke() = Unit
        override fun answer(device: String, approve: Boolean) = Unit
        override fun setRole(device: String, role: Role) = Unit
        override fun remove(device: String) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    private val members = listOf(
        SharingPerson("mac", "Valerio’s Mac", Role.OWNER, InkPalette.Green),
        SharingPerson("ipad", "Marta’s iPad", Role.EDITOR, InkPalette.Violet),
        SharingPerson("phone", "Luca’s phone", Role.READER, InkPalette.Blue)
    )

    /** The owner: the invitation beside who asks to join and who is on the document, light and dark. */
    @Test
    fun owner() = listOf(false, true).forEach { dark ->
        capture("share-owner-${if (dark) "dark" else "light"}", width = 908, height = 760, dark = dark) {
            Dialog(ShareDialogWidth) {
                ShareContent(
                    state = SharingState(
                        shared = true,
                        isOwner = true,
                        owner = "Valerio’s Mac",
                        invitation = SharingInvitation("pageboard://join?d=7F3K-Q9PL-2MXA&k=c2VjcmV0&o=bWFj", Role.EDITOR, approval = true),
                        requests = listOf(SharingPerson("lab", "Lab PC", Role.READER, InkPalette.Orange)),
                        members = members.mapIndexed { index, member -> if (index == 0) member.copy(me = true) else member },
                        offline = false,
                        otherVersion = false
                    ),
                    actions = noActions,
                    name = "Valerio’s Mac",
                    deviceName = "Valerio’s Mac",
                    onRename = {},
                    compact = false
                )
            }
        }
    }

    /**
     * A member over the internet: how each one is reached (the local network, the internet directly
     * or through the relay), the relay of this device being changed.
     */
    @Test
    fun internet() = capture("share-internet", width = 608, height = 900) {
        Dialog(ShareDialogNarrowWidth) {
            ShareContent(
                state = SharingState(
                    shared = true,
                    isOwner = false,
                    owner = "Valerio’s Mac",
                    invitation = null,
                    requests = emptyList(),
                    members = listOf(
                        members[0].copy(route = Route.INTERNET_DIRECT),
                        members[1].copy(me = true),
                        members[2].copy(route = Route.INTERNET_RELAYED)
                    ),
                    offline = false,
                    otherVersion = false,
                    internet = InternetSettings(enabled = true, relay = "relay.example.com", token = "secret")
                ),
                actions = noActions,
                name = "Marta’s iPad",
                deviceName = "Marta’s iPad",
                onRename = {},
                compact = false
            )
        }
    }

    /** The library about to be shared (docs/plans/shared-folders.md): what sharing it does, the first invitation. */
    @Test
    fun library() = capture("share-library", width = 908, height = 640) {
        Dialog(ShareDialogWidth) {
            ShareContent(
                state = SharingState(
                    shared = false,
                    isOwner = true,
                    owner = "",
                    invitation = null,
                    requests = emptyList(),
                    members = emptyList(),
                    offline = false,
                    otherVersion = false,
                    kind = InvitationKind.LIBRARY
                ),
                actions = noActions,
                name = "Valerio’s Mac",
                deviceName = "Valerio’s Mac",
                onRename = {},
                compact = false
            )
        }
    }

    /**
     * A note shared on its own and in a shared folder (docs/plans/shared-access.md): its own people,
     * one of them also through the folder, and the people of the folder, read only here, with the
     * folder's own dialog to open; changes of a device not known here yet waiting.
     */
    @Test
    fun twoSources() = capture("share-two-sources", width = 608, height = 900) {
        Dialog(ShareDialogNarrowWidth) {
            ShareContent(
                state = SharingState(
                    shared = true,
                    isOwner = false,
                    owner = "Valerio’s Mac",
                    invitation = null,
                    requests = emptyList(),
                    members = listOf(members[0], members[1].copy(me = true, alsoThrough = listOf("Physics"))),
                    offline = false,
                    otherVersion = false,
                    sources = listOf(SharingSource("Physics", listOf(members[0], members[1].copy(me = true), members[2]), target = ShareTarget.Folder("physics", "Physics"))),
                    waiting = listOf(SharingWaiting(WaitingChanges.Reason.UNKNOWN_DEVICE))
                ),
                actions = noActions,
                name = "Marta’s iPad",
                deviceName = "Marta’s iPad",
                onRename = {},
                compact = false
            )
        }
    }

    /**
     * The owner of a shared folder inside the shared library: the people through the library, and
     * who besides the owner can share what is inside it.
     */
    @Test
    fun folderInside() = capture("share-folder-inside", width = 908, height = 900) {
        Dialog(ShareDialogWidth) {
            ShareContent(
                state = SharingState(
                    shared = true,
                    isOwner = true,
                    owner = "Valerio’s Mac",
                    invitation = SharingInvitation("pageboard://join?s=7F3K-Q9PL-2MXA&n=folder&k=c2VjcmV0&o=bWFj", Role.EDITOR, approval = false),
                    requests = emptyList(),
                    members = listOf(members[0].copy(me = true), members[1]),
                    offline = false,
                    otherVersion = false,
                    kind = InvitationKind.FOLDER,
                    sources = listOf(SharingSource(null, listOf(members[0].copy(me = true), SharingPerson("phone2", "Valerio’s phone", Role.EDITOR, InkPalette.Orange)), target = ShareTarget.Folder(null, "Library"))),
                    editorsShareInside = false
                ),
                actions = noActions,
                name = "Valerio’s Mac",
                deviceName = "Valerio’s Mac",
                onRename = {},
                compact = false
            )
        }
    }

    /** Sharing a folder inside a shared folder whose owner does not allow it: who decides, told. */
    @Test
    fun insideRefused() = capture("share-inside-refused", width = 908, height = 640) {
        Dialog(ShareDialogWidth) {
            ShareContent(
                state = SharingState(
                    shared = false,
                    isOwner = true,
                    owner = "",
                    invitation = null,
                    requests = emptyList(),
                    members = emptyList(),
                    offline = false,
                    otherVersion = false,
                    kind = InvitationKind.FOLDER,
                    refused = true,
                    refusedBy = "Physics",
                    refusedByOwner = true
                ),
                actions = noActions,
                name = "Marta’s iPad",
                deviceName = "Marta’s iPad",
                onRename = {},
                compact = false
            )
        }
    }

    /** Joining a shared library: into a new folder, or into this library. */
    @Test
    fun joinLibrary() = capture("join-library", width = 520, height = 420) {
        Dialog(ShareDialogNarrowWidth) {
            Box(modifier = Modifier.padding(24.dp)) { LibraryRootChoice("Valerio’s notes", intoLibrary = true) {} }
        }
    }

    /**
     * What happened to the changes of the others, told (docs/plans/shared-access.md, phase 11): the
     * ones of a reader refused, and a folder this device only reads not getting what changed here.
     */
    @Test
    fun changesTold() = capture("share-changes-told", width = 608, height = 760) {
        Dialog(ShareDialogNarrowWidth) {
            ShareContent(
                state = SharingState(
                    shared = true,
                    isOwner = true,
                    owner = "Valerio’s Mac",
                    invitation = null,
                    requests = emptyList(),
                    members = listOf(members[0].copy(me = true), members[2]),
                    offline = false,
                    otherVersion = false,
                    kind = InvitationKind.FOLDER,
                    refusals = listOf(SharingRefusal(RefusedChanges.Reason.NOT_ADMITTED, "Luca’s phone")),
                    notPassedOn = listOf("Physics")
                ),
                actions = noActions,
                name = "Valerio’s Mac",
                deviceName = "Valerio’s Mac",
                onRename = {},
                compact = false
            )
        }
    }

    /** A member on a phone (a sheet): only the owner invites, another version met. */
    @Test
    fun participant() = capture("share-participant-phone", width = 411, height = 760) {
        Sheet {
            ShareContent(
                state = SharingState(
                    shared = true,
                    isOwner = false,
                    owner = "Valerio’s Mac",
                    invitation = null,
                    requests = emptyList(),
                    members = members.mapIndexed { index, member -> if (index == 1) member.copy(me = true) else member },
                    offline = false,
                    otherVersion = true
                ),
                actions = noActions,
                name = "Marta’s iPad",
                deviceName = "Marta’s iPad",
                onRename = {},
                compact = true
            )
        }
    }

    /** Before sharing: the options of the first invitation, the name of this device. */
    @Test
    fun notShared() = capture("share-not-shared", width = 908, height = 560) {
        Dialog(ShareDialogWidth) {
            ShareContent(
                state = SharingState(
                    shared = false,
                    isOwner = false,
                    owner = "",
                    invitation = null,
                    requests = emptyList(),
                    members = emptyList(),
                    offline = false,
                    otherVersion = false
                ),
                actions = noActions,
                name = "",
                deviceName = "Valerio’s Mac",
                onRename = {},
                compact = false
            )
        }
    }

    /** Joining: looking for the owner, waiting for it, in, refused, without the local network; the avatars of the top bar. */
    @Test
    fun joining() = capture("share-joining", width = 900, height = 520) {
        val title = "Lecture 1 — Limits"
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                JoinCard { JoinProgress(JoinStatus.WAITING_APPROVAL, title, online = true) }
                JoinCard { JoinProgress(JoinStatus.ADMITTED, title, online = true) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                JoinCard { JoinProgress(JoinStatus.REFUSED, title, online = true) }
                JoinCard { JoinProgress(JoinStatus.LOOKING_FOR_OWNER, title, online = false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                MemberAvatar("Marta’s iPad", InkPalette.Violet, ring = MaterialTheme.colorScheme.surface)
                MemberAvatar("Luca’s phone", InkPalette.Blue, ring = MaterialTheme.colorScheme.surface)
            }
        }
    }

    /**
     * Why the devices do not work together (docs/plans/sync-diagnostics.md): a member whose owner is
     * found where it is no more, the search failed, and the check of the connection.
     */
    @Test
    fun problems() = capture("share-problems", width = 608, height = 760) {
        val mac = LanDevice("mac", "Valerio’s Mac", "192.168.0.248:28461", shared = true, otherVersion = false, connected = false, failures = 4, failure = "timeout")
        Dialog(ShareDialogNarrowWidth) {
            ShareContent(
                state = SharingState(
                    shared = true,
                    isOwner = false,
                    owner = "Valerio’s Mac",
                    invitation = null,
                    requests = emptyList(),
                    members = listOf(members[0], members[1].copy(me = true)),
                    offline = false,
                    otherVersion = false,
                    problems = listOf(SharingProblem.DiscoveryFailed("NSNetServicesTimeoutError, -72007"), SharingProblem.NotAnswering(listOf(mac)))
                ),
                actions = noActions,
                name = "Marta’s iPad",
                deviceName = "Marta’s iPad",
                onRename = {},
                compact = false
            )
        }
    }

    /** Joining while the owner is looked for: nobody found after a while (with the check), the local network refused. */
    @Test
    fun joiningProblems() = capture("share-joining-problems", width = 900, height = 520) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            JoinCard {
                JoinProgress(JoinStatus.LOOKING_FOR_OWNER, "Library", online = true, InvitationKind.LIBRARY, listOf(SharingProblem.NobodyFound(internetOff = true)), onCheck = {})
            }
            JoinCard {
                JoinProgress(JoinStatus.LOOKING_FOR_OWNER, "Library", online = true, InvitationKind.LIBRARY, listOf(SharingProblem.PermissionDenied))
            }
        }
    }

    /** The dialog of a wide window, as the application opens it, [width] at most. */
    @Composable
    private fun Dialog(width: Dp, content: @Composable () -> Unit) {
        AdaptiveSheet(body = SheetBody.Fixed, onDismissRequest = {}, maxWidth = width) { content() }
    }

    /** The sheet of a phone: the low container, its top rounded 28. */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(top = 24.dp)
        ) { content() }
    }

    /** The joining dialog with its title. */
    @Composable
    private fun JoinCard(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Join a shared document", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}
