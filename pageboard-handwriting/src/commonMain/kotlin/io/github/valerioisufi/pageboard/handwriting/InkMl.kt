package io.github.valerioisufi.pageboard.handwriting

import io.github.valerioisufi.pageboard.model.StrokePoint

/**
 * An ink of an InkML file: its strokes and its annotations (`label`, `normalizedLabel`, ...).
 */
public class InkMlInk(public val sample: InkSample, public val annotations: Map<String, String>) {
    /** The text it holds, from its annotation `label` (or `normalizedLabel`). */
    public val label: String? get() = annotations["label"] ?: annotations["normalizedLabel"]
}

/**
 * Ink as InkML (W3C), one ink a file as in MathWriting: a trace a stroke, each sample `x y t`
 * (millimeters, milliseconds). The format the benches of the readers share between platforms.
 */
public object InkMl {
    /** [sample] as an InkML document, with [annotations] (e.g. `label` to the text it holds). */
    public fun write(sample: InkSample, annotations: Map<String, String> = emptyMap()): String = buildString {
        append("<ink xmlns=\"http://www.w3.org/2003/InkML\">\n")
        for ((type, value) in annotations) append("<annotation type=\"").append(escape(type)).append("\">").append(escape(value)).append("</annotation>\n")
        for (stroke in sample.strokes) {
            append("<trace>")
            stroke.forEachIndexed { i, p ->
                if (i > 0) append(", ")
                append(format(p.x)).append(' ').append(format(p.y)).append(' ').append(p.timestampMs)
            }
            append("</trace>\n")
        }
        append("</ink>\n")
    }

    /** The ink of an InkML document: its traces in order (`x y`, then `t` if present), its annotations. */
    public fun read(text: String): InkMlInk {
        val annotations = ANNOTATION.findAll(text).associate { unescape(it.groupValues[1]) to unescape(it.groupValues[2]) }
        val strokes = TRACE.findAll(text).map { trace ->
            trace.groupValues[1].split(',').mapNotNull { point ->
                val values = point.trim().split(SPACES).filter { it.isNotEmpty() }
                val x = values.getOrNull(0)?.toFloatOrNull()
                val y = values.getOrNull(1)?.toFloatOrNull()
                if (x == null || y == null) null else StrokePoint(x, y, timestampMs = values.getOrNull(2)?.toDoubleOrNull()?.toLong() ?: 0L)
            }
        }.filter { it.isNotEmpty() }.toList()
        return InkMlInk(InkSample(strokes), annotations)
    }

    private val ANNOTATION = Regex("""<annotation[^>]*type="([^"]*)"[^>]*>([^<]*)</annotation>""")
    private val TRACE = Regex("""<trace[^>]*>([^<]*)</trace>""")
    private val SPACES = Regex("\\s+")

    private fun format(value: Float): String {
        val rounded = kotlin.math.round(value * 1000f) / 1000f
        val text = rounded.toString()
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
