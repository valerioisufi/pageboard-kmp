package io.github.valerioisufi.drawview.core.change

import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.newElementId
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
 * An operation applied to [document]: the change undoing it ([inverse], `null` for an operation
 * of another device that changed nothing here), where it comes from and what it did.
 */
class CommittedChange(
    val document: DrawDocument,
    val operation: Operation,
    val inverse: DocumentChange?,
    val origin: ChangeOrigin,
    val effects: ChangeEffects
) {
    val change: DocumentChange get() = operation.change
}

/** Notified of every change applied to a document, on the thread of the document. */
fun interface ChangeObserver {
    fun onChange(committed: CommittedChange)
}

/**
 * The single entry point of the changes of an open [document]: applies them, in order, and
 * notifies its observers (history, persistence, rendering, synchronization...).
 *
 * The changes of this device ([submit]) become operations of its [replica], stamped by its
 * [clock] and numbered in order; the operations of the other devices are applied once each
 * ([receive]). [versionVector] tells which operations have been applied: the last one of every
 * replica.
 *
 * It holds no UI state: a document can be changed without an editor (e.g. by another device
 * while it is closed). The changes are submitted on the thread that owns the model of the
 * document (the UI thread for an open editor).
 *
 * @param replica the device making the changes (see `DocumentStore.replicaId`).
 * @param knownOperations the version vector of the operations already applied to [document].
 */
class DocumentSession(
    val document: DrawDocument,
    val replica: String = newElementId(),
    knownOperations: Map<String, Long> = emptyMap()
) {
    val clock = HybridClock(replica)
    private val applied = knownOperations.toMutableMap()
    private val observers = mutableListOf<ChangeObserver>()

    init {
        clock.observe(document.latestStamp())
    }

    /** The last operation applied of every replica. */
    val versionVector: Map<String, Long> get() = applied.toMap()

    fun addObserver(observer: ChangeObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ChangeObserver) {
        observers.remove(observer)
    }

    /**
     * Applies [change], made on this device, and notifies the observers.
     *
     * @return the change undoing it, or `null` when it changed nothing (it is not an operation
     *   then: nobody is notified).
     */
    fun submit(change: DocumentChange, origin: ChangeOrigin = ChangeOrigin.LOCAL): DocumentChange? {
        require(origin != ChangeOrigin.REMOTE) { "The changes of another device are received" }
        val stamp = clock.next()
        val effects = ChangeEffects(document)
        val inverse = change.applyTo(ChangeContext(document, effects, stamp)) ?: return null
        val sequence = (applied[replica] ?: 0L) + 1
        applied[replica] = sequence
        notify(CommittedChange(document, Operation(OperationId(replica, sequence), stamp, change), inverse, origin, effects))
        return inverse
    }

    /**
     * Applies [operation], made on another device, unless it has been applied already. The
     * operations of a replica are applied in their order: one that does not follow the last one
     * of its replica is not applied.
     *
     * @return `true` when it has been applied (even if it changed nothing: it was made before
     *   more recent changes).
     */
    fun receive(operation: Operation): Boolean {
        val id = operation.id
        if (id.sequence != (applied[id.replica] ?: 0L) + 1) return false
        clock.observe(operation.stamp)
        val effects = ChangeEffects(document)
        val inverse = operation.change.applyTo(ChangeContext(document, effects, operation.stamp))
        applied[id.replica] = id.sequence
        notify(CommittedChange(document, operation, inverse, ChangeOrigin.REMOTE, effects))
        return true
    }

    private fun notify(committed: CommittedChange) {
        if (!committed.effects.isEmpty) document.modifiedAt = currentTimeMillis()
        for (observer in observers.toList()) observer.onChange(committed)
    }
}
