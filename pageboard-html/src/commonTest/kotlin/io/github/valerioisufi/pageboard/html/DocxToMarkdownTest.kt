package io.github.valerioisufi.pageboard.html

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Word document in the notation, its archive read within bounds. */
class DocxToMarkdownTest {

    /** A document written by a script like Word's: a heading of a style named in Italian, runs, links, lists, a table, an equation. */
    private val docx = Base64.decode(
        "UEsDBBQAAAAIAChjel3HHBc8CgAAAAgAAAATAAAAW0NvbnRlbnRfVHlwZXNdLnhtbLMJqSxILda3AwBQSwMEFAAAAAgAKGN6XXbo" +
            "4pLUAQAATgYAABEAAAB3b3JkL2RvY3VtZW50LnhtbL1V224bIRB971cgnltj+6FqV16iVlUkS6kUKekHYBh7UbkJsJ39+wJ7qe3Y" +
            "uVhVX4admcM5zIxgFzdPWqEd+CCtqfFsMsUIDLdCmk2Nfz3efvqCUYjMCKasgRq3EPAN/bDYV8LyrQYTUWIwodrXuInRVYQE3oBm" +
            "YWIdmJRbW69ZTK7fkL31wnnLIYQkoBWZT6efiWbS4J7Gv4XGrteSw4/+AB2JB8ViKiI00oWBTV/LlpINpqnKlRVtKddlz937sjzE" +
            "VgHaVzumavwoo1V2hgldkBFSTKR3UssYcjyWrO8wfxk7XD5uFRzjqcPOQwC/A0y/oaONg+kEVp1c70W6skqcg1/gRmmkL9HLE3oZ" +
            "mZL8PQJ51h9PJZrWgVfS/Ea+kqLGfim+4oN+MZSTp/0adx0gJy80tZ+B2eq+GLVTw7SmeU4ltxRDbJhdv+HZFG+lD/FKvdkVektj" +
            "wP/H+h6AWyP+oeD8FcHvW6XgQkfjShVQB+X0+KbQp+e7SoSfh7eX4WQUuSA1e5/U/FUp0lXXt1VX9md6Zu6ZZ6OTP9bZpM7lxWeT" +
            "rsWC6EJdImRMCzAHqNUpqqRJYSSjADnSPfca0TsWInIpv/HMNWdvGumfxvwx/AnoH1BLAwQUAAAACAAoY3pdKe/7vdcAAACSAQAA" +
            "DwAAAHdvcmQvc3R5bGVzLnhtbJ2QsW7DIBBAfwXd3mB3iCorJEtVqVKGDukHIHw2SMAhjtjN3xdap12rToDe0+PgcPoIXiyY2VFU" +
            "0O86EBgNjS7OCt4vLw9PcDoe1oHLzSOLakceVgW2lDRIycZi0LyjhLGyiXLQpR7zLFfKY8pkkLnGgpePXbeXQbsIWyb/JUPT5Aw+" +
            "k7kGjOU7ktHrUgdm6xLfa+G/tQot/LxRrEO5JVSQdNZz1smC2NDrqODiCnnqv/yoQ9MX7evVqNufiR5kQ+ktt4WuxbuI58Xfva5x" +
            "uQlyC//u+PgJUEsDBBQAAAAIAChjel1KOwGi/AAAAJQCAAASAAAAd29yZC9udW1iZXJpbmcueG1snZLBboMwDIbvewqU+xroYZoQ" +
            "0EtVaZeetgcIwZRIsYOSAH38edBKpYcN7RTF9v/5z68UhyvaZAQfjKNSZLtUJEDaNYYupfj6PL2+i0NVTDkNWIPnasICCvlUii7G" +
            "Ppcy6A5QhZ3rgbjXOo8q8tVf5OR803unIQRWopX7NH2TqAyJG8Zvwbi2NRqOTg8IFBeIB6siew6d6cOdhv+lcbMT1Qu/U9UheqXj" +
            "ecBkdftoOJ5U/GRhR8s9w0cplgqnc8LIxVFxrQFtUFkhq0LO08+ibJtIrgxssJdtslcP1kL8YxHNC+gGnimrbXcYR7KAeLR61u1/" +
            "0WWPOvnwwapvUEsDBBQAAAAIAChjel0cU50KuAAAADIBAAAcAAAAd29yZC9fcmVscy9kb2N1bWVudC54bWwucmVsc43PTQrCMBAF" +
            "4KuE2dtUF6LStBsVunAj9QAhmbah+SOJUm9vUAQFFy6HefM9pmpmo8kNQ1TOMlgWJRC0wkllBwaX7rjYQFNXZ9Q85UQclY8kn9jI" +
            "YEzJ7yiNYkTDY+E82rzpXTA85TEM1HMx8QHpqizXNHwa8G2SVjIIrdwC6e4e/7Fd3yuBeyeuBm36UUHHLAWt7JRRHgZMLzZmF2du" +
            "vMYnpJVRKb4zJydz/WFOGCzXQOuKfj1fPwBQSwECFAMUAAAACAAoY3pdxxwXPAoAAAAIAAAAEwAAAAAAAAAAAAAAgAEAAAAAW0Nv" +
            "bnRlbnRfVHlwZXNdLnhtbFBLAQIUAxQAAAAIAChjel126OKS1AEAAE4GAAARAAAAAAAAAAAAAACAATsAAAB3b3JkL2RvY3VtZW50" +
            "LnhtbFBLAQIUAxQAAAAIAChjel0p7/u91wAAAJIBAAAPAAAAAAAAAAAAAACAAT4CAAB3b3JkL3N0eWxlcy54bWxQSwECFAMUAAAA" +
            "CAAoY3pdSjsBovwAAACUAgAAEgAAAAAAAAAAAAAAgAFCAwAAd29yZC9udW1iZXJpbmcueG1sUEsBAhQDFAAAAAgAKGN6XRxTnQq4" +
            "AAAAMgEAABwAAAAAAAAAAAAAAIABbgQAAHdvcmQvX3JlbHMvZG9jdW1lbnQueG1sLnJlbHNQSwUGAAAAAAUABQBHAQAAYAUAAAAA"
    )

