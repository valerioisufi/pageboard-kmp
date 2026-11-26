package io.github.valerioisufi.pageboard.ui.screen

import io.github.valerioisufi.pageboard.ui.platform.PickedFile
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The files imported as text: a Word document and a page of HTML in the notation, the others as their text. */
class ImportedTextTest {

    private fun docx(body: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body</w:body></w:document>").encodeToByteArray()
            )
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    @Test
    fun aWordDocumentIsImportedInTheNotation() {
        val file = PickedFile("Lesson 3.docx", docx("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r><w:r><w:t xml:space=\"preserve\"> words</w:t></w:r></w:p>"))
        val imported = importedText(file, 0)!!
        assertEquals("**Bold** words", imported.text.trim())
        assertTrue(imported.isMarkdown)
        assertEquals("Lesson 3", imported.name)
    }

    @Test
    fun aPageOfHtmlIsImportedInTheNotationOrAsItsText() {
        assertEquals("# Title\n\nA *word*.", importedText(PickedFile("page.html", "<h1>Title</h1><p>A <em>word</em>.</p>".encodeToByteArray()), 0)!!.text.trim())
        assertEquals("Only words", importedText(PickedFile("plain.htm", "<p>Only words</p>".encodeToByteArray()), 0)!!.text.trim())
    }

    @Test
    fun aTextIsImportedAsItIs() {
        val imported = importedText(PickedFile("notes.txt", "﻿Some notes".encodeToByteArray()), 2)!!
        assertEquals("Some notes", imported.text)
        assertEquals(2, imported.pageIndex)
    }

    @Test
    fun aFileThatCannotBeReadGivesNothing() {
        assertNull(importedText(PickedFile("broken.docx", "not an archive".encodeToByteArray()), 0))
        assertNull(importedText(PickedFile("empty.txt", ByteArray(0)), 0))
    }
}
