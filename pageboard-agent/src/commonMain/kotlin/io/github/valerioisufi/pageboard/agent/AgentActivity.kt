package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.AddPage
import io.github.valerioisufi.pageboard.model.change.AddResource
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.MovePages
import io.github.valerioisufi.pageboard.model.change.PlaceElements
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.change.RemovePage
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.model.change.ReorderElements
import io.github.valerioisufi.pageboard.model.change.RestyleMarkups
import io.github.valerioisufi.pageboard.model.change.SetDocumentDefaults
import io.github.valerioisufi.pageboard.model.change.SetPageFormat
import io.github.valerioisufi.pageboard.model.change.UpdateElementContent
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A batch of changes a model made to a document, the items of a proposal applied as one operation, or
 * to the library ([documentId] `null`).
 *
 * @param client the name the client gave itself: for the user to read, not proof of anything.
 * @param title the title of its proposal.
 * @param at when it was applied, in milliseconds since the epoch.
 * @param undone whether it was undone from the activity.
 * @param undoable whether the change undoing it is known: not when it was kept by a version of the
 *   application whose changes this one does not read.
 */
public data class AgentBatch(
    val id: String,
    val documentId: String?,
    val client: String,
    val title: String,
    val items: Int,
    val at: Long,
    val undone: Boolean = false,
    val undoable: Boolean = true
)

/** How a batch is undone. */
internal sealed interface BatchUndo

/** A batch of a document: the change undoing it, and the stamp of its operation. */
internal class DocumentUndo(val inverse: DocumentChange, val stamp: Stamp) : BatchUndo

/** A batch of the library: the steps undoing its items, in their order. */
internal class LibraryUndos(val steps: List<LibraryUndo>) : BatchUndo

/** What became of the undo of an [AgentBatch]. */
public enum class UndoOutcome {
    /** Its inverse was applied. */
    UNDONE,

    /** What it changed was changed again since (by the user, a Ctrl+Z, another device): nothing was done. */
    CHANGED,

    /** Undone already, or no longer kept. */
    GONE,

    /** Its document is not there, or may only be read on this device. */
    UNAVAILABLE
}

/**
 * What the models changed (Creator, Controller): every batch applied, with the change undoing it, so
 * that the user can undo it whether its document is in an editor or not. It outlives the application
 * when it keeps an [AgentJournal]; without one quitting drops it, like the proposals: what a model
 * removed is still hidden in the document.
 *
 * A batch is undone only while what it touched is as it left it: no piece of it (an element, a page,
 * the title) changed after its operation. So an undo after the user wrote on it, or after a Ctrl+Z of
 * the same batch, overwrites nothing.
 *
 * @param maxKept how many batches are kept, the most recent ones.
 */
public class AgentActivity(
    private val maxKept: Int = DEFAULT_MAX_KEPT,
    private val clock: () -> Long = ::currentTimeMillis
) {
    private val state = MutableStateFlow<List<AgentBatch>>(emptyList())
    private val undos = mutableMapOf<String, BatchUndo>()

    /** The batches, the oldest first. */
    public val batches: StateFlow<List<AgentBatch>> = state.asStateFlow()

    /** The batch [id], or `null`. */
    public fun find(id: String): AgentBatch? = state.value.firstOrNull { it.id == id }

    internal fun record(documentId: String, client: String, title: String, items: Int, inverse: DocumentChange, stamp: Stamp): AgentBatch =
        record(documentId, client, title, items, DocumentUndo(inverse, stamp))

    internal fun record(documentId: String?, client: String, title: String, items: Int, undo: BatchUndo): AgentBatch {
        val batch = AgentBatch(newElementId(), documentId, client, title, items, clock())
        var dropped = emptyList<AgentBatch>()
        state.update { current ->
            val all = current + batch
            dropped = all.dropLast(maxKept)
            all.takeLast(maxKept)
        }
        undos[batch.id] = undo
        dropped.forEach { undos.remove(it.id) }
        return batch
    }

    /** Puts back the batches kept before the application quit, the most recent ones, with how they are undone. */
    internal fun restore(kept: List<AgentBatch>, keptUndos: Map<String, BatchUndo>) {
        val batches = kept.takeLast(maxKept)
        undos.clear()
        batches.forEach { batch -> keptUndos[batch.id]?.takeIf { !batch.undone }?.let { undos[batch.id] = it } }
        state.value = batches.map { it.copy(undoable = it.undone || it.id in undos) }
    }

    /** How the batch [id] is undone, while it is kept and not undone. */
    internal fun undoOf(id: String): BatchUndo? = undos[id]

    internal fun markUndone(id: String) {
        undos.remove(id)
        state.update { current -> current.map { if (it.id == id) it.copy(undone = true) else it } }
    }

    public companion object {
        public const val DEFAULT_MAX_KEPT: Int = 100
    }
}

/**
 * Whether what [change] (the inverse of a batch applied at [stamp]) would undo in [document] is as the
 * batch left it: no stamp of what it touches is after [stamp]. A kind of change it cannot tell about
 * counts as changed.
 */
internal fun unchangedSince(document: Document, change: DocumentChange, stamp: Stamp): Boolean {
    fun page(id: String): Page? = document.pageById(id) ?: document.removedPages[id]
    fun pageUnchanged(id: String): Boolean {
        val page = page(id) ?: return true
        return page.stamps().all { it <= stamp }
    }
    fun elementsUnchanged(pageId: String, ids: Collection<String>): Boolean {
        val page = page(pageId) ?: return true
        val wanted = ids.toSet()
        return (page.elements() + page.removedElements).filter { it.id in wanted }.all { element -> element.stamps().all { it <= stamp } }
    }
    return when (change) {
        is CompositeChange -> change.changes.all { unchangedSince(document, it, stamp) }
        is AddElements -> elementsUnchanged(change.pageId, change.elements.map { it.id })
        is RemoveElements -> elementsUnchanged(change.pageId, change.ids)
        is UpdateTexts -> elementsUnchanged(change.pageId, change.texts.map { it.id })
        is PlaceElements -> elementsUnchanged(change.pageId, change.placements.map { it.id }) &&
            elementsUnchanged(change.toPageId, change.placements.map { it.id })
        is ReorderElements -> elementsUnchanged(change.pageId, change.orders.map { it.id })
        is RestyleMarkups -> elementsUnchanged(change.pageId, change.styles.map { it.id })
        is UpdateElementContent -> elementsUnchanged(change.pageId, listOf(change.id))
        is AddPage -> pageUnchanged(change.page.id)
        is RemovePage -> pageUnchanged(change.pageId)
        is MovePages -> change.moves.all { pageUnchanged(it.pageId) }
        is SetPageFormat -> pageUnchanged(change.pageId)
        is RenameDocument -> document.titleVersion <= stamp
        is SetDocumentDefaults -> document.defaultsVersion <= stamp
        is AddResource -> true
        // The inverse of an AddResource
        else -> change.changesNothing
    }
}

/** Every stamp of this element: its presence, place, order, content. */
private fun PageElement.stamps(): Sequence<Stamp> =
    sequenceOf(added, removed ?: Stamp.ZERO, placed, ordered, updated) +
        ((this as? TextElement)?.contentVersions?.stamps?.values?.asSequence() ?: emptySequence())

/** Every stamp of this page: its presence, position, format and what is on it. */
private fun Page.stamps(): Sequence<Stamp> =
    sequenceOf(added, removed ?: Stamp.ZERO, orderVersion, formatVersion) +
        contentVersions.stamps.values.asSequence() +
        (elements() + removedElements).asSequence().flatMap { it.stamps() }