    @Test
    fun aRawDeflatedEntryInflatesToItsSize() {
        val deflated = Base64.decode("y0jNyclXyEAnAQ==")
        assertEquals("hello hello hello hello", inflateRaw(deflated, 23)?.decodeToString())
        assertNull(inflateRaw(deflated, 22), "more than it said")
        assertNull(inflateRaw(deflated, 24), "less than it said")
        assertNull(inflateRaw(byteArrayOf(1, 2, 3), 3), "not deflated")
    }

    @Test
    fun theEntriesOfTheArchiveAreRead() {
        val archive = ZipEntries.of(docx)
        assertTrue("word/document.xml" in archive.names)
        assertTrue(archive.read("word/document.xml")!!.decodeToString().contains("Limits"))
        assertNull(archive.read("word/missing.xml"))
    }

    @Test
    fun theDocumentIsWrittenInTheNotation() {
        val markdown = DocxToMarkdown().convert(docx)!!
        assertTrue(markdown.startsWith("# Limits"), markdown)
        assertTrue("A **bold** and *italic* word, [a link](https://example.org/limits)." in markdown, markdown)
        assertTrue("1. First" in markdown && "Inner" in markdown && "2. Second" in markdown, markdown)
        assertTrue(Regex("""(?m)^ +1\. Inner""").containsMatchIn(markdown), "nested: $markdown")
        assertTrue(Regex("""(?m)^[-*] Bullet""").containsMatchIn(markdown), markdown)
        assertTrue("| x | y |" in markdown, markdown)
        assertTrue("\\frac{a}{b}" in markdown, markdown)
        assertTrue(markdown.trimEnd().endsWith("Last paragraph."), markdown)
    }

    @Test
    fun whatIsNotAnArchiveGivesNothing() {
        assertNull(DocxToMarkdown().convert("not a document".encodeToByteArray()))
        assertNull(DocxToMarkdown().convert(ByteArray(0)))
        // Its directory cut: every offset checked
        assertNull(DocxToMarkdown().convert(docx.copyOf(docx.size - 30)))
        assertFailsWith<ZipEntries.UnreadableArchive> { ZipEntries.of(docx.copyOfRange(0, 100)) }
    }

    @Test
    fun anEntryClaimingAHugeSizeIsNotInflated() {
        val bomb = docx.copyOf()
        // The size of the first entry of the directory, made 2 GiB
        val end = (bomb.size - 22 downTo 0).first { bomb[it] == 0x50.toByte() && bomb[it + 1] == 0x4b.toByte() && bomb[it + 2] == 5.toByte() && bomb[it + 3] == 6.toByte() }
        val directory = (bomb[end + 16].toInt() and 0xFF) or ((bomb[end + 17].toInt() and 0xFF) shl 8)
        bomb[directory + 27] = 0x7F
        assertFailsWith<ZipEntries.UnreadableArchive> { ZipEntries.of(bomb) }
    }
}
