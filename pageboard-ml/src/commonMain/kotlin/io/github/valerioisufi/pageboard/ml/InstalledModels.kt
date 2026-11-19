package io.github.valerioisufi.pageboard.ml

import io.github.valerioisufi.pageboard.platform.dataDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The models of the [catalog] on this device, in [directory] (`models/` of the data directory by
 * default), each in a folder of its own (`<id>/<file>`). A file is there only complete and
 * verified: [ModelDownloads] writes it under another name until then.
 *
 * [installed] is updated by [refresh], by the downloads and by [delete].
 */
public class InstalledModels(
    public val catalog: ModelCatalog = ModelCatalog.Default,
    public val directory: String = defaultDirectory()
) {
    private val state = MutableStateFlow(scan())

    /** The ids of the models on this device. */
    public val installed: StateFlow<Set<String>> = state.asStateFlow()

    /** Where the file of [spec] is kept. */
    public fun pathOf(spec: ModelSpec): String = "$directory/${spec.id}/${spec.fileName}"

    /** The model [spec], if it is on this device. */
    public operator fun get(spec: ModelSpec): InstalledModel? = InstalledModel(spec, pathOf(spec)).takeIf { isComplete(spec) }

    /** The model of the catalog named [id], if it is on this device. */
    public operator fun get(id: String): InstalledModel? = catalog[id]?.let { get(it) }

    /** Looks again at the files (e.g. after the settings were reopened). */
    public fun refresh() {
        state.value = scan()
    }

    /**
     * Deletes the file of [spec], and what was downloaded of it: the student asked it, to give its
     * space back. Nothing else of [directory] is touched.
     *
     * @return whether the model is no longer on this device.
     */
    public fun delete(spec: ModelSpec): Boolean {
        val deleted = listOf(pathOf(spec), partialPathOf(spec)).all { path ->
            try {
                SystemFileSystem.delete(Path(path), mustExist = false)
                true
            } catch (_: Exception) {
                false
            }
        }
        refresh()
        return deleted
    }

    /** Where [ModelDownloads] writes the file of [spec] while it arrives. */
    internal fun partialPathOf(spec: ModelSpec): String = pathOf(spec) + PARTIAL_SUFFIX

    /** [spec] was downloaded and verified. */
    internal fun added(spec: ModelSpec) {
        state.update { it + spec.id }
    }

    private fun isComplete(spec: ModelSpec): Boolean = SystemFileSystem.metadataOrNull(Path(pathOf(spec)))?.size == spec.sizeBytes

    private fun scan(): Set<String> = catalog.models.filter(::isComplete).mapTo(mutableSetOf()) { it.id }

    public companion object {
        internal const val PARTIAL_SUFFIX = ".part"

        /** `models/` of the data directory of the application. */
        public fun defaultDirectory(): String = dataDirectory() + "/models"
    }
}
