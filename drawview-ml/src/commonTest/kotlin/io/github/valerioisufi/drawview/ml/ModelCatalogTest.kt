package io.github.valerioisufi.drawview.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun theModelsAreFoundByTheirIdAndKind() {
        val catalog = ModelCatalog.Default
        assertSame(ModelCatalog.GEMMA_4_E4B, catalog["gemma-4-e4b"])
        assertNull(catalog["gemma-5"])
        assertEquals(listOf(ModelCatalog.EMBEDDING_GEMMA_2_TEXT), catalog.of(ModelKind.EMBEDDING))
        assertEquals(3, catalog.of(ModelKind.GENERATION).size)
    }

    @Test
    fun theFilesOfTheDefaultModelsArePinnedAndVerifiable() {
        for (spec in ModelCatalog.Default.models) {
            // A fixed revision, so that the hash holds
            assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(spec.url), spec.url)
            assertTrue(spec.url.endsWith("/" + spec.fileName), spec.url)
            assertTrue(Regex("[0-9a-f]{64}").matches(spec.sha256), spec.id)
            assertTrue(spec.sizeBytes > 0 && spec.memoryBytes > 0, spec.id)
        }
        assertEquals(768, ModelCatalog.EMBEDDING_GEMMA_2_TEXT.dimensions)
    }

    @Test
    fun twoModelsWithTheSameIdAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            ModelCatalog(listOf(ModelCatalog.GEMMA_4_E2B, ModelCatalog.GEMMA_4_E2B))
        }
    }
}
