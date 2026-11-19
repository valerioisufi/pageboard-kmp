package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.LoadedModels
import io.github.valerioisufi.drawview.ml.ModelDownloads
import io.github.valerioisufi.drawview.ml.ModelKind
import io.github.valerioisufi.drawview.ml.ModelSpec

/**
 * The models of this device given by the application to its [LectureAssistant] (the same the
 * search uses): what is downloaded ([installed]), what is in memory ([loaded]), the downloads
 * ([downloads]). Without them, or when the runtime does not work here ([isAvailable] false), the
 * local requests go to Ollama.
 */
public class DeviceModels(
    public val installed: InstalledModels,
    public val loaded: LoadedModels,
    public val downloads: ModelDownloads,
    public val isAvailable: Boolean = true
) {
    /** The models of the catalog that answer the requests of the assistant (not the embeddings). */
    public val generators: List<ModelSpec> get() = installed.catalog.of(ModelKind.GENERATION)

    /** The model [id] of the catalog, if it is one. */
    public fun spec(id: String): ModelSpec? = installed.catalog[id]

}
