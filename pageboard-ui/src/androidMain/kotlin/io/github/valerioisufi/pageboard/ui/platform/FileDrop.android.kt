package io.github.valerioisufi.pageboard.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The files dragged from the other applications are not taken yet (content URIs, item providers). */
@Composable
public actual fun Modifier.fileDropTarget(onFiles: (List<PickedFile>) -> Boolean): Modifier = this
