package io.github.valerioisufi.pageboard.model

/**
 * Keys ordering items by their text order, a key can always be made between two others: an item
 * is inserted or moved by giving it a new key, without changing the keys of the other items (the
 * order of the pages of a document, the same on every device).
 *
 * A key is a fraction in (0, 1) written in base 62 without its leading "0." (e.g. "V" is about
 * 0.5); keys never end with the zero digit, so that there is always room before them.
 */
public object FractionalIndex {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE = 62

    /**
     * A key greater than [before] and smaller than [after] (`null`: no bound on that side).
     *
     * @throws IllegalArgumentException when [before] is not smaller than [after].
     */
    public fun between(before: String?, after: String?): String {
        val a = before.orEmpty()
        require(after == null || a < after) { "$before is not before $after" }
        return midpoint(a, after)
    }

    /** [count] increasing keys after [before] (e.g. pages appended at the end). */
    public fun after(before: String?, count: Int): List<String> {
        val keys = mutableListOf<String>()
        var last = before
        repeat(count) {
            val key = between(last, null)
            keys.add(key)
            last = key
        }
        return keys
    }

    public fun isValidKey(key: String): Boolean = key.isNotEmpty() && key.last() != '0' && key.all { it in DIGITS }

    private fun digit(c: Char): Int = DIGITS.indexOf(c).also { require(it >= 0) { "$c is not a key digit" } }

    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            // The common prefix (a is padded with zeros)
            var n = 0
            while (n < b.length && a.getOrElse(n) { '0' } == b[n]) n++
            if (n > 0) return b.substring(0, n) + midpoint(if (n < a.length) a.substring(n) else "", b.substring(n))
        }
        val digitA = if (a.isNotEmpty()) digit(a[0]) else 0
        val digitB = if (b != null) digit(b[0]) else BASE
        if (digitB - digitA > 1) return DIGITS[(digitA + digitB) / 2].toString()
        // Consecutive digits
        if (b != null && b.length > 1) return b.substring(0, 1)
        return DIGITS[digitA] + midpoint(if (a.isNotEmpty()) a.substring(1) else "", null)
    }

    /**
     * New keys for the items whose position changes when [keys] are put in this order: the items
     * of the longest increasing sequence keep their key, the others get a key between their new
     * neighbours.
     *
     * @return the index of every item of [keys] that gets a new key, with that key.
     */
    public fun reorder(keys: List<String>): Map<Int, String> {
        val kept = longestIncreasing(keys)
        val result = mutableMapOf<Int, String>()
        var previous: String? = null
        for (i in keys.indices) {
            if (i in kept) {
                previous = keys[i]
                continue
            }
            val next = (i + 1 until keys.size).firstOrNull { it in kept }?.let { keys[it] }
            val key = between(previous, next)
            result[i] = key
            previous = key
        }
        return result
    }

    /** Indexes of a longest strictly increasing subsequence of [keys]. */
    private fun longestIncreasing(keys: List<String>): Set<Int> {
        val tails = mutableListOf<Int>()
        val parent = IntArray(keys.size) { -1 }
        for (i in keys.indices) {
            var low = 0
            var high = tails.size
            while (low < high) {
                val mid = (low + high) / 2
                if (keys[tails[mid]] < keys[i]) low = mid + 1 else high = mid
            }
            if (low > 0) parent[i] = tails[low - 1]
            if (low == tails.size) tails.add(i) else tails[low] = i
        }
        val result = mutableSetOf<Int>()
        var k = tails.lastOrNull() ?: -1
        while (k >= 0) {
            result.add(k)
            k = parent[k]
        }
        return result
    }
}
