package io.github.valerioisufi.drawview.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateMapOf
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
import io.github.valerioisufi.drawview.agent.AGENT_TEXT_COLOR
import io.github.valerioisufi.drawview.agent.AgentConnection
import io.github.valerioisufi.drawview.agent.AgentGrants
import io.github.valerioisufi.drawview.agent.ChosenPlace
import io.github.valerioisufi.drawview.agent.ExternalAgents
import io.github.valerioisufi.drawview.agent.ItemOutcome
import io.github.valerioisufi.drawview.agent.PlacementWarning
import io.github.valerioisufi.drawview.agent.PlannedPiece
import io.github.valerioisufi.drawview.agent.Proposal
import io.github.valerioisufi.drawview.agent.ProposalEntry
import io.github.valerioisufi.drawview.agent.ProposalItem
import io.github.valerioisufi.drawview.agent.ProposalPlan
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
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_place_chosen
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_placing
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_reject_all
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_review_empty
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_review_from
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_review_show
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_review_title
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_show_on_page
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_warning_chain
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_warning_far
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_warning_narrowed
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_warning_new_page
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_warning_overlaps
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_warning_past_end
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_warning_split
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.PlacementItem
import io.github.valerioisufi.drawview.canvas.controller.PlacementRequest
import io.github.valerioisufi.drawview.canvas.controller.PlacementSpot
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.extension.EditorPanel
import io.github.valerioisufi.drawview.ui.screen.EditorActionButton
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
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

    /** The places the user chose for the items of a proposal, until it is decided. */
    private val chosenPlaces = mutableStateMapOf<String, Map<Int, List<ChosenPlace>>>()

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
        var plans by remember { mutableStateOf<Map<String, ProposalPlan>>(emptyMap()) }
        LaunchedEffect(all, changes) {
            val waiting = proposals.filter { it.isWaiting }
            stale = waiting.associate { it.id to agents.staleItems(it) }
            // A plan takes time on the thread of the document: not at every stroke
            delay(PLAN_DELAY_MS)
            plans = waiting.mapNotNull { proposal -> agents.plan(proposal)?.let { proposal.id to it } }.toMap()
        }
        val placing = stringResource(Res.string.agent_placing)
        ReviewList(
            proposals = proposals,
            staleItems = stale,
            warnings = plans.mapValues { (_, plan) ->
                plan.items.withIndex().filter { it.value.warnings.isNotEmpty() }.associate { it.index to it.value.warnings }
            },
            shown = plans.mapValues { (_, plan) ->
                plan.items.indices.filterTo(mutableSetOf()) { plan.items[it].pieces.isNotEmpty() }
            },
            chosen = chosenPlaces.mapValues { it.value.keys },
            onShow = { id, index ->
                agents.proposals.find(id)?.let { proposal -> scope.launch { show(proposal, index, controller, placing) } }
            },
            onDecide = { id, accepted ->
                val chosen = chosenPlaces.remove(id).orEmpty()
                scope.launch { agents.decide(id, accepted, chosen) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    /**
     * Shows the text of an item where it would go: a new text to look at, move and confirm, what it
     * replaces by going to its box. Nothing is applied until the proposal is.
     */
    private suspend fun show(proposal: Proposal, index: Int, controller: DrawViewController, label: String) {
        val plan = agents.plan(proposal, chosenPlaces[proposal.id].orEmpty()) ?: return
        val pieces = plan.items.getOrNull(index)?.pieces.orEmpty()
        val pages = controller.document.pages
        if (proposal.entries.getOrNull(index)?.item !is ProposalItem.InsertText) {
            val piece = pieces.firstOrNull() ?: return
            val page = pages.indexOfFirst { it.id == piece.pageId }
            if (page >= 0) controller.pageManager.jumpToPage(page, piece.box)
            return
        }
        val items = pieces.mapNotNull { piece ->
            val spot = spotOf(piece, plan, pages.map { it.id }) ?: return@mapNotNull null
            val preview = DrawText().apply {
                text = piece.markdown
                width = piece.box.width
                height = piece.box.height
                color = AGENT_TEXT_COLOR
                fontSize = piece.fontSize
                markDirty()
            }
            PlacementItem(preview, listOf(spot))
        }
        if (items.size != pieces.size || items.isEmpty()) return
        controller.placementManager.start(
            PlacementRequest(
                items = items,
                label = label,
                onPlace = { spots ->
                    val places = spots.map { ChosenPlace(if (it.newPageAfter) null else pages.getOrNull(it.pageIndex)?.id, it.box) }
                    chosenPlaces[proposal.id] = chosenPlaces[proposal.id].orEmpty() + (index to places)
                }
            )
        )
    }

    /** The place of [piece] for the editor: on its page, or on a new page after the last page there is. */
    private fun spotOf(piece: PlannedPiece, plan: ProposalPlan, pageIds: List<String>): PlacementSpot? {
        if (!piece.isOnNewPage) return pageIds.indexOf(piece.pageId).takeIf { it >= 0 }?.let { PlacementSpot(it, piece.box) }
        val position = plan.pageOrder.indexOf(piece.pageId)
        val before = (position - 1 downTo 0).firstOrNull { plan.pageOrder[it] in pageIds } ?: return null
        return PlacementSpot(pageIds.indexOf(plan.pageOrder[before]), piece.box, position - before)
    }

    private companion object {
        const val PLAN_DELAY_MS = 300L
    }
}

/** The proposals of a document, those waiting first. */
@Composable
internal fun ReviewList(
    proposals: List<Proposal>,
    staleItems: Map<String, Set<Int>>,
    onDecide: (String, Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
    warnings: Map<String, Map<Int, List<PlacementWarning>>> = emptyMap(),
    shown: Map<String, Set<Int>> = emptyMap(),
    chosen: Map<String, Set<Int>> = emptyMap(),
    onShow: (String, Int) -> Unit = { _, _ -> }
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
                ProposalCard(
                    proposal,
                    staleItems[proposal.id].orEmpty(),
                    warnings[proposal.id].orEmpty(),
                    shown[proposal.id].orEmpty(),
                    chosen[proposal.id].orEmpty(),
                    onShow,
                    onDecide
                )
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
private fun ProposalCard(
    proposal: Proposal,
    stale: Set<Int>,
    warnings: Map<Int, List<PlacementWarning>>,
    shown: Set<Int>,
    chosen: Set<Int>,
    onShow: (String, Int) -> Unit,
    onDecide: (String, Set<Int>) -> Unit
) {
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
                    EntryContent(
                        entry,
                        isStale,
                        warnings[index].orEmpty(),
                        isChosen = index in chosen,
                        onShow = if (index in shown && !isStale) ({ onShow(proposal.id, index) }) else null,
                        modifier = Modifier.weight(1f).padding(top = 12.dp)
                    )
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
private fun EntryContent(
    entry: ProposalEntry,
    stale: Boolean,
    warnings: List<PlacementWarning>,
    isChosen: Boolean,
    onShow: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
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
        // What was made on something changed goes nowhere: where it would go does not matter
        if (!stale) {
            for (warning in warnings) {
                Text(
                    warningText(warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning.harmsThePage) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isChosen) {
            Text(
                stringResource(Res.string.agent_place_chosen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        onShow?.let {
            TextButton(onClick = it, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                Text(stringResource(Res.string.agent_show_on_page), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Whether it would hide or lose something, rather than only be less than ideal. */
private val PlacementWarning.harmsThePage: Boolean
    get() = this is PlacementWarning.Overlaps || this is PlacementWarning.PastPageEnd || this is PlacementWarning.ChainOverflow

/** What is wrong with where an item goes, in words. */
@Composable
private fun warningText(warning: PlacementWarning): String = when (warning) {
    is PlacementWarning.FarFromAnchor -> stringResource(Res.string.agent_warning_far, warning.byMm.roundToInt())
    PlacementWarning.MovedToNewPage -> stringResource(Res.string.agent_warning_new_page)
    is PlacementWarning.Split -> stringResource(Res.string.agent_warning_split, warning.pieces)
    is PlacementWarning.Narrowed -> stringResource(Res.string.agent_warning_narrowed)
    is PlacementWarning.Overlaps -> stringResource(Res.string.agent_warning_overlaps)
    is PlacementWarning.PastPageEnd -> stringResource(Res.string.agent_warning_past_end, warning.byMm.roundToInt())
    is PlacementWarning.ChainOverflow -> stringResource(Res.string.agent_warning_chain)
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
