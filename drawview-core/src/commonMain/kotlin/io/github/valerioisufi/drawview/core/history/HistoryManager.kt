package io.github.valerioisufi.drawview.core.history

import androidx.compose.runtime.mutableStateListOf
import io.github.valerioisufi.drawview.core.change.ChangeObserver
import io.github.valerioisufi.drawview.core.change.ChangeOrigin
import io.github.valerioisufi.drawview.core.change.CommittedChange
import io.github.valerioisufi.drawview.core.change.CompositeChange
import io.github.valerioisufi.drawview.core.change.DocumentChange
import io.github.valerioisufi.drawview.core.change.DocumentSession

/**
 * Undo / redo of the changes made on this device: records the change undoing every local change
 * of [session] and submits it on undo (the changes of the other devices are not undone).
 *
 * The changes submitted inside a group ([group], or [beginGroup] / [endGroup], e.g. the strokes
 * erased by one gesture) are undone together.
 *
 * Backed by Compose snapshot state so that [canUndo] and [canRedo] are observable.
 */
class HistoryManager(
    private val session: DocumentSession,
    private val maxHistorySize: Int = 50
) : ChangeObserver {
    private val undoStack = mutableStateListOf<DocumentChange>()
    private val redoStack = mutableStateListOf<DocumentChange>()

    private var groupDepth = 0
    private val groupInverses = mutableListOf<DocumentChange>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    init {
        session.addObserver(this)
    }

    /** Stops recording the changes of the session. */
    fun dispose() {
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
    fun beginGroup() {
        groupDepth++
    }

    /** Ends the group started by [beginGroup]: its changes become one entry of the history. */
    fun endGroup() {
        if (groupDepth == 0) return
        groupDepth--
        if (groupDepth > 0 || groupInverses.isEmpty()) return
        val inverses = groupInverses.toList()
        groupInverses.clear()
        push(if (inverses.size == 1) inverses[0] else CompositeChange(inverses.asReversed()))
    }

    /** Runs [block] in a group: the changes it submits are undone together. */
    inline fun <T> group(block: () -> T): T {
        beginGroup()
        try {
            return block()
        } finally {
            endGroup()
        }
    }

    fun undo() {
        val inverse = undoStack.removeLastOrNull() ?: return
        session.submit(inverse, ChangeOrigin.UNDO)?.let { redoStack.add(it) }
    }

    fun redo() {
        val change = redoStack.removeLastOrNull() ?: return
        session.submit(change, ChangeOrigin.REDO)?.let { undoStack.add(it) }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun push(inverse: DocumentChange) {
        undoStack.add(inverse)
        redoStack.clear()
        if (undoStack.size > maxHistorySize) undoStack.removeAt(0)
    }
}
