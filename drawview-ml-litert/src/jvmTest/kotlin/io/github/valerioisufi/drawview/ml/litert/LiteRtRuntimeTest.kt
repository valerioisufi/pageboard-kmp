package io.github.valerioisufi.drawview.ml.litert

import io.github.valerioisufi.drawview.ml.GeneratorOptions
import io.github.valerioisufi.drawview.ml.InstalledModel
import io.github.valerioisufi.drawview.ml.ModelCatalog
import io.github.valerioisufi.drawview.ml.ModelException
import io.github.valerioisufi.drawview.ml.ModelInput
import io.github.valerioisufi.drawview.ml.ModelKind
import io.github.valerioisufi.drawview.ml.ModelSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LiteRtRuntimeTest {
    private val runtime = LiteRtRuntime()

    /** The desktop platforms `litertlm-jvm` holds a native library for. */
    private val shipped: Boolean
        get() {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val x64 = arch == "amd64" || arch == "x86_64"
            val arm64 = arch == "aarch64" || arch == "arm64"
            return (os.startsWith("windows") && x64) || (os.contains("mac") && arm64) || (os.contains("linux") && (x64 || arm64))
        }

    @Test
    fun itRunsOnTheDesktopPlatformsOfLiteRtLm() {
        assertEquals(shipped, runtime.isAvailable)
        assertEquals(shipped, runtime.supports(ModelCatalog.GEMMA_4_E2B))
        assertEquals(shipped, runtime.supports(ModelCatalog.EMBEDDING_GEMMA_2_TEXT))
    }

    @Test
    fun aModelOfAnotherRuntimeIsNotSupported() {
        val gguf = ModelSpec(
            id = "other", name = "Other", kind = ModelKind.GENERATION, inputs = setOf(ModelInput.TEXT),
            fileName = "other.gguf", url = "https://models.test/other.gguf", sizeBytes = 1, sha256 = "0".repeat(64), memoryBytes = 1
        )
        assertFalse(runtime.supports(gguf))
    }

    @Test
    fun aFileThatIsNotAModelIsAModelException() = runTest {
        if (!runtime.isAvailable) return@runTest
        val file = File.createTempFile("drawview-not-a-model", ".litertlm").apply {
            writeText("not a model")
            deleteOnExit()
        }
        val error = assertFailsWith<ModelException> {
            runtime.generator(InstalledModel(ModelCatalog.GEMMA_4_E2B, file.absolutePath), GeneratorOptions())
        }
        assertTrue(error.message.orEmpty().isNotEmpty())
        file.delete()
    }
}
