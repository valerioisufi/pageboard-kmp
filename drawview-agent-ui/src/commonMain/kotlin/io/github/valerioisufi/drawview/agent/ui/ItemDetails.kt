package io.github.valerioisufi.drawview.agent.ui

import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.agent.LibraryItem
import io.github.valerioisufi.drawview.agent.ProposalEntry
import io.github.valerioisufi.drawview.agent.ProposalItem
import io.github.valerioisufi.drawview.agent.StackPlace
import io.github.valerioisufi.drawview.agent.ui.generated.resources.Res
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_alignment
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_background
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_back
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_color
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_elements
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_font_size
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_front
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_moved
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_page
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_points
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_scaled
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_size
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_detail_width
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_library_create_document
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_library_create_folder
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_library_move_document
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_library_move_folder
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_library_rename_folder
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_library_restore
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_library_root
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_library_trash_document
import io.github.valerioisufi.drawview.agent.ui.generated.resources.agent_library_trash_folder
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What an item of a proposal does, in a few words beside its label (the page it is on, how far it moves
 * things, the style it gives), or `null` when its label and its texts say it all.
 */
@Composable
internal fun itemDetail(entry: ProposalEntry): String? {
    val parts = mutableListOf<String>()
    entry.page?.let { parts += stringResource(Res.string.agent_detail_page, it + 1) }
    when (val item = entry.item) {
        is ProposalItem.RemoveElements -> parts += pluralStringResource(Res.plurals.agent_detail_elements, item.elements.size, item.elements.size)
        is ProposalItem.PlaceElements -> {
            parts += pluralStringResource(Res.plurals.agent_detail_elements, item.elements.size, item.elements.size)
            if (item.dx != 0f || item.dy != 0f) parts += stringResource(Res.string.agent_detail_moved, item.dx.mm(), item.dy.mm())
            if (item.scale != 1f) parts += stringResource(Res.string.agent_detail_scaled, item.scale.mm())
        }
        is ProposalItem.ReorderElements -> {
            parts += pluralStringResource(Res.plurals.agent_detail_elements, item.elements.size, item.elements.size)
            parts += stringResource(if (item.to == StackPlace.FRONT) Res.string.agent_detail_front else Res.string.agent_detail_back)
        }
        is ProposalItem.RestyleText -> {
            item.color?.let { parts += stringResource(Res.string.agent_detail_color, it) }
            item.fontSize?.let { parts += stringResource(Res.string.agent_detail_font_size, it.mm()) }
            item.alignment?.let { parts += stringResource(Res.string.agent_detail_alignment, it) }
        }
        is ProposalItem.AddStroke -> {
            parts += pluralStringResource(Res.plurals.agent_detail_points, item.points.size, item.points.size)
            item.color?.let { parts += stringResource(Res.string.agent_detail_color, it) }
            item.width?.let { parts += stringResource(Res.string.agent_detail_width, it.mm()) }
        }
        is ProposalItem.RestyleStroke -> {
            item.color?.let { parts += stringResource(Res.string.agent_detail_color, it) }
            item.width?.let { parts += stringResource(Res.string.agent_detail_width, it.mm()) }
        }
        is ProposalItem.AddImage -> item.area?.let { parts += stringResource(Res.string.agent_detail_size, it.width.mm(), it.height.mm()) }
        is ProposalItem.SetPageFormat -> {
            if (item.widthMm != null || item.heightMm != null) {
                parts += stringResource(Res.string.agent_detail_size, item.widthMm?.mm() ?: "–", item.heightMm?.mm() ?: "–")
            }
            item.background?.let { parts += stringResource(Res.string.agent_detail_background, it) }
            item.backgroundColor?.let { parts += stringResource(Res.string.agent_detail_color, it) }
        }
        else -> {}
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** What an item of the library does, in words, with the names of the documents and folders it names. */
@Composable
internal fun libraryItemText(item: LibraryItem, names: Map<String, String>): String {
    fun name(id: String?): String = id?.let { names[it] ?: it.take(8) } ?: ""
    val root = stringResource(Res.string.agent_library_root)
    fun place(id: String?): String = if (id == null) root else name(id)
    return when (item) {
        is LibraryItem.CreateDocument -> stringResource(Res.string.agent_library_create_document, item.title, place(item.folder))
        is LibraryItem.MoveDocument -> stringResource(Res.string.agent_library_move_document, name(item.document), place(item.folder))
        is LibraryItem.TrashDocument -> stringResource(Res.string.agent_library_trash_document, name(item.document))
        is LibraryItem.CreateFolder -> stringResource(Res.string.agent_library_create_folder, item.name, place(item.parent))
        is LibraryItem.RenameFolder -> stringResource(Res.string.agent_library_rename_folder, name(item.folder), item.name)
        is LibraryItem.MoveFolder -> stringResource(Res.string.agent_library_move_folder, name(item.folder), place(item.parent))
        is LibraryItem.TrashFolder -> stringResource(Res.string.agent_library_trash_folder, name(item.folder))
        is LibraryItem.Restore -> stringResource(Res.string.agent_library_restore, name(item.item))
    }
}

/** Millimeters for a person: a whole number, or one decimal. */
private fun Float.mm(): String {
    val tenths = (this * 10f).roundToInt()
    val sign = if (tenths < 0) "-" else ""
    val whole = abs(tenths) / 10
    val decimal = abs(tenths) % 10
    return if (decimal == 0) "$sign$whole" else "$sign$whole.$decimal"
}
