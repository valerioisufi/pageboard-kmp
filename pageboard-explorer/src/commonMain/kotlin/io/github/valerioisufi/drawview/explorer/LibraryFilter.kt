package io.github.valerioisufi.drawview.explorer

import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_tag_pdf
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_tag_pdfs
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_tag_shared
import io.github.valerioisufi.drawview.ui.components.BadgeTone
import io.github.valerioisufi.drawview.ui.extension.DocumentTag
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.stringResource

/** The notes the library shows. */
internal sealed interface LibraryFilter {
    /** Every note, and the folders. */
    data object All : LibraryFilter

    /** The notes written (without a PDF). */
    data object Notes : LibraryFilter

    /** The notes of the tag [tagId]. */
    data class Tagged(val tagId: String) : LibraryFilter
}

/** The tag of the documents of [documents] holding a PDF. */
internal fun pdfTag(documents: () -> Set<String>) = DocumentTag(
    id = "pdf",
    label = { stringResource(Res.string.explorer_tag_pdf) },
    filterLabel = { stringResource(Res.string.explorer_tag_pdfs) },
    icon = DrawViewIcons.FileText,
    tone = BadgeTone.Red,
    documents = documents
)

/** The tag of the documents of [documents] shared with other devices. */
internal fun sharedTag(documents: () -> Set<String>) = DocumentTag(
    id = "shared",
    label = { stringResource(Res.string.explorer_tag_shared) },
    filterLabel = { stringResource(Res.string.explorer_tag_shared) },
    icon = DrawViewIcons.Users,
    tone = BadgeTone.Green,
    documents = documents
)
