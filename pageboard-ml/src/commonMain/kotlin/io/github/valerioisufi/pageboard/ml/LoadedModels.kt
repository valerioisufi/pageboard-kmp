package io.github.valerioisufi.pageboard.ml

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The models in memory (Pure Fabrication): the only one that loads and closes the models of the
 * [runtime].
 *
 * * A model is loaded by its first request and answers one request at a time.
 * * After a request it stays loaded for the time asked (`keepLoadedMillis`), so that the requests
 *   of a lecture, every minute, do not load it again; 0 closes it at once.
 * * At most [maxGenerators] generation models and [maxEmbedders] embedding models are loaded at
 *   once: a new one takes the place of the one used least recently, once its request is done.
 *
 * A request must not ask for another model inside its block: with one place, it would wait for
 * itself.
 */
public class LoadedModels(
    private val runtime: ModelRuntime,
    private val maxGenerators: Int = 1,
    private val maxEmbedders: Int = 1,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    init {
        require(maxGenerators >= 1 && maxEmbedders >= 1) { "At least one model of each kind" }
    }

    private class Key(val modelId: String, val path: String, val kind: ModelKind, val options: Any) {
        override fun equals(other: Any?): Boolean =
            other is Key && other.modelId == modelId && other.path == path && other.kind == kind && other.options == options

        override fun hashCode(): Int = ((modelId.hashCode() * 31 + path.hashCode()) * 31 + kind.hashCode()) * 31 + options.hashCode()
    }

    private class Entry(val key: Key, val instance: AutoCloseable) {
        /** Held by its request, and by its closing. */
        val use = Mutex()

        // Under the lock of LoadedModels
        var users = 0
        var lastUsed = 0L
        var expiry: Job? = null

        /** Under [use]. */
        var isClosed = false
    }

    private val lock = Mutex()
    private val entries = mutableListOf<Entry>()
    private var tick = 0L

    /** Changes at every model closed: the requests waiting for a place look again. */
    private val closings = MutableStateFlow(0L)
    private val loadedState = MutableStateFlow<Set<String>>(emptySet())

    /** The ids of the models loaded. */
    public val loaded: StateFlow<Set<String>> = loadedState.asStateFlow()

    /**
     * Runs [block] with [model] loaded to generate text, loading it if needed.
     *
     * @throws ModelException when it cannot be loaded.
     */
    public suspend fun <T> withGenerator(
        model: InstalledModel,
        options: GeneratorOptions = GeneratorOptions(),
        keepLoadedMillis: Long = 0,
        block: suspend (Generator) -> T
    ): T = use(Key(model.spec.id, model.path, ModelKind.GENERATION, options), keepLoadedMillis, { runtime.generator(model, options) }) {
        block(it as Generator)
    }

    /**
     * Runs [block] with [model] loaded to compute embeddings, loading it if needed.
     *
     * @throws ModelException when it cannot be loaded.
     */
    public suspend fun <T> withEmbedder(
        model: InstalledModel,
        options: EmbedderOptions = EmbedderOptions(),
        keepLoadedMillis: Long = 0,
        block: suspend (Embedder) -> T
    ): T = use(Key(model.spec.id, model.path, ModelKind.EMBEDDING, options), keepLoadedMillis, { runtime.embedder(model, options) }) {
        block(it as Embedder)
    }

    /** Closes the model [modelId] (e.g. at the end of a lecture), once its request is done. */
    public suspend fun unload(modelId: String) {
        for (entry in lock.withLock { entries.filter { it.key.modelId == modelId } }) close(entry)
    }

    /** Closes every model, once their requests are done. */
    public suspend fun unloadAll() {
        for (entry in lock.withLock { entries.toList() }) close(entry)
    }

    private suspend fun <T> use(key: Key, keepLoadedMillis: Long, load: suspend () -> AutoCloseable, block: suspend (AutoCloseable) -> T): T {
        while (true) {
            val entry = acquire(key, load)
            try {
                entry.use.withLock {
                    // Closed by unload between its acquisition and its turn: loaded again
                    if (!entry.isClosed) return block(entry.instance)
                }
            } finally {
                withContext(NonCancellable) { release(entry, keepLoadedMillis) }
            }
        }
    }

    private suspend fun acquire(key: Key, load: suspend () -> AutoCloseable): Entry {
        while (true) {
            val seen = closings.value
            val evicting = lock.withLock {
                entries.firstOrNull { it.key == key }?.let { entry ->
                    entry.users++
                    entry.expiry?.cancel()
                    entry.expiry = null
                    return entry
                }
                val sameKind = entries.filter { it.key.kind == key.kind }
                val max = if (key.kind == ModelKind.GENERATION) maxGenerators else maxEmbedders
                if (sameKind.size < max) {
                    // Loaded under the lock: two requests for a model load it once
                    val entry = Entry(key, load())
                    entry.users = 1
                    entries.add(entry)
                    publish()
                    return entry
                }
                sameKind.filter { it.users == 0 }.minByOrNull { it.lastUsed }
            }
            if (evicting != null) close(evicting, onlyIfIdle = true) else closings.first { it != seen }
        }
    }

    private suspend fun release(entry: Entry, keepLoadedMillis: Long) {
        val closeNow = lock.withLock {
            entry.users--
            entry.lastUsed = ++tick
            if (entry.users > 0 || entry !in entries) return
            if (keepLoadedMillis > 0) {
                entry.expiry = scope.launch {
                    delay(keepLoadedMillis)
                    close(entry, onlyIfIdle = true)
                }
            }
            keepLoadedMillis <= 0
        }
        if (closeNow) close(entry, onlyIfIdle = true)
    }

    /** Closes [entry] once its request is done; [onlyIfIdle]: not if a request took it meanwhile. */
    private suspend fun close(entry: Entry, onlyIfIdle: Boolean = false) {
        val self = currentCoroutineContext()[Job]
        entry.use.withLock {
            lock.withLock {
                if ((onlyIfIdle && entry.users > 0) || entry !in entries) return
                entries.remove(entry)
                entry.expiry?.takeIf { it != self }?.cancel()
                entry.expiry = null
                publish()
            }
            entry.isClosed = true
            entry.instance.close()
        }
        closings.value++
    }

    private fun publish() {
        loadedState.value = entries.mapTo(mutableSetOf()) { it.key.modelId }
    }
}
