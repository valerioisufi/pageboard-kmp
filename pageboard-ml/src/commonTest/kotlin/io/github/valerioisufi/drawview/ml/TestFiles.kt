package io.github.valerioisufi.drawview.ml

import io.github.valerioisufi.drawview.platform.Sha256
import kotlin.random.Random
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray

/** A new empty directory of the tests, deleted by [deleteDirectory]. */
internal fun newTestDirectory(): String {
    val path = Path(SystemTemporaryDirectory, "pageboard-ml-" + Random.nextLong().toULong().toString(16))
    SystemFileSystem.createDirectories(path)
    return path.toString()
}

/** Deletes [directory] of the tests and what it holds. */
internal fun deleteDirectory(directory: String) {
    fun delete(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::delete)
        SystemFileSystem.delete(path, mustExist = false)
    }
    delete(Path(directory))
}

internal fun writeFile(path: String, bytes: ByteArray) {
    Path(path).parent?.let { SystemFileSystem.createDirectories(it) }
    SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }
}

internal fun readFile(path: String): ByteArray? =
    if (SystemFileSystem.exists(Path(path))) SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() } else null

internal fun exists(path: String): Boolean = SystemFileSystem.exists(Path(path))

/** A model of the tests whose file is [bytes]. */
internal fun testSpec(bytes: ByteArray, id: String = "test-model", kind: ModelKind = ModelKind.GENERATION): ModelSpec = ModelSpec(
    id = id,
    name = "Test model",
    kind = kind,
    inputs = setOf(ModelInput.TEXT),
    fileName = "$id.litertlm",
    url = "https://models.test/$id.litertlm",
    sizeBytes = bytes.size.toLong(),
    sha256 = Sha256.of(bytes),
    memoryBytes = bytes.size.toLong()
)
