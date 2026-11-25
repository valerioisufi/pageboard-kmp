package io.github.valerioisufi.pageboard.sync

/**
 * Changes of another device that wait on this one, and why (see [SyncEngine.waitingOf] and
 * `docs/plans/shared-access.md`): a change is kept until it can be taken, never applied before, so
 * that an interface can tell the user why something made elsewhere is not here yet.
 */
public class WaitingChanges internal constructor(
    /** The document or the shared folder they change. */
    public val itemId: String,
    /** The replica that made them. */
    public val replica: String,
    /** The device of [replica], when the memberships known here name one (and agree on it). */
    public val device: String?,
    /** How many changes wait (on the connection holding the most of them). */
    public val count: Int,
    public val reason: Reason
) {
    /** Why changes wait. More reasons may be added: an interface tells the ones it does not know as waiting. */
    public enum class Reason {
        /**
         * No membership known here names their replica yet: a device admitted meanwhile, or one of
         * an access this device is not in, whose membership has not come (or is not anchored in
         * what this device trusts).
         */
        UNKNOWN_DEVICE,

        /** The accesses of the item name their replica for different devices: taken as neither. */
        DEVICES_DISAGREE,

        /** They follow changes of the same replica not received yet (a change of a shared folder). */
        EARLIER_MISSING
    }
}
