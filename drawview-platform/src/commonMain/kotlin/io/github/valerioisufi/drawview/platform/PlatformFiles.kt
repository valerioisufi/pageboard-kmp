package io.github.valerioisufi.drawview.platform

import kotlin.random.Random

/**
 * Minimal file system access used to store the resources of the documents (imported images
 * and PDF files).
 */
public expect object PlatformFiles {
    /** Directory where document resources are stored (created on demand). */
    public fun resourcesDirectory(): String

    public fun readBytes(path: String): ByteArray?

    public fun writeBytes(path: String, bytes: ByteArray): Boolean

    public fun exists(path: String): Boolean

    public fun delete(path: String): Boolean
}

/**
 * Builds a unique path inside [PlatformFiles.resourcesDirectory].
 */
public fun newResourcePath(prefix: String, extension: String): String {
    val unique = Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
    return PlatformFiles.resourcesDirectory().trimEnd('/') + "/${prefix}_$unique.$extension"
}
