package io.github.valerioisufi.drawview.sync.ui

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
import io.github.valerioisufi.drawview.sync.JoinStatus
import io.github.valerioisufi.drawview.sync.protocol.Role
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import io.github.valerioisufi.drawview.ui.theme.InkPalette
import io.github.valerioisufi.drawview.ui.theme.LocalCanvasColors
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the sharing as the dialogs draw it (the dialogs themselves open in windows the
 * screenshots do not take): the owner, a member, before sharing, joining (docs/ui-design.md).
 *
 * `./gradlew :drawview-sync-ui:recordRoborazziJvm` records them in `screenshots/`.
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
                DrawViewTheme(darkTheme = dark, dynamicColor = false) {
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
                        invitation = SharingInvitation("drawview://join?d=7F3K-Q9PL-2MXA&k=c2VjcmV0&o=bWFj", Role.EDITOR, approval = true),
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

    /** The dialog of a wide window: the high container rounded 28. */
    @Composable
    private fun Dialog(width: Dp, content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .width(width)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                .padding(vertical = 24.dp)
        ) { content() }
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
