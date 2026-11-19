package io.github.valerioisufi.pageboard.ui.extension

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A panel contributed to the editor (e.g. the lecture assistant). The panel says what it shows;
 * the editor, which knows the space and the other panels, decides where: beside the canvas when it
 * keeps room enough, else rising from the bottom over it (see `EditorScaffold`). The editor draws
 * its header: [icon], [title], [actions] and the button closing it.
 *
 * @param id a name unique among the panels of the editor.
 * @param icon the mark of the panel before its title, if any (e.g. the sparkles of an assistant).
 * @param isOpen whether the panel is shown (read in composition: a state of the extension).
 * @param onClose closes the panel (its button, or the sheet dragged down).
 * @param actions buttons of its header, before the one closing it (e.g. its settings).
 * @param content what it shows, under its header; it scrolls its own content.
 */
@Stable
public class EditorPanel(
    public val id: String,
    public val title: @Composable () -> String,
    public val isOpen: () -> Boolean,
    public val onClose: () -> Unit,
    public val actions: @Composable RowScope.() -> Unit = {},
    public val icon: ImageVector? = null,
    public val content: @Composable ColumnScope.() -> Unit
)
