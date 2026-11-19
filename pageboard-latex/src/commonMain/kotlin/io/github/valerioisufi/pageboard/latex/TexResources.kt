package io.github.valerioisufi.pageboard.latex

import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.latex.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Unpacks the TeX resources (fonts and XML symbol tables shipped as Compose resources) into a
 * directory of the file system, where the native engine can read them.
 */
internal object TexResources {
    private const val ROOT = "files/tex"
    private const val MARKER = ".drawview_tex_resources"

    @OptIn(ExperimentalResourceApi::class)
    suspend fun ensureUnpacked(directory: String): Boolean {
        val index = Res.readBytes("$ROOT/RES_README")
        // The marker stores a fingerprint of the index: resources are refreshed on upgrades
        val fingerprint = index.contentHashCode().toString()
        val markerPath = "$directory/$MARKER"
        if (PlatformFiles.readBytes(markerPath)?.decodeToString() == fingerprint &&
            PlatformFiles.exists("$directory/fonts/latin/r10.ttf")
        ) {
            return true
        }

        val paths = index.decodeToString().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        for (path in paths) {
            val bytes = try {
                Res.readBytes("$ROOT/$path")
            } catch (_: Throwable) {
                continue
            }
            if (!PlatformFiles.writeBytes("$directory/$path", bytes)) return false
        }
        PlatformFiles.writeBytes("$directory/RES_README", index)
        return PlatformFiles.writeBytes(markerPath, fingerprint.encodeToByteArray())
    }
}
