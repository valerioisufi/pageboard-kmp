package io.github.valerioisufi.pageboard.sync

/**
 * Changes of another device refused here, and why (see [SyncEngine.refusedOf] and
 * `docs/plans/shared-access.md`): unlike [WaitingChanges], they are never taken, so that an
 * interface can tell the user why something made elsewhere is not here and will not be.
 */
public class RefusedChanges internal constructor(
    /** The document or the shared folder they change. */
    public val itemId: String,
    /** The replica that made them. */
    public val replica: String,
    /** The device of [replica], when the memberships known here name one (and agree on it). */
    public val device: String?,
    /** How many changes were refused since the engine started (at most a bound, see [SyncEngine.refusedOf]). */
    public val count: Int,
    public val reason: Reason
) {
    /** Why changes were refused. More reasons may be added: an interface tells the ones it does not know as refused. */
    public enum class Reason {
        /**
         * Changes of a document that no access lets their replica make: made after it could only
         * read, by another application, or after its role or grant was taken back. They are left
         * out, and the others of the same connection taken.
         */
        NOT_ALLOWED,

        /**
         * A change of a shared folder its sharing does not admit from their replica (its role, a
         * grant missing, a bound): the connection carrying it is closed, and nothing of it taken.
         */
        NOT_ADMITTED
    }
}
