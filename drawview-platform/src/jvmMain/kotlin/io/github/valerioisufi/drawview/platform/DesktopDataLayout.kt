package io.github.valerioisufi.drawview.platform

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * The layout of a desktop data directory, brought up to date (GRASP Pure Fabrication).
 *
 * Until `dataDirectory()` took the parent of the resources directory on the separators of the
 * platform, on Windows it was the resources directory itself, so what belongs beside the resources
 * was saved inside them: the lectures, the speech models, the thumbnails and the name of the
 * device ([MOVED]). They are moved beside the resources, where `dataDirectory()` finds them now.
 * On macOS and Linux there is never anything to move.
 *
 * Nothing is overwritten or deleted. An entry that is beside the resources already (a build
 * without the fix run again after one with it) is merged with the one inside them: what is
 * missing beside the resources is moved there, and a file on both sides stays on both (the one
 * beside the resources is the one read). A move that fails (a file held by another process)
 * leaves the entry where it is, to be moved at the next start.
 */
internal object DesktopDataLayout {
    /** The resources directory of the documents, in the data directory. */
    const val RESOURCES: String = "resources"

    /** What `dataDirectory()` put in the resources directory on Windows: a list closed with the fix. */
    val MOVED: List<String> = listOf("lectures", "whisper", "thumbnails", "device-name.txt")

    /** The data directories brought up to date by this process. */
    private val upToDate: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Brings [dataDirectory] up to date once per process, before its data is read. */
    fun ensureUpToDate(dataDirectory: File) {
        val key = dataDirectory.absolutePath
        if (key in upToDate) return
        synchronized(this) {
            if (key in upToDate) return
            bringUpToDate(dataDirectory)
            upToDate += key
        }
    }

    /** Moves the [MOVED] entries of the resources directory of [dataDirectory] beside it. */
    fun bringUpToDate(dataDirectory: File) {
        val resources = File(dataDirectory, RESOURCES)
        for (name in MOVED) {
            val misplaced = File(resources, name)
            if (misplaced.exists()) move(misplaced, File(dataDirectory, name))
        }
    }

    /** Moves [from] to [to], into the directory [to] when it is there already. */
    private fun move(from: File, to: File) {
        if (!to.exists()) {
            try {
                // A rename on the same volume; without options it never replaces a file there
                Files.move(from.toPath(), to.toPath())
            } catch (e: IOException) {
                println("DrawView: cannot move $from to $to, moved at the next start: ${e.message}")
            }
            return
        }
        if (!from.isDirectory || !to.isDirectory) {
            println("DrawView: $from stays where it is, $to is there already")
            return
        }
        from.listFiles()?.forEach { move(it, File(to, it.name)) }
        // A directory emptied only: delete() refuses one with something left in it
        if (from.list()?.isEmpty() == true) from.delete()
    }
}
