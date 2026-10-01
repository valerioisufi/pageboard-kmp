package io.github.valerioisufi.drawview.core.history

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manages Undo and Redo stacks for vector drawing actions.
 * Backed by Compose SnapshotStateList so UI reacts automatically.
 *
 * @param coroutineScope Scope used to execute suspend undo/redo actions.
 * @param maxHistorySize Maximum depth of the undo stack.
 * @param onDocumentModified Callback invoked whenever document history alters.
 */
class HistoryManager(
    private val coroutineScope: CoroutineScope,
    private val maxHistorySize: Int = 50,
    private val onDocumentModified: () -> Unit = {}
) {
    val undoStack: SnapshotStateList<DrawAction> = mutableStateListOf()
    val redoStack: SnapshotStateList<DrawAction> = mutableStateListOf()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Registers a new user action. Clears redo timeline.
     */
    fun addHistoryAction(action: DrawAction) {
        undoStack.add(action)
        redoStack.clear()

        if (undoStack.size > maxHistorySize) {
            undoStack.removeAt(0)
        }

        onDocumentModified()
    }

    /**
     * Executes Undo.
     */
    fun undo(context: DrawingActionContext) {
        if (undoStack.isEmpty()) return
        val action = undoStack.removeAt(undoStack.size - 1)
        coroutineScope.launch {
            action.undo(context)
            redoStack.add(action)
            onDocumentModified()
        }
    }

    /**
     * Executes Redo.
     */
    fun redo(context: DrawingActionContext) {
        if (redoStack.isEmpty()) return
        val action = redoStack.removeAt(redoStack.size - 1)
        coroutineScope.launch {
            action.redo(context)
            undoStack.add(action)
            onDocumentModified()
        }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        onDocumentModified()
    }
}
