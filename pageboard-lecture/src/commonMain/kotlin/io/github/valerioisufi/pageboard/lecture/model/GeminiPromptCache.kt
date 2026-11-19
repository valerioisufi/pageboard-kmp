package io.github.valerioisufi.pageboard.lecture.model

import io.github.valerioisufi.pageboard.lecture.gemini.CreateCachedContentRequest
import io.github.valerioisufi.pageboard.lecture.gemini.GeminiClient
import io.github.valerioisufi.pageboard.lecture.gemini.GeminiContent
import io.github.valerioisufi.pageboard.lecture.gemini.GeminiException
import io.github.valerioisufi.pageboard.lecture.gemini.GeminiPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The explicit cache of Gemini for the requests of one lecture. The system instruction and the
 * leading parts of a prompt that stay the same ([ModelPrompt.cachedParts]) are stored once and
 * kept [ttlSeconds] past every request; the requests send only the rest.
 *
 * A start that Gemini refuses (too short, a model without caches) is sent whole from then on. A
 * start that changes (another model, another course) gets a cache of its own, and the old one is
 * deleted. The cache is deleted when the lecture ends ([clear]); one left behind expires by itself.
 */
internal class GeminiPromptCache(private val client: GeminiClient, private val ttlSeconds: Int = DEFAULT_TTL_SECONDS) {
    /** A cache in use: [name] holds the start [key]. */
    private class Entry(val key: String, val name: String)

    /** The cache that opens a request, and the tokens billed to create it (0 when it was there already). */
    class Use(val name: String, val createdTokens: Long)

    private val mutex = Mutex()
    private var entry: Entry? = null
    private val refused = mutableSetOf<String>()

    /** The cache holding the start of [prompt] for [model], created or extended; `null` to send it whole. */
    suspend fun use(apiKey: String, prompt: ModelPrompt, model: String): Use? {
        if (prompt.cachedParts <= 0) return null
        val key = keyOf(prompt, model)
        return mutex.withLock {
            if (key in refused) return@withLock null
            entry?.takeIf { it.key == key }?.let { current ->
                try {
                    client.extendCache(apiKey, current.name, ttlSeconds)
                    return@withLock Use(current.name, 0)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Still there after a temporary error; expired otherwise, and created again
                    if (e !is GeminiException || e.isRetryable) return@withLock Use(current.name, 0)
                    entry = null
                }
            }
            entry?.let { old ->
                entry = null
                delete(apiKey, old.name)
            }
            try {
                val created = client.createCache(
                    apiKey,
                    CreateCachedContentRequest(
                        model = "models/" + model.removePrefix("models/").trim(),
                        contents = listOf(GeminiContent(role = "user", parts = prompt.parts.take(prompt.cachedParts).map(GeminiBackend::partOf))),
                        systemInstruction = GeminiContent(parts = listOf(GeminiPart.text(prompt.system))),
                        ttl = "${ttlSeconds}s"
                    )
                )
                entry = Entry(key, created.name)
                Use(created.name, created.usageMetadata?.totalTokenCount ?: 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Refused for good (too short, no cache for the model): not asked again
                if (e is GeminiException && !e.isRetryable && !e.isAuthenticationError) refused.add(key)
                null
            }
        }
    }

    /** Forgets the cache [name], which Gemini no longer has. */
    suspend fun forget(name: String) {
        mutex.withLock { if (entry?.name == name) entry = null }
    }

    /** Deletes the cache of the lecture, which is over. */
    suspend fun clear(apiKey: String) {
        val old = mutex.withLock { entry.also { entry = null } } ?: return
        delete(apiKey, old.name)
    }

    private suspend fun delete(apiKey: String, name: String) {
        try {
            client.deleteCache(apiKey, name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // It expires by itself
        }
    }

    private fun keyOf(prompt: ModelPrompt, model: String): String = buildString {
        append(model.removePrefix("models/").trim()).append('\u0000').append(prompt.system)
        for (part in prompt.parts.take(prompt.cachedParts)) {
            append('\u0000')
            when (part) {
                is PromptPart.Text -> append(part.text)
                is PromptPart.Image -> append("image ").append(part.jpeg.contentHashCode())
            }
        }
    }

    companion object {
        /** Ten minutes past every request: the requests come every few minutes. */
        const val DEFAULT_TTL_SECONDS = 600
    }
}
