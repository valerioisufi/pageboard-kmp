package io.github.valerioisufi.drawview.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.drawview.agent.AgentConnection
import io.github.valerioisufi.drawview.agent.AgentGrants
import io.github.valerioisufi.drawview.agent.ItemOutcome
import io.github.valerioisufi.drawview.agent.Proposal
import io.github.valerioisufi.drawview.agent.ProposalEntry
import io.github.valerioisufi.drawview.agent.ProposalItem
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import io.github.valerioisufi.drawview.ui.theme.LocalCanvasColors
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the review of the proposals and of the access of the clients (`docs/agent-tools.md`).
 *
 * `./gradlew :drawview-agent-ui:recordRoborazziJvm` records them in `screenshots/`.
 */
@OptIn(ExperimentalTestApi::class)
class AgentReviewScreenshotTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    private fun capture(name: String, width: Int, height: Int, dark: Boolean = false, content: @Composable () -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                DrawViewTheme(darkTheme = dark, dynamicColor = false) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LocalCanvasColors.current.desk)
                            .padding(16.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Surface(
                            modifier = Modifier.width((width - 32).dp),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            content = content
                        )
                    }
                }
            }
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/$name.png")
        }

    private val waiting = Proposal(
        id = "one",
        documentId = "document",
        client = "Claude Code",
        title = "Tidy the notes on Maxwell's equations",
        entries = listOf(
            ProposalEntry(
                ProposalItem.ReplaceText("a", "base", "**Gauss's law:** the flux through a closed surface is $\\frac{Q}{\\varepsilon_0}$."),
                before = "gauss: flux through closed surface = Q / eps0"
            ),
            ProposalEntry(ProposalItem.RemoveText("b", "base"), before = "see page 4 (old)"),
            ProposalEntry(ProposalItem.InsertText("## Summary\nThe four equations, side by side.")),
            ProposalEntry(ProposalItem.AddPage())
        )
    )

    private val decided = Proposal(
        id = "two",
        documentId = "document",
        client = "A script",
        title = "Fix a typo",
        entries = listOf(
            ProposalEntry(ProposalItem.ReplaceText("c", "base", "new"), before = "old", outcome = ItemOutcome.ACCEPTED),
            ProposalEntry(ProposalItem.ReplaceText("d", "base", "new"), before = "old", outcome = ItemOutcome.STALE)
        )
    )

    @Test
    fun review() = capture("agent_review", 420, 760) {
        ReviewList(listOf(decided, waiting), mapOf("one" to setOf(1)), onDecide = { _, _ -> })
    }

    @Test
    fun reviewDark() = capture("agent_review_dark", 420, 760, dark = true) {
        ReviewList(listOf(decided, waiting), mapOf("one" to setOf(1)), onDecide = { _, _ -> })
    }

    @Test
    fun reviewEmpty() = capture("agent_review_empty", 420, 180) {
        ReviewList(emptyList(), emptyMap(), onDecide = { _, _ -> })
    }

    @Test
    fun access() = runDesktopComposeUiTest(width = 640, height = 620) {
        setContent {
            DrawViewTheme(darkTheme = false, dynamicColor = false) {
                AdaptiveRoot(Modifier.fillMaxSize()) {
                    AccessSheet(
                        grants = AgentGrants(enabled = true, documents = setOf("document")),
                        documentId = "document",
                        connections = listOf(AgentConnection("c", "Claude Code", direct = false)),
                        onEnabled = {},
                        onDocument = {},
                        onDirect = { _, _ -> },
                        onDismiss = {}
                    )
                }
            }
        }
        waitForIdle()
        captureToImage().captureRoboImage("screenshots/agent_access.png")
    }
}
