package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.ml.GenerationPart
import io.github.valerioisufi.pageboard.ml.GenerationRequest
import io.github.valerioisufi.pageboard.ml.GeneratorOptions
import kotlinx.coroutines.CancellationException

/**
 * The next words of a text being written, from the generation model of the device (see
 * `docs/plans/writing-suggestions-more.md`): a few words, the paragraph before the caret only, the
 * model kept loaded a while after the last request, since a writing asks again and again.
 */
internal object NextWordsWriter {
    private const val KEEP_LOADED_MS = 120_000L

    /** The end of the paragraph read, in characters. */
    private const val MAX_CONTEXT_CHARS = 600

    /** The fewest words written before the model is asked. */
    private const val MIN_WORDS = 3

    /** The most words suggested. */
    const val MAX_WORDS = 8

    private const val SYSTEM_PROMPT =
        "You complete the notes a student is writing. Continue the text with its next few words (at most 8), " +
            "in the same language, style and notation. Answer with the words only: no quotes, no explanation, " +
            "nothing of the text again."

    /** The next words of [textBefore] from the model of [deviceModels]; `null` when there is none, or nothing to say. */
    suspend fun nextWords(textBefore: String, deviceModels: DeviceModels?): String? {
        val models = deviceModels ?: return null
        val model = LectureTitleGenerator.installedModel(models) ?: return null
        val context = textBefore.takeLast(MAX_CONTEXT_CHARS).trim()
        if (context.split(' ', '\n').count { it.isNotBlank() } < MIN_WORDS) return null
        val request = GenerationRequest(
            system = SYSTEM_PROMPT,
            parts = listOf(GenerationPart.Text(context)),
            maxOutputTokens = 16,
            temperature = 0f
        )
        val answer = try {
            models.loaded.withGenerator(model, GeneratorOptions(maxTokens = 1024), KEEP_LOADED_MS) { it.generate(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return cleaned(answer, context)
    }

    /** [answer] as words to put after [context]: its first line, without quotes, at most [MAX_WORDS]; `null` for nothing new. */
    fun cleaned(answer: String, context: String): String? {
        val line = answer.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '\'', '“', '”', '«', '»', '`')?.trim() ?: return null
        if (line.isEmpty() || context.endsWith(line)) return null
        // The words of the text the model wrote again
        val repeated = line.split(' ').takeWhile { it.isNotEmpty() }.let { words ->
            (words.size downTo 1).firstOrNull { n -> context.endsWith(words.take(n).joinToString(" ")) } ?: 0
        }
        val words = line.split(Regex("\\s+")).drop(repeated).take(MAX_WORDS)
        return words.joinToString(" ").takeIf { it.isNotBlank() }
    }
}
