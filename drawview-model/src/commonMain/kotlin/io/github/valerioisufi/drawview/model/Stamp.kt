package io.github.valerioisufi.drawview.model

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
data class Stamp(val time: Long, val counter: Int, val replica: String) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int =
        compareValuesBy(this, other, { it.time }, { it.counter }, { it.replica })

    override fun toString(): String = time.toString(16).padStart(12, '0') + "." + counter.toString(16).padStart(8, '0') + "." + replica

    companion object {
        /** Before every stamp (what was never written). */
        val ZERO = Stamp(0L, 0, "")

        fun parse(text: String): Stamp? {
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

/**
 * The stacking order of an element on its page: the stamp of the change that put it there and
 * its position in that change (elements added or moved later are on top). The same on every
 * device; written as `stamp/index` (text order = stacking order).
 */
@Serializable(with = ElementOrderSerializer::class)
data class ElementOrder(val stamp: Stamp, val index: Int) : Comparable<ElementOrder> {
    override fun compareTo(other: ElementOrder): Int =
        compareValuesBy(this, other, { it.stamp }, { it.index })

    override fun toString(): String = "$stamp/" + index.toString(16).padStart(8, '0')

    companion object {
        /** The order of an element not on a page yet (assigned when it is added). */
        val UNASSIGNED = ElementOrder(Stamp.ZERO, -1)

        fun parse(text: String): ElementOrder? {
            val slash = text.lastIndexOf('/')
            if (slash < 0) return null
            val stamp = Stamp.parse(text.substring(0, slash)) ?: return null
            val index = text.substring(slash + 1).toIntOrNull(16) ?: return null
            return ElementOrder(stamp, index)
        }
    }
}

internal object ElementOrderSerializer : KSerializer<ElementOrder> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ElementOrder", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ElementOrder) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): ElementOrder = ElementOrder.parse(decoder.decodeString()) ?: ElementOrder.UNASSIGNED
}

