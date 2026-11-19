package io.github.valerioisufi.drawview.ui.extension

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.ui.command.Command

/**
 * Content contributed to the editor by the application (e.g. an assistant), composed with the
 * editor of every open document; each contribution receives the [EditorContext] of the document.
 *
 * Written as a subclass overriding the contributions it makes, or with the builder
 * `EditorExtension { toolbarActions { context -> … } }`. A contribution added to this class in a
 * later version is an open member with a default: the extensions compiled before keep working
 * (`docs/plans/editor-ports.md`). Several extensions are combined with `+` (e.g. an assistant and the
 * collaboration).
 */
public abstract class EditorExtension {
    /** Actions added to the top bar of the editor, before the drawing mode toggle. */
    public open val toolbarActions: @Composable RowScope.(context: EditorContext) -> Unit get() = {}

    /**
     * Content drawn over the canvas (e.g. hints), the dialogs of the extension and the effects
     * following the editor: composed with it, always; the canvas keeps receiving the pointer events
     * outside of the interactive elements of the overlay.
     */
    public open val canvasOverlay: @Composable BoxScope.(context: EditorContext) -> Unit get() = {}

    /**
     * The actions of the extension in the menus and the shortcuts of the editor (e.g. "Export…"):
     * the same as its toolbar buttons, which can show them (the `command` of a `ToolButton`).
     */
    public open val commands: (context: EditorContext) -> List<Command> get() = { emptyList() }

    /**
     * The panels of the extension (e.g. the lecture assistant): the editor places them beside the
     * canvas or over it, by the space it has.
     */
    public open val panels: (context: EditorContext) -> List<EditorPanel> get() = { emptyList() }

    /**
     * Why the document may only be read, when the extension knows (e.g. "Valerio’s Mac decides who
     * edits"): shown by the editor beside "Read only".
     */
    public open val readOnlyNote: @Composable (context: EditorContext) -> String? get() = { null }

    /**
     * The actions of the extension on the pages selected in the page grid (e.g. "Export…"), in the
     * bar of the selection: they read the pages from the selection of `context.pages`.
     */
    public open val pageGridActions: (context: EditorContext) -> List<Command> get() = { emptyList() }

    /**
     * What follows the caret of the text box being written, suggested by the extension (e.g. a
     * model completing the sentence); `null` when it suggests nothing (see [TextSuggester]).
     */
    public open val textSuggestions: TextSuggester? get() = null

    /**
     * The contributions of this extension followed by those of [other]: toolbar actions, commands,
     * panels and actions of the page grid in this order, [other]'s overlay drawn over this one, the
     * note of the read only documents of this one if it has one, the suggestion of this one if it
     * suggests something.
     */
    public operator fun plus(other: EditorExtension): EditorExtension = CombinedExtension(this, other)
}

/** Builds an extension of the contributions set in [build]. */
public fun EditorExtension(build: EditorExtensionBuilder.() -> Unit): EditorExtension =
    EditorExtensionBuilder().apply(build).build()

/**
 * The contributions of an extension made by the builder `EditorExtension { … }`, one setter each
 * (see the members of [EditorExtension]); a contribution not set is the default.
 */
public class EditorExtensionBuilder internal constructor() {
    private var toolbarActions: (@Composable RowScope.(EditorContext) -> Unit)? = null
    private var canvasOverlay: (@Composable BoxScope.(EditorContext) -> Unit)? = null
    private var commands: ((EditorContext) -> List<Command>)? = null
    private var panels: ((EditorContext) -> List<EditorPanel>)? = null
    private var readOnlyNote: (@Composable (EditorContext) -> String?)? = null
    private var pageGridActions: ((EditorContext) -> List<Command>)? = null
    private var textSuggestions: TextSuggester? = null

    /** See [EditorExtension.toolbarActions]. */
    public fun toolbarActions(content: @Composable RowScope.(context: EditorContext) -> Unit) {
        toolbarActions = content
    }

    /** See [EditorExtension.canvasOverlay]. */
    public fun canvasOverlay(content: @Composable BoxScope.(context: EditorContext) -> Unit) {
        canvasOverlay = content
    }

    /** See [EditorExtension.commands]. */
    public fun commands(of: (context: EditorContext) -> List<Command>) {
        commands = of
    }

    /** See [EditorExtension.panels]. */
    public fun panels(of: (context: EditorContext) -> List<EditorPanel>) {
        panels = of
    }

    /** See [EditorExtension.readOnlyNote]. */
    public fun readOnlyNote(of: @Composable (context: EditorContext) -> String?) {
        readOnlyNote = of
    }

    /** See [EditorExtension.pageGridActions]. */
    public fun pageGridActions(of: (context: EditorContext) -> List<Command>) {
        pageGridActions = of
    }

    /** See [EditorExtension.textSuggestions]. */
    public fun textSuggestions(suggester: TextSuggester) {
        textSuggestions = suggester
    }

    internal fun build(): EditorExtension {
        val toolbar = toolbarActions
        val overlay = canvasOverlay
        val commandsOf = commands
        val panelsOf = panels
        val note = readOnlyNote
        val gridActions = pageGridActions
        val suggester = textSuggestions
        return object : EditorExtension() {
            override val toolbarActions get() = toolbar ?: super.toolbarActions
            override val canvasOverlay get() = overlay ?: super.canvasOverlay
            override val commands get() = commandsOf ?: super.commands
            override val panels get() = panelsOf ?: super.panels
            override val readOnlyNote get() = note ?: super.readOnlyNote
            override val pageGridActions get() = gridActions ?: super.pageGridActions
            override val textSuggestions get() = suggester
        }
    }
}

/** Two extensions as one (see [EditorExtension.plus]). */
private class CombinedExtension(private val first: EditorExtension, private val second: EditorExtension) : EditorExtension() {
    override val toolbarActions: @Composable RowScope.(EditorContext) -> Unit = { context ->
        first.toolbarActions(this, context)
        second.toolbarActions(this, context)
    }
    override val canvasOverlay: @Composable BoxScope.(EditorContext) -> Unit = { context ->
        first.canvasOverlay(this, context)
        second.canvasOverlay(this, context)
    }
    override val commands: (EditorContext) -> List<Command> = { context -> first.commands(context) + second.commands(context) }
    override val panels: (EditorContext) -> List<EditorPanel> = { context -> first.panels(context) + second.panels(context) }
    override val readOnlyNote: @Composable (EditorContext) -> String? = { context -> first.readOnlyNote(context) ?: second.readOnlyNote(context) }
    override val pageGridActions: (EditorContext) -> List<Command> = { context -> first.pageGridActions(context) + second.pageGridActions(context) }

    // The suggestion of the first extension, else of the second
    override val textSuggestions: TextSuggester? = when {
        first.textSuggestions == null -> second.textSuggestions
        second.textSuggestions == null -> first.textSuggestions
        else -> TextSuggester { context, writing ->
            first.textSuggestions?.suggest(context, writing) ?: second.textSuggestions?.suggest(context, writing)
        }
    }
}
