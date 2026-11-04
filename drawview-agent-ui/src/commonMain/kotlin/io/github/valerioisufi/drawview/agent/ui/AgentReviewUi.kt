package io.github.valerioisufi.drawview.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.agent.AgentConnection
import io.github.valerioisufi.drawview.agent.AgentGrants
import io.github.valerioisufi.drawview.agent.ExternalAgents
import io.github.valerioisufi.drawview.agent.ItemOutcome
import io.github.valerioisufi.drawview.agent.Proposal
import io.github.valerioisufi.drawview.agent.ProposalEntry
import io.github.valerioisufi.drawview.agent.ProposalItem
import io.github.valerioisufi.drawview.agent.ui.generated.resources.Res
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_accept_selected
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_access_clients
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_access_direct
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_access_document
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_access_document_note
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_access_enable
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_access_enable_note
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_access_how
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_access_no_clients
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_access_title
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_close
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_command
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_decided_title
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_item_add_page
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_item_insert
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_item_insert_new_page
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_item_remove
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_item_replace
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_item_stale
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_outcome_accepted
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_outcome_rejected
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_outcome_stale
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_reject_all
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_review_empty
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_review_from
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_review_show
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_review_title
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.extension.EditorPanel
import io.github.valerioisufi.drawview.ui.screen.EditorActionButton
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The external models in the editors of an application (Controller): the panel of the proposals of a
 * document, opened when one arrives, and the sheet of the access. No hook of its own in the editor:
 * a command, a panel and an overlay.
 */
public class AgentReviewUi(private val agents: ExternalAgents) {
    private val openPanels = mutableStateSetOf<String>()
    private var accessOf by mutableStateOf<String?>(null)

    private fun accessCommand(documentId: String) = Command(
        id = "external_models",
        label = { stringResource(Res.string.agent_command) },
        group = CommandGroup.TOOLS,
        perform = { accessOf = documentId }
    )

    /** What the application adds to its editors. */
    public val editorExtension: EditorExtension = EditorExtension(
        toolbarActions = { context ->
            val proposals by agents.proposals.proposals.collectAsState()
            if (proposals.any { it.documentId == context.documentId && it.isWaiting }) {
                EditorActionButton(DrawViewIcons.Sparkles, stringResource(Res.string.agent_review_show), onClick = { openPanels += context.documentId })
            }
        },
        canvasOverlay = { context ->
            val documentId = context.documentId
            val proposals by agents.proposals.proposals.collectAsState()
            val waiting = proposals.count { it.documentId == documentId && it.isWaiting }
            // A new proposal shows itself: nothing happens until it is looked at
            LaunchedEffect(documentId, waiting) {
                if (waiting > 0) openPanels += documentId
            }
            if (accessOf == documentId) {
                val grants by agents.access.grants.collectAsState()
                val connections by agents.access.connections.collectAsState()
                val scope = rememberCoroutineScope()
                AccessSheet(
                    grants = grants,
                    documentId = documentId,
                    connections = connections,
                    onEnabled = { enabled -> scope.launch { agents.access.setEnabled(enabled) } },
                    onDocument = { allowed ->
                        scope.launch { if (allowed) agents.access.grant(documentId) else agents.access.revoke(documentId) }
                    },
                    onDirect = { id, direct -> agents.access.setDirect(id, direct) },
                    onDismiss = { accessOf = null }
                )
            }
        },
        commands = { context -> listOf(accessCommand(context.documentId)) },
        panels = { context -> listOf(reviewPanel(context.documentId, context.controller)) }
    )

    private fun reviewPanel(documentId: String, controller: DrawViewController) = EditorPanel(
        id = "external_models",
        title = { stringResource(Res.string.agent_review_title) },
        isOpen = { documentId in openPanels },
        onClose = { openPanels -= documentId },
        icon = DrawViewIcons.Sparkles,
        content = { ReviewContent(documentId, controller) }
    )

