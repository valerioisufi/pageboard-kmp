package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.canvas.render.HiddenElements
import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.SelectionActivity
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The presence of the editor among the others working on the document: what the user is doing
 * here ([activity], for the others), and the other devices drawn over the document
 * ([collaborators], told by the synchronization, see `pageboard-sync-ui`).
 */
public class CollaboratorPresence internal constructor(
    private val document: DrawDocument,
    private val hiddenElements: HiddenElements,
    private val invalidate: (DrawPage, Rect?) -> Unit,
    private val onCollaboratorsChanged: () -> Unit
) {
    private val currentActivity = MutableStateFlow<EditorActivity?>(null)
    private var pointerActivity: EditorActivity? = null
    private var textDraft: TextData? = null
    private var textDraftPageId: String? = null
    private var selectionActivity: SelectionActivity? = null

    /**
     * What the user is doing on the document now (the pointer, the stroke being drawn, the text
     * being written, the elements selected), for the others working on it; `null` when nothing.
     */
    public val activity: StateFlow<EditorActivity?> = currentActivity.asStateFlow()

    /** Where the pointer is and the stroke being drawn (from the gestures). */
    internal fun reportActivity(activity: EditorActivity?) {
        pointerActivity = activity
        publishActivity()
    }

    /** The text being written on the page [pageId] as it is now (`null`: none). */
    internal fun reportTextDraft(pageId: String?, text: TextData?) {
        textDraftPageId = pageId
        textDraft = text
        publishActivity()
    }

    /** The elements selected (`null`: none). */
    internal fun reportSelection(selection: SelectionActivity?) {
        selectionActivity = selection
        publishActivity()
    }

    private fun publishActivity() {
        val activity = (pointerActivity ?: EditorActivity()).copy(text = textDraft, textPageId = textDraftPageId, selection = selectionActivity)
        currentActivity.value = activity.takeUnless { it.isIdle }
    }

    private var shownCollaborators by mutableStateOf(emptyList<Collaborator>())

    /**
     * The other devices working on the document, drawn over it: where they are, the strokes they
     * are drawing, the texts they are writing and the elements they select (kept by the
     * synchronization, see `pageboard-sync-ui`). The texts they write and the elements they move
     * are hidden from the content meanwhile (see [HiddenElements]).
     */
    public var collaborators: List<Collaborator>
        get() = shownCollaborators
        set(value) {
            showByCollaborators(shownCollaborators.hiddenElementIds(), value.hiddenElementIds())
            shownCollaborators = value
            onCollaboratorsChanged()
        }

    /**
     * The other device writing the text [element] (see [collaborators]), if any: meanwhile the
     * text can be neither edited nor selected here (the user is told, see [editsRefused]).
     */
    internal fun writerOf(element: DrawElement): Collaborator? = shownCollaborators.firstOrNull { it.text?.id == element.id }

    private val refusedEdits = MutableSharedFlow<Collaborator>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The other devices writing the texts the user tried to edit or to select here. */
    public val editsRefused: SharedFlow<Collaborator> = refusedEdits.asSharedFlow()

    /** Tells that the text [writer] is writing was not opened or selected here. */
    internal fun refuseEdit(writer: Collaborator) {
        refusedEdits.tryEmit(writer)
    }

    /** The elements the collaborators draw themselves: the texts written, the elements moved. */
    private fun List<Collaborator>.hiddenElementIds(): Set<String> = buildSet {
        for (collaborator in this@hiddenElementIds) {
            collaborator.text?.let { add(it.id) }
            if (collaborator.selectionTransform != null) collaborator.selection.forEach { add(it.id) }
        }
    }

    private fun showByCollaborators(before: Set<String>, after: Set<String>) {
        if (before == after) return
        for (id in before - after) setShownByCollaborator(id, false)
        for (id in after - before) setShownByCollaborator(id, true)
    }

    private fun setShownByCollaborator(id: String, shown: Boolean) {
        val (page, element) = document.findElement(id) ?: return
        if (element !is DrawStroke && element !is DrawText && element !is DrawImage && element !is DrawCustomElement) return
        hiddenElements.setShownByCollaborator(id, shown)
        invalidate(page, page.boundsOf(element))
    }
}
