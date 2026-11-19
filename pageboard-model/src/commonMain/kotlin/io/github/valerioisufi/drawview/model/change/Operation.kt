package io.github.valerioisufi.drawview.model.change

import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlinx.serialization.Serializable

/**
 * The hybrid logical clock of a [replica]: its stamps follow the wall clock and are greater
 * than every stamp it has seen ([observe]), even when the clocks of the devices differ.
 */
public class HybridClock(public val replica: String, private val wallClock: () -> Long = ::currentTimeMillis) {
    private var last = Stamp.ZERO

    /** A new stamp, greater than every stamp made or seen before. */
    public fun next(): Stamp {
        val now = wallClock()
        last = when {
            now > last.time -> Stamp(now, 0, replica)
            last.counter < Int.MAX_VALUE -> Stamp(last.time, last.counter + 1, replica)
            // The counter at its greatest (a stamp seen with it): the next millisecond, rather
            // than a negative counter (a smaller stamp)
            else -> Stamp(last.time + 1, 0, replica)
        }
        return last
    }

    /** Takes into account a stamp made elsewhere: the next stamps are greater. */
    public fun observe(stamp: Stamp) {
        if (stamp.time > last.time || (stamp.time == last.time && stamp.counter > last.counter)) {
            last = Stamp(stamp.time, stamp.counter, replica)
        }
    }

    /**
     * Whether [stamp], made elsewhere, may be observed: at most [MAX_AHEAD_MS] ahead of the wall
     * clock here, and with no negative part. A stamp further ahead (of a clock set wrong, or of a
     * member stamping its changes as it likes) would push this clock with it, and its change would
     * win over the ones made after it.
     */
    public fun admits(stamp: Stamp): Boolean =
        stamp.time >= 0 && stamp.counter >= 0 && stamp.time <= wallClock() + MAX_AHEAD_MS

    public companion object {
        /**
         * How far ahead of the wall clock here a stamp made elsewhere may be (see [admits]): an
         * hour, far more than the clocks set by the network ever differ.
         */
        public const val MAX_AHEAD_MS: Long = 60 * 60 * 1000L
    }
}

/** A change as made by [replica]: its [sequence] number among the changes of that replica. */
@Serializable
public data class OperationId(val replica: String, val sequence: Long)

/**
 * A change of a document as it is logged and exchanged: who made it, when, and the change.
 */
@Serializable
public data class Operation(val id: OperationId, val stamp: Stamp, val change: DocumentChange)
