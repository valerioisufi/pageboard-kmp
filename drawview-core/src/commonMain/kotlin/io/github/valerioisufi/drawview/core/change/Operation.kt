package io.github.valerioisufi.drawview.core.change

import io.github.valerioisufi.drawview.core.model.Stamp
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlinx.serialization.Serializable

/**
 * The hybrid logical clock of a [replica]: its stamps follow the wall clock and are greater
 * than every stamp it has seen ([observe]), even when the clocks of the devices differ.
 */
class HybridClock(val replica: String, private val wallClock: () -> Long = ::currentTimeMillis) {
    private var last = Stamp.ZERO

    /** A new stamp, greater than every stamp made or seen before. */
    fun next(): Stamp {
        val now = wallClock()
        last = if (now > last.time) Stamp(now, 0, replica) else Stamp(last.time, last.counter + 1, replica)
        return last
    }

    /** Takes into account a stamp made elsewhere: the next stamps are greater. */
    fun observe(stamp: Stamp) {
        if (stamp.time > last.time || (stamp.time == last.time && stamp.counter > last.counter)) {
            last = Stamp(stamp.time, stamp.counter, replica)
        }
    }
}

/** A change as made by [replica]: its [sequence] number among the changes of that replica. */
@Serializable
data class OperationId(val replica: String, val sequence: Long)

/**
 * A change of a document as it is logged and exchanged: who made it, when, and the change.
 */
@Serializable
data class Operation(val id: OperationId, val stamp: Stamp, val change: DocumentChange)