    @Composable
    private fun ReviewContent(documentId: String, controller: DrawViewController) {
        val all by agents.proposals.proposals.collectAsState()
        val proposals = all.filter { it.documentId == documentId }
        val scope = rememberCoroutineScope()
        // What is stale follows the document
        var changes by remember { mutableIntStateOf(0) }
        DisposableEffect(controller) {
            val observer = ChangeObserver { changes++ }
            controller.session.addObserver(observer)
            onDispose { controller.session.removeObserver(observer) }
        }
        var stale by remember { mutableStateOf<Map<String, Set<Int>>>(emptyMap()) }
        LaunchedEffect(all, changes) {
            stale = proposals.filter { it.isWaiting }.associate { it.id to agents.staleItems(it) }
        }
        ReviewList(
            proposals = proposals,
            staleItems = stale,
            onDecide = { id, accepted -> scope.launch { agents.decide(id, accepted) } },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The proposals of a document, those waiting first. */
@Composable
internal fun ReviewList(
    proposals: List<Proposal>,
    staleItems: Map<String, Set<Int>>,
    onDecide: (String, Set<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    val waiting = proposals.filter { it.isWaiting }
    val decided = proposals.filterNot { it.isWaiting }.asReversed()
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (waiting.isEmpty()) {
            Text(
                stringResource(Res.string.agent_review_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        for (proposal in waiting) {
            androidx.compose.runtime.key(proposal.id) {
                ProposalCard(proposal, staleItems[proposal.id].orEmpty(), onDecide)
            }
        }
        if (decided.isNotEmpty()) {
            Text(
                stringResource(Res.string.agent_decided_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (proposal in decided) {
                androidx.compose.runtime.key(proposal.id) { DecidedProposal(proposal) }
            }
        }
    }
}

/** A proposal waiting: each item with a box, on by default but when stale. */
@Composable
private fun ProposalCard(proposal: Proposal, stale: Set<Int>, onDecide: (String, Set<Int>) -> Unit) {
    val unchecked = remember(proposal.id) { mutableStateSetOf<Int>() }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(proposal.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(Res.string.agent_review_from, proposal.client),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            proposal.entries.forEachIndexed { index, entry ->
                val isStale = index in stale
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = !isStale && index !in unchecked,
                        enabled = !isStale,
                        onCheckedChange = { checked -> if (checked) unchecked -= index else unchecked += index }
                    )
                    EntryContent(entry, isStale, Modifier.weight(1f).padding(top = 12.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val accepted = proposal.entries.indices.filter { it !in stale && it !in unchecked }.toSet()
                Button(onClick = { onDecide(proposal.id, accepted) }, enabled = accepted.isNotEmpty()) {
                    Text(stringResource(Res.string.agent_accept_selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { onDecide(proposal.id, emptySet()) }) {
                    Text(stringResource(Res.string.agent_reject_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** What an item does: the text as it is and as it would be. */
@Composable
private fun EntryContent(entry: ProposalEntry, stale: Boolean, modifier: Modifier = Modifier) {
    val item = entry.item
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(itemLabel(item), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        entry.before?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough
            )
        }
        val after = when (item) {
            is ProposalItem.ReplaceText -> item.markdown
            is ProposalItem.InsertText -> item.markdown
            else -> null
        }
        after?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (stale) {
            Text(
                stringResource(Res.string.agent_item_stale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun itemLabel(item: ProposalItem): String = stringResource(
    when (item) {
        is ProposalItem.ReplaceText -> Res.string.agent_item_replace
        is ProposalItem.RemoveText -> Res.string.agent_item_remove
        is ProposalItem.InsertText -> if (item.newPage) Res.string.agent_item_insert_new_page else Res.string.agent_item_insert
        is ProposalItem.AddPage -> Res.string.agent_item_add_page
    }
)

/** A proposal decided, in a few words: what became of each item. */
@Composable
private fun DecidedProposal(proposal: Proposal) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(proposal.title, style = MaterialTheme.typography.bodyMedium)
        proposal.entries.forEach { entry ->
            val outcome = when (entry.outcome) {
                ItemOutcome.ACCEPTED -> Res.string.agent_outcome_accepted
                ItemOutcome.STALE -> Res.string.agent_outcome_stale
                else -> Res.string.agent_outcome_rejected
            }
            Text(
                "${itemLabel(entry.item)}: ${stringResource(outcome)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.outcome == ItemOutcome.STALE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The access of the clients: on or off, this document, those connected. */
@Composable
internal fun AccessSheet(
    grants: AgentGrants,
    documentId: String,
    connections: List<AgentConnection>,
    onEnabled: (Boolean) -> Unit,
    onDocument: (Boolean) -> Unit,
    onDirect: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveSheet(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.agent_access_title)) },
        actions = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.agent_close)) } }
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SwitchRow(
                stringResource(Res.string.agent_access_enable),
                stringResource(Res.string.agent_access_enable_note),
                checked = grants.enabled,
                onChange = onEnabled
            )
            if (grants.enabled) {
                SwitchRow(
                    stringResource(Res.string.agent_access_document),
                    stringResource(Res.string.agent_access_document_note),
                    checked = documentId in grants.documents,
                    onChange = onDocument
                )
                Text(
                    stringResource(Res.string.agent_access_how),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(stringResource(Res.string.agent_access_clients), style = MaterialTheme.typography.titleSmall)
                if (connections.isEmpty()) {
                    Text(
                        stringResource(Res.string.agent_access_no_clients),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (connection in connections) {
                    SwitchRow(
                        connection.name,
                        stringResource(Res.string.agent_access_direct),
                        checked = connection.direct,
                        onChange = { onDirect(connection.id, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
