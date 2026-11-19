package io.github.valerioisufi.pageboard.store.space

import io.github.valerioisufi.pageboard.model.change.HybridClock
import io.github.valerioisufi.pageboard.model.change.OperationId

/** A change of a shared folder applied ([inverse]: what undoes it; [received]: made on another device). */
public class CommittedCatalogChange(
    public val operation: CatalogOperation,
    public val inverse: CatalogChange?,
    public val received: Boolean
)

/** Told of every change applied by a [CatalogSession]. */
public fun interface CatalogObserver {
    public fun onChange(committed: CommittedCatalogChange)
}

/**
 * The single entry point of the changes of a shared folder (GRASP Controller), as
 * `DocumentSession` is for a document: it stamps and numbers the changes made here ([submit]),
 * applies the ones of the other devices once each and in the order of their replica ([receive]),
 * and keeps the version vector.
 *
 * @param replica the device making the changes (see `DocumentStore.replicaId`).
 * @param knownOperations the version vector of the operations already applied to [catalog].
 */
public class CatalogSession(
    public val spaceId: String,
    public val catalog: SpaceCatalog,
    public val replica: String,
    knownOperations: Map<String, Long> = emptyMap(),
    wallClock: (() -> Long)? = null
) {
    public val clock: HybridClock = if (wallClock != null) HybridClock(replica, wallClock) else HybridClock(replica)
    private val applied = knownOperations.toMutableMap()
    private val observers = mutableListOf<CatalogObserver>()

    init {
        clock.observe(catalog.latestStamp())
    }

    /** The space may only be read here (shared with this device as a reader): [submit] refuses. */
    public var isReadOnly: Boolean = false

    /** The last operation applied of every replica. */
    public val versionVector: Map<String, Long> get() = applied.toMap()

    public fun lastApplied(replica: String): Long = applied[replica] ?: 0L

    public fun addObserver(observer: CatalogObserver) {
        observers.add(observer)
    }

    public fun removeObserver(observer: CatalogObserver) {
        observers.remove(observer)
    }

    /**
     * Applies [change], made on this device, and tells the observers.
     *
     * @return the operation, to log and send, or `null` when the space is read only here or the
     *   change is not well formed.
     */
    public fun submit(change: CatalogChange): CommittedCatalogChange? {
        if (isReadOnly || !change.isWellFormed()) return null
        val stamp = clock.next()
        val inverse = change.applyTo(catalog, stamp)
        val sequence = (applied[replica] ?: 0L) + 1
        applied[replica] = sequence
        val committed = CommittedCatalogChange(CatalogOperation(OperationId(replica, sequence), stamp, change), inverse, received = false)
        notify(committed)
        return committed
    }

    /**
     * Whether [operation], made on another device, may be received: well formed, stamped by its
     * replica, and at most [HybridClock.MAX_AHEAD_MS] ahead of the wall clock here (see
     * `DocumentSession.admits`). Whether its replica may write is the membership's to say.
     */
    public fun admits(operation: CatalogOperation): Boolean =
        operation.stamp.replica == operation.id.replica && operation.id.replica != replica &&
            operation.id.sequence > 0 && clock.admits(operation.stamp) && operation.change.isWellFormed()

    /**
     * Applies [operation], made on another device, unless it has been applied already; one that
     * does not follow the last one of its replica is not applied.
     *
     * @return `true` when it has been applied (even if it changed nothing: it was made before
     *   more recent changes).
     * @throws IllegalArgumentException when it follows the last one of its replica but may not be
     *   received (see [admits]).
     */
    public fun receive(operation: CatalogOperation): Boolean {
        val id = operation.id
        if (id.sequence != (applied[id.replica] ?: 0L) + 1) return false
        require(admits(operation)) { "The operation ${id.replica}/${id.sequence} of the space is not admitted" }
        clock.observe(operation.stamp)
        val inverse = operation.change.applyTo(catalog, operation.stamp)
        applied[id.replica] = id.sequence
        notify(CommittedCatalogChange(operation, inverse, received = true))
        return true
    }

    private fun notify(committed: CommittedCatalogChange) {
        for (observer in observers.toList()) observer.onChange(committed)
    }
}
