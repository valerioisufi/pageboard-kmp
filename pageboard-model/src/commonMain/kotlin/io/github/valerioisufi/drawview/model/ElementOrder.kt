package io.github.valerioisufi.drawview.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The position of an element in the stack of its page: a [key] compared as text, the greater on
 * top, the same on every device (ties are broken by the identifier of the element, see
 * [DrawPage.stack]). A key can always be made between two others ([between]), so that an element
 * is put anywhere in the stack without changing the keys of the others.
 *
 * The keys are of printable ASCII (`!` to `~`), at most [MAX_LENGTH] characters, never ending
 * with `!`, and start with a letter from `G`:
 * - the orders written before the content of a page was one stack, `stamp/index` (the stamp of
 *   the change that put the element on its page, its position in that change), start with a
 *   hexadecimal digit: an element reads them in the layer of its kind ([inLayer]: the letter of
 *   its kind followed by the order as it was), so that the pages written before keep their look,
 *   each kind above the previous ones;
 * - the change putting elements on top gives them `V`, its stamp and their position in it
 *   ([onTop]): above every key made before it, on every device;
 * - the keys between two others are made by the editor ([between]) and carried by the changes.
 *
 * The versions before the one stack read a key starting with a letter as no order: never as a
 * stamp.
 */
@Serializable(with = ElementOrderSerializer::class)
public class ElementOrder private constructor(
    /** The text of the order, compared as text. */
    public val key: String
) : Comparable<ElementOrder> {

    /** Whether it is an order written before the one stack (`stamp/index`), not read in a layer yet. */
    internal val isLegacy: Boolean get() = key.isNotEmpty() && key[0].isHexDigit()

    /** The stamp of an order written before the one stack, `null` for the other orders. */
    internal val legacyStamp: Stamp?
        get() = if (isLegacy) Stamp.parse(key.substring(0, key.lastIndexOf('/'))) else null

    /** Whether it is well formed (see [ElementOrder]): any order received must be. */
    internal val isWellFormed: Boolean get() = isWellFormed(key)

    /**
     * This order in the layer [layer] (the letter of a kind, see [layerOf]) when it was written
     * before the one stack; itself otherwise.
     */
    internal fun inLayer(layer: Char): ElementOrder = if (isLegacy) ElementOrder(layer + key) else this

    /** Whether it could be given by a change of stamp [stamp]: not above the greatest of its keys. */
    internal fun isNotAfter(stamp: Stamp): Boolean = isLegacy || key <= TOP_LAYER + stamp.toString() + "/~"

    override fun compareTo(other: ElementOrder): Int = key.compareTo(other.key)

    override fun equals(other: Any?): Boolean = other is ElementOrder && key == other.key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = key

    public companion object {
        /** The longest key: far more than the editor makes, little to keep. */
        public const val MAX_LENGTH: Int = 256

        /** The order of an element not on a page yet (assigned when it is added). */
        public val UNASSIGNED: ElementOrder = ElementOrder("")

        /** The order of [text] (see [ElementOrder]), or `null` when it is not one. */
        public fun parse(text: String): ElementOrder? = if (isWellFormed(text)) ElementOrder(text) else null

        /**
         * The order of the element at [index] in a change of stamp [stamp] putting elements on
         * top: above every order made before that change, on every device.
         */
        public fun onTop(stamp: Stamp, index: Int): ElementOrder = ElementOrder(TOP_LAYER + legacyText(stamp, index))

        /**
         * An order above [below] (`null`: the bottom of the stack) and under [above], two orders
         * of the elements of a page (read in their layers).
         *
         * @throws IllegalArgumentException when [below] is not under [above].
         */
        public fun between(below: ElementOrder?, above: ElementOrder): ElementOrder = between(below, above, 1)[0]

        /**
         * [count] increasing orders above [below] (`null`: the bottom of the stack) and under
         * [above], made by halves so that they stay short.
         *
         * @throws IllegalArgumentException when [below] is not under [above].
         */
        public fun between(below: ElementOrder?, above: ElementOrder, count: Int): List<ElementOrder> {
            val low = below?.key ?: FLOOR
            require(low < above.key) { "$below is not under $above" }
            val keys = ArrayList<String>(count)
            fill(low, above.key, count, keys)
            return keys.map(::ElementOrder)
        }

        /**
         * [count] increasing short orders under every order on top (a stack keyed again when its
         * keys grew too long): `H` and a number of fixed width, without the lowest digit.
         */
        internal fun spread(count: Int): List<ElementOrder> {
            val base = BASE - 1
            var width = 1
            var room = base.toLong()
            while (room < count) {
                width++
                room *= base
            }
            return List(count.coerceAtLeast(0)) { i ->
                var n = i
                val digits = CharArray(width)
                for (d in width - 1 downTo 0) {
                    digits[d] = digit(1 + n % base)
                    n /= base
                }
                ElementOrder("H" + digits.concatToString())
            }
        }

        /** The layer of the kind of [element] (see [inLayer]). */
        internal fun layerOf(element: DrawElement): Char = when (element) {
            is DrawPdf -> PDF_LAYER
            is DrawTextMarkup -> MARKUP_LAYER
            is DrawImage -> IMAGE_LAYER
            is DrawCustomElement -> CUSTOM_LAYER
            is DrawText -> TEXT_LAYER
            is DrawStroke -> STROKE_LAYER
        }

        /**
         * The order of the element [index] among the elements of the layer [layer] of a page built
         * outside of the changes (see [DrawPage.add]): the order such a page has always had.
         */
        internal fun built(layer: Char, index: Int): ElementOrder = ElementOrder(layer + legacyText(Stamp.ZERO, index))

        /** Whether [order] is one of [built] in the layer [layer]. */
        internal fun isBuilt(order: ElementOrder, layer: Char): Boolean = order.key.startsWith(layer + BUILT_PREFIX)

        internal const val PDF_LAYER = 'P'
        internal const val MARKUP_LAYER = 'Q'
        internal const val IMAGE_LAYER = 'R'
        internal const val CUSTOM_LAYER = 'S'
        internal const val TEXT_LAYER = 'T'
        internal const val STROKE_LAYER = 'U'
        private const val TOP_LAYER = 'V'

        /** Under every key (never one itself). */
        private const val FLOOR = "G"

        private val BUILT_PREFIX = Stamp.ZERO.toString() + "/"

        private const val MIN = '!'
        private const val MAX = '~'
        private const val BASE = MAX.code - MIN.code + 1

        private fun digit(value: Int): Char = (MIN.code + value).toChar()

        private fun valueOf(c: Char): Int = c.code - MIN.code

        /** `stamp/index`, as the orders were written before the one stack. */
        private fun legacyText(stamp: Stamp, index: Int): String = "$stamp/" + index.toString(16).padStart(8, '0')

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

        private fun isWellFormed(key: String): Boolean {
            if (key.isEmpty() || key.length > MAX_LENGTH || key.last() == MIN) return false
            if (key.any { it < MIN || it > MAX }) return false
            val first = key[0]
            if (first.isHexDigit()) {
                val slash = key.lastIndexOf('/')
                return slash > 0 && Stamp.parse(key.substring(0, slash)) != null &&
                    key.length - slash - 1 == 8 && key.substring(slash + 1).toIntOrNull(16) != null
            }
            return first in 'G'..'Z'
        }

        /** Adds to [into] [count] increasing keys strictly between [a] and [b], by halves. */
        private fun fill(a: String, b: String, count: Int, into: MutableList<String>) {
            if (count <= 0) return
            val middle = midpoint(a, b)
            val under = (count - 1) / 2
            fill(a, middle, under, into)
            into.add(middle)
            fill(middle, b, count - 1 - under, into)
        }

        /**
         * A key strictly between [a] and [b] (`null`: no bound above), keys never ending with the
         * lowest digit (a fraction in base 94, as `FractionalIndex` in base 62).
         */
        private fun midpoint(a: String, b: String?): String {
            if (b != null) {
                // The common prefix (a is padded with the lowest digit)
                var n = 0
                while (n < b.length && a.getOrElse(n) { MIN } == b[n]) n++
                if (n > 0) return b.substring(0, n) + midpoint(if (n < a.length) a.substring(n) else "", b.substring(n))
            }
            val digitA = if (a.isNotEmpty()) valueOf(a[0]) else 0
            val digitB = if (b != null) valueOf(b[0]) else BASE
            if (digitB - digitA > 1) return digit((digitA + digitB) / 2).toString()
            // Consecutive digits
            if (b != null && b.length > 1) return b.substring(0, 1)
            return digit(digitA) + midpoint(if (a.isNotEmpty()) a.substring(1) else "", null)
        }
    }
}

/** Writes an order as its key; reads a text that is not one as [ElementOrder.UNASSIGNED]. */
internal object ElementOrderSerializer : KSerializer<ElementOrder> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ElementOrder", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ElementOrder) = encoder.encodeString(value.key)
    override fun deserialize(decoder: Decoder): ElementOrder = ElementOrder.parse(decoder.decodeString()) ?: ElementOrder.UNASSIGNED
}
