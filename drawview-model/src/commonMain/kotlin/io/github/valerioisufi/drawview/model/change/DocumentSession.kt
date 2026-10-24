package io.github.valerioisufi.drawview.model.change

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a change of a document comes from. */
public enum class ChangeOrigin {
    /** Made by the user on this device (recorded in the history). */
    LOCAL,

    /** Undo of a local change. */
    UNDO,

    /** Redo of a local change. */
    REDO,

    /** Made on another device. */
    REMOTE,

    /**
     * Made by this device to merge its changes with the ones of the others (e.g. the copy of the
     * content of a text lost to another one, see [ChangeEffects.lostVersions]), or to store what
     * it draws (e.g. the height of a text drawn differently since it was stored): not recorded in
     * the history.
     */
    MERGE
}

/**
 * An operation applied to [document]: the change undoing it ([inverse], `null` for an operation
 * of another device that changed nothing here), where it comes from and what it did.
 */
public class CommittedChange(
    public val document: DrawDocument,
    public val operation: Operation,
    public val inverse: DocumentChange?,
    public val origin: ChangeOrigin,
    public val effects: ChangeEffects
) {
    public val change: DocumentChange get() = operation.change
}

/** Notified of every change applied to a document, on the thread of the document. */
public fun interface ChangeObserver {
    public fun onChange(committed: CommittedChange)
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
public class DocumentSession(
    public val document: DrawDocument,
    public val replica: String = newElementId(),
    knownOperations: Map<String, Long> = emptyMap()
) {
    public val clock: HybridClock = HybridClock(replica)
    private val applied = knownOperations.toMutableMap()
    private val observers = mutableListOf<ChangeObserver>()

    init {
        clock.observe(document.latestStamp())
    }

    private val readOnlyState = MutableStateFlow(false)

    /**
     * The document may only be read here (e.g. shared with this device as a reader): the changes
     * made here are refused, the ones of the other devices are received.
     */
    public var isReadOnly: Boolean
        get() = readOnlyState.value
        set(value) {
            readOnlyState.value = value
        }

    /** [isReadOnly] as it changes. */
    public val readOnly: StateFlow<Boolean> = readOnlyState.asStateFlow()

    /** The last operation applied of every replica. */
    public val versionVector: Map<String, Long> get() = applied.toMap()

    /** The number of the last operation of [replica] applied (0: none). */
    public fun lastApplied(replica: String): Long = applied[replica] ?: 0L

    public fun addObserver(observer: ChangeObserver) {
        observers.add(observer)
    }

    public fun removeObserver(observer: ChangeObserver) {
        observers.remove(observer)
    }

    /**
     * Applies [change], made on this device, and notifies the observers.
     *
     * @return the change undoing it, or `null` when it changed nothing (it is not an operation
     *   then: nobody is notified).
     */
    public fun submit(change: DocumentChange, origin: ChangeOrigin = ChangeOrigin.LOCAL): DocumentChange? {
        require(origin != ChangeOrigin.REMOTE) { "The changes of another device are received" }
        if (isReadOnly) return null
        val stamp = clock.next()
        val effects = ChangeEffects(document)
        val inverse = change.applyTo(ChangeContext(document, effects, stamp)) ?: return null
        val sequence = (applied[replica] ?: 0L) + 1
        applied[replica] = sequence
        notify(CommittedChange(document, Operation(OperationId(replica, sequence), stamp, change), inverse, origin, effects))
        keepLostVersions(effects)
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
    public fun receive(operation: Operation): Boolean {
        val id = operation.id
        if (id.sequence != (applied[id.replica] ?: 0L) + 1) return false
        clock.observe(operation.stamp)
        val effects = ChangeEffects(document)
        val inverse = operation.change.applyTo(ChangeContext(document, effects, operation.stamp, received = true))
        applied[id.replica] = id.sequence
        notify(CommittedChange(document, operation, inverse, ChangeOrigin.REMOTE, effects))
        keepLostVersions(effects)
        return true
    }

    /**
     * Adds the copies of the contents of texts lost to others made apart (see
     * [ChangeEffects.lostVersions]): the same copies on every device that sees the two contents.
     */
    private fun keepLostVersions(effects: ChangeEffects) {
        for ((page, copies) in effects.lostVersions.groupBy({ it.first }, { it.second })) {
            submit(AddElements(page.id, copies, lostVersions = true), ChangeOrigin.MERGE)
        }
    }

    private fun notify(committed: CommittedChange) {
        if (!committed.effects.isEmpty) {
            document.modifiedAt = currentTimeMillis()
            document.markChanged()
        }
        for (observer in observers.toList()) observer.onChange(committed)
    }
}
