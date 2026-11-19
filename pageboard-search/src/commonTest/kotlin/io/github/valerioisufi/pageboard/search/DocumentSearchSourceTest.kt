package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.model.DrawCustomElement
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.ElementType
import io.github.valerioisufi.pageboard.model.encode
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

class DocumentSearchSourceTest {
    @Serializable
    private data class Flashcard(val front: String, val back: String)

    private object FlashcardType : ElementType<Flashcard> {
        override val id: String = "test.flashcard"
        override val serializer = Flashcard.serializer()

        override fun searchText(value: Flashcard): String = "${value.front}: ${value.back}"
    }

    @Test
    fun indexesFolderPathsMarkdownTextsWithLatexAndCustomElementsWithoutWritingStore() = runTest {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = backgroundScope)
        assertTrue(store.createFolder("Università", parentId = null))
        val uniFolder = store.folders.first().single { it.name == "Università" }
        assertTrue(store.createFolder("Crittografia", parentId = uniFolder.id))
        val courseFolder = store.folders.first().single { it.name == "Crittografia" }

        val doc = store.createDocument("Appunti sul cifrario", folderId = courseFolder.id)
        val page = doc.pages.single()

        val text = DrawText().apply {
            this.text = "# Sicurezza semantica\nUn cifrario **perfetto** soddisfa $\$P(M = m \\mid C = c) = P(M = m)\$."
            x = 10f
            y = 20f
            width = 80f
            height = 25f
        }
        page.add(text)
        store.saveText(page, text)

        val customElement = DrawCustomElement(FlashcardType.id).apply {
            version = FlashcardType.version
            payload = FlashcardType.encode(Flashcard("Diffie-Hellman", "Scambio di chiavi su gruppo ciclico"))
            x = 15f
            y = 80f
            width = 60f
            height = 30f
        }
        page.add(customElement)
        store.saveCustomElement(page, customElement)

        val unknownCustom = DrawCustomElement("unregistered.type").apply {
            version = 1
            payload = "ignored".encodeToByteArray()
            x = 0f
            y = 0f
            width = 10f
            height = 10f
        }
        page.add(unknownCustom)
        store.saveCustomElement(page, unknownCustom)
        store.flush()

        val keysBefore = storage.keys
        val snapshotBefore = keysBefore.associateWith { storage.get(it) }

        val source = DocumentSearchSource(store, elementTypes = listOf(FlashcardType))
        val summary = store.documents.first().single()
        val chunks = source.chunks(summary)

        // Never writes to DocumentStore
        assertEquals(snapshotBefore, storage.keys.associateWith { storage.get(it) })

        assertEquals(3, chunks.size)
        val titleChunk = chunks[0]
        assertEquals(SearchChunkKind.TITLE, titleChunk.kind)
        assertEquals(SearchAnchor.Document(doc.id), titleChunk.anchor)
        assertEquals("Università / Crittografia / Appunti sul cifrario", titleChunk.text)

        val textChunk = chunks[1]
        assertEquals(SearchChunkKind.TEXT, textChunk.kind)
        val textAnchor = textChunk.anchor as SearchAnchor.Page
        assertEquals(page.id, textAnchor.pageId)
        assertEquals(1, textAnchor.pageNumber)
        // Markdown "#" and "**" stripped, LaTeX formula kept intact
        assertTrue("Sicurezza semantica" in textChunk.text)
        assertTrue("cifrario perfetto" in textChunk.text)
        assertTrue("\$P(M = m \\mid C = c) = P(M = m)\$" in textChunk.text)
        assertTrue("**" !in textChunk.text)

        val customChunk = chunks[2]
        assertEquals(SearchChunkKind.TEXT, customChunk.kind)
        assertEquals("Diffie-Hellman: Scambio di chiavi su gruppo ciclico", customChunk.text)
    }
}
