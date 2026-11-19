package io.github.valerioisufi.drawview.ml

/** What a model is for. */
public enum class ModelKind {
    /** It writes text from text, images and audio (Gemma 4). */
    GENERATION,

    /** It turns a text into a vector, whose distance from another tells how close their meanings are (EmbeddingGemma 2). */
    EMBEDDING
}

/** What a model reads. */
public enum class ModelInput { TEXT, IMAGE, AUDIO }

/**
 * A model that can be downloaded and run on the device: one file of the runtime (a `.litertlm`
 * of LiteRT-LM), where it is downloaded from and how it is recognized. Two specs are the same
 * model when their [id] and their [sha256] are the same.
 *
 * @param id the name the settings keep: it does not change across the versions of the application.
 * @param name the name shown to the student.
 * @param fileName the name of its file in [InstalledModels.directory].
 * @param url where it is downloaded from (a fixed revision, so that its [sha256] holds).
 * @param sizeBytes the size of its file.
 * @param sha256 the hash of its file, in hexadecimal: a download that does not match is not kept.
 * @param memoryBytes about the memory it takes when loaded, to tell whether a device can hold it.
 * @param dimensions the size of the vectors of an embedding model (0 for the others).
 * @param maxImageTokens the most tokens an image may take in this file ([GeneratorOptions.imageTokens]):
 *   it is loaded with no more; 0 for a model that reads no image.
 */
public class ModelSpec(
    public val id: String,
    public val name: String,
    public val kind: ModelKind,
    public val inputs: Set<ModelInput>,
    public val fileName: String,
    public val url: String,
    public val sizeBytes: Long,
    public val sha256: String,
    public val memoryBytes: Long,
    public val dimensions: Int = 0,
    public val maxImageTokens: Int = 0
) {
    override fun equals(other: Any?): Boolean = other is ModelSpec && other.id == id && other.sha256 == sha256

    override fun hashCode(): Int = id.hashCode() * 31 + sha256.hashCode()

    override fun toString(): String = "ModelSpec($id)"
}

/** A model whose file is on this device, at [path], verified when it was downloaded. */
public class InstalledModel(public val spec: ModelSpec, public val path: String) {
    override fun equals(other: Any?): Boolean = other is InstalledModel && other.spec == spec && other.path == path

    override fun hashCode(): Int = spec.hashCode() * 31 + path.hashCode()

    override fun toString(): String = "InstalledModel(${spec.id}, $path)"
}
