package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.placement.BesideAnchor
import io.github.valerioisufi.pageboard.model.placement.FreeSpace
import io.github.valerioisufi.pageboard.model.placement.InMargin
import io.github.valerioisufi.pageboard.model.placement.PageBottom
import io.github.valerioisufi.pageboard.model.placement.PlacementPolicy
import kotlin.math.min

/**
 * Where and how wide a note of the lecture goes on its page, by its kind (see `docs/plans/auto-placement.md`):
 * a definition, a formula or a reference in the margin beside what it is about, narrow; an
 * explanation, an example or a theorem below it, as wide as it; an announcement at the bottom of
 * the page, across it.
 */
public object NotePlacement {

    /** The width of a note in the margin. */
    public const val MARGIN_WIDTH_MM: Float = 60f

    /** The width of a note below nothing in particular. */
    public const val NOTE_WIDTH_MM: Float = 90f

    /** How [kind] is placed next to [anchor] (`null`: nothing on the page it is about). */
    public fun policyOf(kind: NoteKind, anchor: Rect?): PlacementPolicy = when (kind) {
        NoteKind.DEFINITION, NoteKind.FORMULA, NoteKind.REFERENCE -> InMargin(anchor)
        NoteKind.ANNOUNCEMENT, NoteKind.EXAM_HINT -> PageBottom
        NoteKind.EXPLANATION, NoteKind.EXAMPLE, NoteKind.THEOREM, NoteKind.OTHER -> BesideAnchor(anchor)
    }

    /** The width [kind] prefers on a page [pageWidthMm] wide, next to [anchor]. */
    public fun widthOf(kind: NoteKind, anchor: Rect?, pageWidthMm: Float): Float {
        val most = pageWidthMm - 2 * FreeSpace.DEFAULT_PAGE_MARGIN_MM
        val width = when (kind) {
            NoteKind.DEFINITION, NoteKind.FORMULA, NoteKind.REFERENCE -> MARGIN_WIDTH_MM
            NoteKind.ANNOUNCEMENT, NoteKind.EXAM_HINT -> most
            NoteKind.EXPLANATION, NoteKind.EXAMPLE, NoteKind.THEOREM, NoteKind.OTHER ->
                anchor?.width?.coerceAtLeast(MARGIN_WIDTH_MM) ?: NOTE_WIDTH_MM
        }
        return min(width, most).coerceAtLeast(min(30f, most))
    }
}
