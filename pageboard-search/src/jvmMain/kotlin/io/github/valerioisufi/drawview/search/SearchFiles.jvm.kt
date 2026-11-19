package io.github.valerioisufi.drawview.search

import java.io.File

internal actual fun restrictToOwner(path: String) {
    val file = File(path)
    if (!file.exists()) return
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
}
