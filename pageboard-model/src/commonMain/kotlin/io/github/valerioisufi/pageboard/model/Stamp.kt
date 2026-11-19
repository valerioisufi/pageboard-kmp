package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.change.HybridClock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A hybrid logical clock timestamp: when a change was made ([time], wall clock milliseconds,
 * and [counter] for the changes of the same millisecond) and by which [replica] (a device).
 *
 * Stamps are totally ordered (the replica breaks the ties) and a change made after seeing
 * another one always has a greater stamp (see `HybridClock`): the most recent value of a piece of
 * the document wins on every device (last-writer-wins).
 *
 * Written as `time.counter.replica` (time and counter in fixed width hexadecimal), so that the
 * text order is the stamp order.
 */
@Serializable(with = StampSerializer::class)
public data class Stamp(val time: Long, val counter: Int, val replica: String) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int =
        compareValuesBy(this, other, { it.time }, { it.counter }, { it.replica })

    override fun toString(): String = time.toString(16).padStart(12, '0') + "." + counter.toString(16).padStart(8, '0') + "." + replica

    public companion object {
        /** Before every stamp (what was never written). */
        public val ZERO: Stamp = Stamp(0L, 0, "")

        public fun parse(text: String): Stamp? {
            val parts = text.split('.', limit = 3)
            if (parts.size != 3) return null
            val time = parts[0].toLongOrNull(16) ?: return null
            val counter = parts[1].toIntOrNull(16) ?: return null
            return Stamp(time, counter, parts[2])
        }
    }
}

internal object StampSerializer : KSerializer<Stamp> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Stamp", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Stamp) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Stamp = Stamp.parse(decoder.decodeString()) ?: Stamp.ZERO
}
