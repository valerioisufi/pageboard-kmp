package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.ml.GenerationPart
import io.github.valerioisufi.pageboard.ml.GenerationRequest
import io.github.valerioisufi.pageboard.ml.GeneratorOptions
import io.github.valerioisufi.pageboard.ml.InstalledModel
import kotlinx.coroutines.CancellationException

/**
 * Generates concise topic titles for finished lectures using the on-device generation model
 * shared via [DeviceModels] (`LoadedModels`), keeping the model warm across batch exports.
 */
internal object LectureTitleGenerator {
    private const val MAX_CONTEXT_CHARS = 1500
    private const val TRANSCRIPT_WITH_CONTEXT_CHARS = 2500
    private const val TRANSCRIPT_ONLY_CHARS = 4000
    private const val MAX_TITLE_CHARS = 60
    private const val KEEP_LOADED_MS = 60_000L

    private const val SYSTEM_PROMPT =
        "Write a concise 2 to 6 word descriptive title summarizing the main topic of this lecture, " +
            "in the same language as the lecture. Output only the title, without quotes, labels, bullets, or trailing punctuation."

    private val PREFIXES = listOf("title:", "titolo:", "topic:", "argomento:", "subject:", "tema:")

    /** First installed on-device generation model from [deviceModels], or `null` if unavailable. */
    fun installedModel(deviceModels: DeviceModels?): InstalledModel? {
        if (deviceModels == null || !deviceModels.isAvailable) return null
        return deviceModels.generators.firstNotNullOfOrNull { deviceModels.installed[it] }
    }

    /** Generates a short cleaned title for [record] on device, or `null` if unavailable or empty. */
    suspend fun generate(record: LectureRecord, deviceModels: DeviceModels?): String? {
        val models = deviceModels ?: return null
        val model = installedModel(models) ?: return null
        val input = buildPromptInput(record).takeIf { it.isNotBlank() } ?: return null
        val request = GenerationRequest(
            system = SYSTEM_PROMPT,
            parts = listOf(GenerationPart.Text(input)),
            maxOutputTokens = 32,
            temperature = 0.2f
        )
        val raw = try {
            models.loaded.withGenerator(
                model = model,
                options = GeneratorOptions(maxTokens = 4096, images = false, audio = false),
                keepLoadedMillis = KEEP_LOADED_MS
            ) { generator ->
                generator.generate(request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return cleanTitle(raw).takeIf { it.isNotBlank() }
    }

    /** Builds bounded prompt text from [record]'s outline/review and sampled transcript. */
    fun buildPromptInput(record: LectureRecord): String {
        val structured = record.outline.trim().take(MAX_CONTEXT_CHARS).ifBlank {
            record.finalReview?.trim().orEmpty().take(MAX_CONTEXT_CHARS)
        }
        val transcriptBudget = if (structured.isNotBlank()) TRANSCRIPT_WITH_CONTEXT_CHARS else TRANSCRIPT_ONLY_CHARS
        val transcriptExcerpt = sampleTranscript(record.transcript, transcriptBudget)
        return buildString {
            if (record.documentTitle.isNotBlank()) {
                append("Course: ").append(record.documentTitle.trim()).append("\n\n")
            }
            if (structured.isNotBlank()) {
                append("Outline:\n").append(structured).append("\n\n")
            }
            if (transcriptExcerpt.isNotBlank()) {
                append("Transcript:\n").append(transcriptExcerpt)
            }
        }.trim()
    }

    /** Extracts a fallback topic from [record]'s review heading or `currentTopic` without invoking AI. */
    fun fallbackTitle(record: LectureRecord): String {
        val heading = record.finalReview
            ?.lineSequence()
            ?.map { it.trimStart() }
            ?.firstOrNull { it.startsWith('#') }
            ?.let(::cleanTitle)
            .orEmpty()
        if (heading.isNotBlank()) return heading
        return cleanTitle(record.currentTopic)
    }

    /** Normalizes [raw] model output into a short single-line title suitable for file names. */
    fun cleanTitle(raw: String): String {
        var line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        var changed: Boolean
        do {
            val before = line
            line = line.trimStart('#', '*', '-', '+', '>', '•', ' ', '\t')
                .trim('"', '\'', '«', '»', '`', '*', '_', ' ')
            for (prefix in PREFIXES) {
                if (line.startsWith(prefix, ignoreCase = true)) {
                    line = line.substring(prefix.length).trim()
                }
            }
            changed = line != before
        } while (changed)
        line = line.replace(Regex("\\s+"), " ").trimEnd('.', ':', ';', '!', '?', ',', ' ')
        if (line.length <= MAX_TITLE_CHARS) return line
        val clipped = line.take(MAX_TITLE_CHARS)
        val atWord = clipped.substringBeforeLast(' ', "")
        return (if (atWord.length >= MAX_TITLE_CHARS / 2) atWord else clipped).trimEnd('.', ':', ';', '!', '?', ',', ' ')
    }

    private fun sampleTranscript(segments: List<TranscriptSegment>, maxChars: Int): String {
        val texts = segments.mapNotNull { it.text.trim().takeIf(String::isNotEmpty) }
        if (texts.isEmpty()) return ""
        val total = texts.sumOf { it.length + 1 }
        if (total <= maxChars) return texts.joinToString(" ")
        if (texts.size <= 3) return texts.joinToString(" ").take(maxChars).trimEnd()

        val sliceBudget = maxChars / 3
        val start = takeWithinBudget(texts, 0 until texts.size, sliceBudget)
        val midStart = (texts.size / 3).coerceAtLeast(1)
        val mid = takeWithinBudget(texts, midStart until texts.size, sliceBudget)
        val tail = takeReversedWithinBudget(texts, sliceBudget)
        return listOf(start, mid, tail).filter { it.isNotBlank() }.distinct().joinToString(" … ").take(maxChars)
    }

    private fun takeWithinBudget(texts: List<String>, indices: IntRange, budget: Int): String {
        val out = StringBuilder()
        for (i in indices) {
            val t = texts[i]
            if (out.isNotEmpty() && out.length + 1 + t.length > budget) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(t)
            if (out.length >= budget) break
        }
        return out.toString().take(budget)
    }

    private fun takeReversedWithinBudget(texts: List<String>, budget: Int): String {
        val chosen = ArrayList<String>()
        var used = 0
        for (i in texts.indices.reversed()) {
            val t = texts[i]
            val add = if (chosen.isEmpty()) t.length else t.length + 1
            if (chosen.isNotEmpty() && used + add > budget) break
            chosen.add(t)
            used += add
            if (used >= budget) break
        }
        return chosen.asReversed().joinToString(" ").takeLast(budget)
    }
}
