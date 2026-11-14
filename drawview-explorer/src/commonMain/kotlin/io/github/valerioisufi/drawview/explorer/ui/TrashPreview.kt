package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.valerioisufi.drawview.explorer.DocumentThumbnails
import io.github.valerioisufi.drawview.explorer.LocalLibraryClock
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_days_left
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_read_only
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_restore
import io.github.valerioisufi.drawview.store.TrashedItem
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.TonalButton
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_close
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.DrawViewElevation
import io.github.valerioisufi.drawview.ui.theme.elevation
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The pages of a document of the trash ([item]), read only: drawn by [thumbnails] one after the
 * other as the store gives them, at the width of the window, kept while the preview is open.
 * Nothing is opened in an editor or a session. Restore puts the document back and closes the
 * preview; Close and Escape close it.
 */
@Composable
internal fun TrashPreview(
    item: TrashedItem,
    thumbnails: DocumentThumbnails,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceContainerLow)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else false
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DesignIconButton(DrawViewIcons.Close, stringResource(DrawViewRes.string.common_action_close), onDismiss, tint = colors.onSurface)
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val days = daysLeft(item, LocalLibraryClock.current())
                    Text(
                        stringResource(Res.string.explorer_trash_read_only) + " · " + pluralStringResource(Res.plurals.explorer_trash_days_left, days, days),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TonalButton(onClick = onRestore) {
                    Icon(DrawViewIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.explorer_trash_restore), maxLines = 1)
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { minOf(maxWidth, MAX_PAGE_WIDTH).toPx() }.toInt().coerceAtLeast(1)
                val pages = remember(item.id) { mutableStateListOf<ImageBitmap>() }
                var loading by remember(item.id) { mutableStateOf(true) }
                LaunchedEffect(item.id, widthPx) {
                    pages.clear()
                    loading = true
                    var index = 0
                    while (true) {
                        val page = thumbnails.page(item.id, index, widthPx) ?: break
                        pages += page
                        index++
                    }
                    loading = false
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(pages) { _, page ->
                        Image(
                            bitmap = page,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .widthIn(max = MAX_PAGE_WIDTH)
                                .fillMaxWidth()
                                .aspectRatio(page.width.toFloat() / page.height.coerceAtLeast(1))
                                .elevation(DrawViewElevation.Level1, RoundedCornerShape(4.dp))
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                        )
                    }
                    if (loading) {
                        item {
                            Box(modifier = Modifier.padding(24.dp)) { CircularProgressIndicator() }
                        }
                    }
                }
            }
        }
    }
}

/** The widest a page of the preview is drawn. */
private val MAX_PAGE_WIDTH = 820.dp
