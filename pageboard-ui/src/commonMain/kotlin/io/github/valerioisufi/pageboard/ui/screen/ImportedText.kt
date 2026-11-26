package io.github.valerioisufi.pageboard.ui.screen

import io.github.valerioisufi.pageboard.canvas.controller.TextImport
import io.github.valerioisufi.pageboard.html.DocxToMarkdown
import io.github.valerioisufi.pageboard.html.HtmlToMarkdown
import io.github.valerioisufi.pageboard.model.text.TextCleanup
import io.github.valerioisufi.pageboard.ui.platform.PickedFile

/**
 * [file], chosen or dropped to be imported as a text (see `docs/plans/text-import.md` and
 * `docs/plans/import-and-clipboard.md`), waiting for the student to say where it goes: a Word
 * document and a page of HTML in the notation, any other file as its text. `null` when it cannot be
 * read or holds no text.
 */
internal fun importedText(file: PickedFile, pageIndex: Int?): TextImport? {
    val name = file.name.substringBeforeLast('.')
    return when (file.extension) {
        "docx" -> DocxToMarkdown().convert(file.bytes)?.let { TextImport(it, pageIndex, isMarkdown = true, name = name) }
        "html", "htm", "xhtml" -> HtmlToMarkdown().convertPage(file.text())?.let { TextImport(it, pageIndex, isMarkdown = true, name = name) }
        else -> {
            val text = file.text()
            if (text.isBlank()) return null
            TextImport(
                text, pageIndex,
                isMarkdown = file.extension == "md" || file.extension == "markdown" || TextCleanup.looksLikeMarkdown(text),
                name = name
            )
        }
    }
}
