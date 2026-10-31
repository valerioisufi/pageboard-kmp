package io.github.valerioisufi.drawview.canvas.history

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
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
 * erased by one gesture) are undone together.
 *
 * Backed by Compose snapshot state so that [canUndo] and [canRedo] are observable.
 */
public class HistoryManager(
    private val session: DocumentSession,
    private val maxHistorySize: Int = 50
) : ChangeObserver {
    private val undoStack = mutableStateListOf<DocumentChange>()
    private val redoStack = mutableStateListOf<DocumentChange>()

    private var groupDepth = 0
    private val groupInverses = mutableListOf<DocumentChange>()

    // What the views read: whether there is something to undo changes less often than the stacks
    private val undoable = derivedStateOf { undoStack.isNotEmpty() }
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
            push(inverse)
        }
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
        push(if (inverses.size == 1) inverses[0] else CompositeChange(inverses.asReversed()))
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
        val inverse = undoStack.removeLastOrNull() ?: return
        session.submit(inverse, ChangeOrigin.UNDO)?.let { redoStack.add(it) }
    }

    public fun redo() {
        val change = redoStack.removeLastOrNull() ?: return
        session.submit(change, ChangeOrigin.REDO)?.let { undoStack.add(it) }
    }

    public fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun push(inverse: DocumentChange) {
        undoStack.add(inverse)
        redoStack.clear()
        if (undoStack.size > maxHistorySize) undoStack.removeAt(0)
    }
}
