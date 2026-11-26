package io.github.valerioisufi.pageboard.platform

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

    /**
     * The name of the file or directory at [path]: its last segment, on the separators of the
     * platform (`\` too on Windows), a trailing one ignored.
     */
    public fun name(path: String): String

    /**
     * The directory holding [path], on the separators of the platform (`\` too on Windows), a
     * trailing one ignored: `null` when [path] has a single segment.
     */
    public fun parent(path: String): String?
}

/**
 * Builds a unique path inside [PlatformFiles.resourcesDirectory].
 */
public fun newResourcePath(prefix: String, extension: String): String {
    val unique = Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
    return PlatformFiles.resourcesDirectory().trimEnd('/', '\\') + "/${prefix}_$unique.$extension"
}
