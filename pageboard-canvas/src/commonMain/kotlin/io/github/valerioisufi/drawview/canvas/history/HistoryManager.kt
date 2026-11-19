package io.github.valerioisufi.drawview.canvas.history

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.model.change.CommittedChange
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.DocumentSession

/**
 * Undo / redo of the changes made on this device: records the change undoing every local change
 * of [session] and submits it on undo (the changes of the other devices are not undone).
 *
 * The changes submitted inside a group ([group], or [beginGroup] / [endGroup], e.g. the strokes
 * erased by one gesture) are undone together. The changes submitted into the entry kept open
 * ([intoEntry], e.g. the saves of a text while it is written) are undone together too, until the
 * entry is closed ([closeEntry]) or another change is recorded.
 *
 * Backed by Compose snapshot state so that [canUndo] and [canRedo] are observable.
 */
public class HistoryManager internal constructor(
    private val session: DocumentSession,
    private val maxHistorySize: Int = 50
) : ChangeObserver {
    /**
     * The entries of the history: the changes undoing (or redoing) one of them, submitted one after
     * the other, each its own operation (the changes of an entry kept open may change one text several
     * times, which one operation could not: its stamp would cover them all).
     */
    private val undoStack = mutableStateListOf<List<DocumentChange>>()
    private val redoStack = mutableStateListOf<List<DocumentChange>>()

    private var groupDepth = 0
    private val groupInverses = mutableListOf<DocumentChange>()

    /** The inverses of the changes of the entry kept open, in the order they were made (see [intoEntry]). */
    private val entryInverses = mutableListOf<DocumentChange>()

    /** Whether the entry kept open has changes, observable. */
    private var entryHasChanges by mutableStateOf(false)

    /** Whether the changes submitted now go into the entry kept open (see [intoEntry]). */
    private var recordingEntry = false

    // What the views read: whether there is something to undo changes less often than the stacks
    private val undoable = derivedStateOf { undoStack.isNotEmpty() || entryHasChanges }
    private val redoable = derivedStateOf { redoStack.isNotEmpty() }

    public val canUndo: Boolean get() = undoable.value
    public val canRedo: Boolean get() = redoable.value

    init {
        session.addObserver(this)
    }

    /** Stops recording the changes of the session. */
    public fun dispose() {
        session.removeObserver(this)
    }

    override fun onChange(committed: CommittedChange) {
        if (committed.origin != ChangeOrigin.LOCAL) return
        val inverse = committed.inverse ?: return
        if (groupDepth > 0) {
            groupInverses.add(inverse)
        } else {
            record(inverse)
        }
    }

    /** Whether an entry is kept open with changes in it (see [intoEntry]). */
    internal val hasEntry: Boolean get() = entryInverses.isNotEmpty()

    /**
     * Runs [block], whose changes go into the entry kept open: one entry of the history with the
     * changes submitted into it before, until [closeEntry] or a change recorded otherwise.
     */
    internal fun <T> intoEntry(block: () -> T): T {
        val wasRecording = recordingEntry
        recordingEntry = true
        try {
            return block()
        } finally {
            recordingEntry = wasRecording
        }
    }

    /**
     * Closes the entry kept open: its changes become one entry of the history, or are forgotten
     * unless [keep] (changes that undo to nothing).
     */
    internal fun closeEntry(keep: Boolean = true) {
        if (entryInverses.isEmpty()) return
        val inverses = entryInverses.toList()
        entryInverses.clear()
        entryHasChanges = false
        // Undone from the last one
        if (keep) push(inverses.asReversed())
    }

    /** Starts a group of changes undone together (groups can be nested). */
    @PublishedApi
    internal fun beginGroup() {
        groupDepth++
    }

    /** Ends the group started by [beginGroup]: its changes become one entry of the history. */
    @PublishedApi
    internal fun endGroup() {
        if (groupDepth == 0) return
        groupDepth--
        if (groupDepth > 0 || groupInverses.isEmpty()) return
        val inverses = groupInverses.toList()
        groupInverses.clear()
        record(if (inverses.size == 1) inverses[0] else CompositeChange(inverses.asReversed()))
    }

    /** Runs [block] in a group: the changes it submits are undone together. */
    public inline fun <T> group(block: () -> T): T {
        beginGroup()
        try {
            return block()
        } finally {
            endGroup()
        }
    }

    public fun undo() {
        closeEntry()
        val inverses = undoStack.removeLastOrNull() ?: return
        val redone = inverses.mapNotNull { session.submit(it, ChangeOrigin.UNDO) }
        if (redone.isNotEmpty()) redoStack.add(redone.asReversed())
    }

    public fun redo() {
        closeEntry()
        val changes = redoStack.removeLastOrNull() ?: return
        val undone = changes.mapNotNull { session.submit(it, ChangeOrigin.REDO) }
        if (undone.isNotEmpty()) undoStack.add(undone.asReversed())
    }

    public fun clear() {
        entryInverses.clear()
        entryHasChanges = false
        undoStack.clear()
        redoStack.clear()
    }

    /** Records [inverse]: into the entry kept open while [intoEntry] runs, otherwise after it, closed. */
    private fun record(inverse: DocumentChange) {
        if (recordingEntry) {
            entryInverses.add(inverse)
            entryHasChanges = true
        } else {
            closeEntry()
            push(listOf(inverse))
        }
    }

    private fun push(inverses: List<DocumentChange>) {
        undoStack.add(inverses)
        redoStack.clear()
        if (undoStack.size > maxHistorySize) undoStack.removeAt(0)
    }
}
