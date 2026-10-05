package io.github.valerioisufi.drawview.core.change

import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.util.currentTimeMillis

/** Where a change of a document comes from. */
enum class ChangeOrigin {
    /** Made by the user on this device (recorded in the history). */
    LOCAL,

    /** Undo of a local change. */
    UNDO,

    /** Redo of a local change. */
    REDO,

    /** Made on another device. */
    REMOTE
}

/**
 * A change applied to [document]: the change, the one undoing it and what it did.
 */
class CommittedChange(
    val document: DrawDocument,
    val change: DocumentChange,
    val inverse: DocumentChange,
    val origin: ChangeOrigin,
    val effects: ChangeEffects
)

/** Notified of every change applied to a document, on the thread of the document. */
fun interface ChangeObserver {
    fun onChange(committed: CommittedChange)
}

/**
 * The single entry point of the changes of an open [document]: applies them, in order, and
 * notifies its observers (history, persistence, rendering, synchronization...).
 *
 * It holds no UI state: a document can be changed without an editor (e.g. by another device
 * while it is closed). The changes are submitted on the thread that owns the model of the
 * document (the UI thread for an open editor).
 */
class DocumentSession(val document: DrawDocument) {
    private val observers = mutableListOf<ChangeObserver>()

    fun addObserver(observer: ChangeObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ChangeObserver) {
        observers.remove(observer)
    }

    /**
     * Applies [change] and notifies the observers.
     *
     * @return the change undoing it, or `null` when it changed nothing (the observers are not
     *   notified).
     */
    fun submit(change: DocumentChange, origin: ChangeOrigin = ChangeOrigin.LOCAL): DocumentChange? {
        val effects = ChangeEffects()
        val inverse = change.applyTo(ChangeContext(document, effects)) ?: return null
        if (!effects.isEmpty) document.modifiedAt = currentTimeMillis()
        val committed = CommittedChange(document, change, inverse, origin, effects)
        for (observer in observers.toList()) observer.onChange(committed)
        return inverse
    }
}
