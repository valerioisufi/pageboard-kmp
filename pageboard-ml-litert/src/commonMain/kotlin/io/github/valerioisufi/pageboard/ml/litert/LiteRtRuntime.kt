package io.github.valerioisufi.pageboard.ml.litert

import io.github.valerioisufi.pageboard.ml.Embedder
import io.github.valerioisufi.pageboard.ml.EmbedderOptions
import io.github.valerioisufi.pageboard.ml.Generator
import io.github.valerioisufi.pageboard.ml.GeneratorOptions
import io.github.valerioisufi.pageboard.ml.InstalledModel
import io.github.valerioisufi.pageboard.ml.ModelException
import io.github.valerioisufi.pageboard.ml.ModelRuntime
import io.github.valerioisufi.pageboard.ml.ModelSpec
import io.github.valerioisufi.pageboard.platform.dataDirectory

/**
 * LiteRT-LM as the [ModelRuntime] of Pageboard: the `.litertlm` models (Gemma 4, EmbeddingGemma 2),
 * on the GPU when it can be used, on the processor otherwise.
 *
 * On Android and on the desktop JVM of the platforms LiteRT-LM is built for (Windows x86-64, macOS
 * on Apple silicon, Linux); on iOS for now, it supports no model ([isAvailable] is false) and the
 * applications use their other models.
 *
 * @param cacheDirectory where LiteRT-LM keeps what makes the next load of a model faster (`null`:
 *   nothing is kept).
 */
public class LiteRtRuntime(private val cacheDirectory: String? = defaultCacheDirectory()) : ModelRuntime {
    private val engine: LiteRtEngine? by lazy { liteRtEngine() }

    /** Whether LiteRT-LM runs on this platform (its native library loaded). */
    public val isAvailable: Boolean get() = engine != null

    override fun supports(spec: ModelSpec): Boolean = engine != null && spec.fileName.endsWith(MODEL_EXTENSION)

    override suspend fun generator(model: InstalledModel, options: GeneratorOptions): Generator =
        available(model).generator(model, options, cacheDirectory)

    override suspend fun embedder(model: InstalledModel, options: EmbedderOptions): Embedder =
        available(model).embedder(model, options, cacheDirectory)

    private fun available(model: InstalledModel): LiteRtEngine {
        val engine = engine ?: throw ModelException("LiteRT-LM does not run on this platform")
        if (!supports(model.spec)) throw ModelException("${model.spec.name} is not a model of LiteRT-LM")
        return engine
    }

    public companion object {
        private const val MODEL_EXTENSION = ".litertlm"

        /** `litert-cache/` of the data directory of the application. */
        public fun defaultCacheDirectory(): String = dataDirectory() + "/litert-cache"
    }
}

/** LiteRT-LM on a platform: it loads the models. */
internal interface LiteRtEngine {
    suspend fun generator(model: InstalledModel, options: GeneratorOptions, cacheDirectory: String?): Generator

    suspend fun embedder(model: InstalledModel, options: EmbedderOptions, cacheDirectory: String?): Embedder
}

/** LiteRT-LM on this platform, `null` where it is not built or its native library does not load. */
internal expect fun liteRtEngine(): LiteRtEngine?

/** The room left on the disk beyond the size of a model before its weights are cached (256 MiB). */
internal const val CACHE_MARGIN: Long = 256L * 1024 * 1024

/** The cache directory that tells LiteRT-LM to cache no weights (it writes them beside the model otherwise). */
internal const val NO_CACHE: String = ":nocache"
