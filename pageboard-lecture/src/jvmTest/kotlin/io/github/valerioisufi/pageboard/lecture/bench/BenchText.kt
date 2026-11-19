package io.github.valerioisufi.pageboard.lecture.bench

import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The edits that turn [a] into [b] (Levenshtein). */
internal fun <T> editDistance(a: List<T>, b: List<T>): Int {
    var previous = IntArray(b.size + 1) { it }
    var current = IntArray(b.size + 1)
    for (i in 1..a.size) {
        current[0] = i
        for (j in 1..b.size) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.size]
}

/** The words of [text] as a speech is compared: lower case, without punctuation (an apostrophe parts two words). */
internal fun spokenWords(text: String): List<String> =
    text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").split(' ').filter { it.isNotEmpty() }

/** The word error rate of [hypothesis] against [reference]. */
internal fun wordErrorRate(hypothesis: String, reference: String): Double {
    val words = spokenWords(reference)
    return if (words.isEmpty()) 0.0 else editDistance(spokenWords(hypothesis), words).toDouble() / words.size
}

/**
 * A formula in LaTeX as two are compared: without `$`, spaces, sizes and spacing commands, with the
 * braces of a single character dropped (`x_{1}` is `x_1`) and the synonyms the models write
 * (`\to`, `\leq`, `\ldots`) in one form.
 */
internal fun normalizeLatex(latex: String): String {
    var text = latex.replace("$", "").replace("\\[", "").replace("\\]", "").replace("\\(", "").replace("\\)", "")
    text = text.replace(Regex("""\\(left|right|displaystyle|big|Big|bigl|bigr|,|;|!|:|quad|qquad)(?![a-zA-Z])"""), "")
    text = text.replace(Regex("""\\(mathrm|text|mathit|operatorname|mathbf)\{([^{}]*)\}"""), "$2")
    text = text.replace(Regex("""\s+"""), "")
    text = text.replace(Regex("""\\(to|rightarrow)(?![a-zA-Z])"""), "\\\\rightarrow")
    text = text.replace(Regex("""\\leq(?![a-zA-Z])"""), "\\\\le").replace(Regex("""\\geq(?![a-zA-Z])"""), "\\\\ge")
    text = text.replace(Regex("""\\neq(?![a-zA-Z])"""), "\\\\ne")
    text = text.replace(Regex("""\\(ldots|cdots|dots)(?![a-zA-Z])"""), "...")
    // x_{1} as x_1, until nothing changes (nested single characters)
    while (true) {
        val next = text.replace(Regex("""([_^])\{(\\?[^{}\\])\}"""), "$1$2")
        if (next == text) break
        text = next
    }
    return text
}

/** The characters of [hypothesis] wrong against [reference], as a fraction of the reference. */
internal fun characterErrorRate(hypothesis: String, reference: String): Double =
    if (reference.isEmpty()) (if (hypothesis.isEmpty()) 0.0 else 1.0)
    else editDistance(hypothesis.toList(), reference.toList()).toDouble() / reference.length

/** The words of a text as SQLite FTS5 reads them (`unicode61 remove_diacritics 2`): lower case, without accents. */
internal fun indexWords(text: String): List<String> =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

/**
 * Whether [value] follows [schema] exactly (the part of JSON Schema the assistant uses: types,
 * properties, required, items, enum, minimum and maximum): `null` when it does, what is wrong
 * otherwise.
 */
internal fun schemaError(value: JsonElement, schema: JsonObject, path: String = "$"): String? {
    val type = schema["type"]?.jsonPrimitive?.content
    schema["enum"]?.jsonArray?.let { values ->
        if (value !is JsonPrimitive || values.none { it.jsonPrimitive.content == value.content }) return "$path is not one of the values allowed"
    }
    when (type) {
        "object" -> {
            if (value !is JsonObject) return "$path is not an object"
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            for (required in schema["required"]?.jsonArray.orEmpty()) {
                if (required.jsonPrimitive.content !in value) return "$path has no ${required.jsonPrimitive.content}"
            }
            for ((key, element) in value) {
                val property = properties[key]?.jsonObject ?: return "$path has the key $key, not in the schema"
                if (element is JsonNull) continue
                schemaError(element, property, "$path.$key")?.let { return it }
            }
        }
        "array" -> {
            if (value !is JsonArray) return "$path is not an array"
            val items = schema["items"]?.jsonObject
            if (items != null) value.forEachIndexed { i, element -> schemaError(element, items, "$path[$i]")?.let { return it } }
        }
        "string" -> if (value !is JsonPrimitive || !value.isString) return "$path is not a string"
        "integer" -> {
            if (value !is JsonPrimitive || value.isString || value.longOrNull == null) return "$path is not an integer"
            schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if (value.longOrNull!! < it) return "$path is below its minimum" }
            schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { if (value.longOrNull!! > it) return "$path is above its maximum" }
        }
        "number" -> if (value !is JsonPrimitive || value.isString || value.doubleOrNull == null) return "$path is not a number"
        "boolean" -> if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) return "$path is not a boolean"
    }
    return null
}

/** [text] parsed as JSON, `null` when it is not JSON. */
internal fun parseJsonOrNull(text: String): JsonElement? = try {
    Json.parseToJsonElement(text)
} catch (_: Exception) {
    null
}
