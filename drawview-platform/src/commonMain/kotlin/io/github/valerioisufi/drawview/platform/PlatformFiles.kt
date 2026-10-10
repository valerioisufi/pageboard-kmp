package io.github.valerioisufi.drawview.platform

import kotlin.random.Random

/**
 * Minimal file system access used to store the resources of the documents (imported images
 * and PDF files).
 */
expect object PlatformFiles {
    /** Directory where document resources are stored (created on demand). */
    fun resourcesDirectory(): String

    fun readBytes(path: String): ByteArray?

    fun writeBytes(path: String, bytes: ByteArray): Boolean

    fun exists(path: String): Boolean

    fun delete(path: String): Boolean
}

/**
 * Builds a unique path inside [PlatformFiles.resourcesDirectory].
 */
fun newResourcePath(prefix: String, extension: String): String {
    val unique = Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
    return PlatformFiles.resourcesDirectory().trimEnd('/') + "/${prefix}_$unique.$extension"
}
