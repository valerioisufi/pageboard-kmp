package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Takes the files dropped onto the content from the system (a PDF or an image dragged from the
 * files of the computer): [onFiles] receives them and tells whether it took them. The desktop
 * delivers them; on the other platforms the modifier does nothing yet.
 */
@Composable
public expect fun Modifier.fileDropTarget(onFiles: (List<PickedFile>) -> Boolean): Modifier
