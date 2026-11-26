package io.github.valerioisufi.pageboard.platform

/**
 * Converts between filesystem paths of document resources and the portable paths stored by the
 * document stores (relative to [PlatformFiles.resourcesDirectory]).
 */
public object ResourcePaths {
    /**
     * Returns [path] relative to [resourcesDirectory] (using `/` separators) when [path] lies
     * inside [resourcesDirectory]; otherwise returns [path] unchanged (external paths, empty
     * strings, or paths already relative).
     */
    public fun toStored(
        path: String,
        resourcesDirectory: String = PlatformFiles.resourcesDirectory()
    ): String {
        if (path.isEmpty()) return ""
        val root = normalize(resourcesDirectory).trimEnd('/')
        if (root.isEmpty()) return path
        val normalized = normalize(path)
        val prefix = "$root/"
        if (!normalized.startsWith(prefix)) return path
        val relative = normalized.removePrefix(prefix).trimStart('/')
        if (relative.isEmpty() || hasParentTraversal(relative)) return path
        return relative
    }

    /**
     * Resolves a stored resource path [stored] against [resourcesDirectory]. Empty strings and
     * absolute paths (POSIX `/...`, UNC `\...`, or Windows drive `C:/...` / `C:\...`) are
     * returned unchanged.
     */
    public fun resolve(
        stored: String,
        resourcesDirectory: String = PlatformFiles.resourcesDirectory()
    ): String {
        if (stored.isEmpty() || isAbsolute(stored)) return stored
        val root = resourcesDirectory.trimEnd('/', '\\')
        val relative = stored.trimStart('/', '\\')
        if (root.isEmpty()) return relative
        return "$root/$relative"
    }

    private fun normalize(path: String): String {
        val slash = path.replace('\\', '/')
        return if (slash.length >= 2 && slash[0].isLetter() && slash[1] == ':') {
            slash[0].uppercaseChar() + slash.substring(1)
        } else {
            slash
        }
    }

    private fun isAbsolute(path: String): Boolean {
        if (path.startsWith('/') || path.startsWith('\\')) return true
        return path.length >= 3 && path[0].isLetter() && path[1] == ':' && (path[2] == '/' || path[2] == '\\')
    }

    private fun hasParentTraversal(relative: String): Boolean =
        relative.split('/').any { it == ".." }
}
