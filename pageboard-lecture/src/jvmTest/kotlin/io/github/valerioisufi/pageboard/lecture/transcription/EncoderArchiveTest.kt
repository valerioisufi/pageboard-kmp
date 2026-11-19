package io.github.valerioisufi.pageboard.lecture.transcription

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EncoderArchiveTest {
    private val directory: File = Files.createTempDirectory("encoders").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): String {
        val file = File(directory, "encoder.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return file.absolutePath
    }

    private val models get() = File(directory, "whisper").apply { mkdirs() }

    @Test
    fun anEncoderTakesThePlaceOfTheOneThere() {
        val old = File(models, "ggml-base-encoder.mlmodelc").apply { mkdirs() }
        File(old, "stale.bin").writeBytes(ByteArray(3))
        val zip = zip("ggml-base-encoder.mlmodelc/coremldata.bin" to ByteArray(5), "ggml-base-encoder.mlmodelc/weights/weight.bin" to ByteArray(9))
        JvmEncoderArchive.extract(zip, models.path, "ggml-base-encoder.mlmodelc", maxBytes = 100)
        assertEquals(setOf("coremldata.bin", "weights"), old.list()!!.toSet())
        assertEquals(setOf("ggml-base-encoder.mlmodelc"), models.list()!!.toSet())
    }

    @Test
    fun anEntryOfAnotherDirectoryIsRefused() {
        val zip = zip("ggml-base-encoder.mlmodelc/coremldata.bin" to ByteArray(5), "other/file.bin" to ByteArray(5))
        assertFailsWith<IOException> { JvmEncoderArchive.extract(zip, models.path, "ggml-base-encoder.mlmodelc", maxBytes = 100) }
        assertTrue(models.list()!!.isEmpty())
    }

    @Test
    fun anEncoderLargerThanAllowedIsRefused() {
        val zip = zip("ggml-base-encoder.mlmodelc/coremldata.bin" to ByteArray(5), "ggml-base-encoder.mlmodelc/weights/weight.bin" to ByteArray(1_000))
        assertFailsWith<IOException> { JvmEncoderArchive.extract(zip, models.path, "ggml-base-encoder.mlmodelc", maxBytes = 500) }
        assertTrue(models.list()!!.isEmpty())
    }

    @Test
    fun anArchiveWithoutACoreMlModelIsRefused() {
        val zip = zip("ggml-base-encoder.mlmodelc/readme.txt" to ByteArray(5))
        assertFailsWith<IOException> { JvmEncoderArchive.extract(zip, models.path, "ggml-base-encoder.mlmodelc", maxBytes = 100) }
        assertTrue(models.list()!!.isEmpty())
    }

    @Test
    fun theDigestIsTheSha256OfTheFile() {
        val file = File(directory, "abc.txt").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", JvmEncoderArchive.sha256(file.path))
    }
}
