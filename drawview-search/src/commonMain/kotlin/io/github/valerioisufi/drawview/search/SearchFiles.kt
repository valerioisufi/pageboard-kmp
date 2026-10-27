package io.github.valerioisufi.drawview.search

/** Makes the file at [path] readable and writable by its owner only (where supported). */
internal expect fun restrictToOwner(path: String)
