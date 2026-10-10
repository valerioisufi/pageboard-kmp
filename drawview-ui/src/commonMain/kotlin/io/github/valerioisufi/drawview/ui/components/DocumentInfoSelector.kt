package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.observeChanges
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.util.formatDateTime
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * The title of the document in the bar of the editor, with what it holds under it ([subtitle]);
 * a tap opens its information: where it is ([location]), its dates, its pages, its format and how
 * many elements it holds.
 *
 * @param document the document, `null` while it is being loaded.
 */
@Composable
fun DocumentInfoSelector(
    document: DrawDocument?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    location: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val title = document?.name ?: stringResource(Res.string.common_state_loading)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.document_info_action_expand)) { expanded = !expanded }
                .padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(
                imageVector = if (expanded) DrawViewIcons.ChevronUp else DrawViewIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        ToolPopover(expanded = expanded && document != null, onDismissRequest = { expanded = false }, offset = DpOffset(0.dp, 8.dp)) {
            if (document != null) DocumentInfo(document, location)
        }
    }
}

/** The information of [document]: its title and folder, its dates, pages, format and elements. */
@Composable
private fun DocumentInfo(document: DrawDocument, location: String?) {
    document.observeChanges()
    val monthNames = stringArrayResource(Res.array.date_months_short)
    val firstPage = document.pages.firstOrNull()
    val notAvailable = stringResource(Res.string.common_label_not_available)
    val dimensions = firstPage?.let {
        stringResource(Res.string.document_info_value_dimensions, it.widthMm.toInt(), it.heightMm.toInt())
    } ?: notAvailable
    val elements = document.pages.sumOf { page -> page.strokes.size + page.images.size + page.texts.size + page.pdfs.size + page.markups.size }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(document.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (location != null) {
            Text(location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(12.dp))
        InfoRow(DrawViewIcons.Clock, stringResource(Res.string.document_info_label_created_date), document.createdAt.takeIf { it > 0L }?.let { formatDateTime(it, monthNames).trimStart('0') } ?: notAvailable)
        InfoRow(DrawViewIcons.Pencil, stringResource(Res.string.document_info_label_modified_date), document.modifiedAt.takeIf { it > 0L }?.let { formatDateTime(it, monthNames).trimStart('0') } ?: notAvailable)
        InfoRow(DrawViewIcons.FileText, stringResource(Res.string.document_info_label_pages), document.pages.size.toString())
        InfoRow(DrawViewIcons.Grid, stringResource(Res.string.document_info_label_format), dimensions)
        InfoRow(DrawViewIcons.Layers, stringResource(Res.string.document_info_label_elements_count), elements.toString())
    }
}

/** A property of the document: its icon and its name, its value at the end. */
@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
