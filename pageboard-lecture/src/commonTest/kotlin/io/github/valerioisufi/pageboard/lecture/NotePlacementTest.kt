package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.placement.BesideAnchor
import io.github.valerioisufi.pageboard.model.placement.InMargin
import io.github.valerioisufi.pageboard.model.placement.PageBottom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** The place and the width of a note by its kind (see `docs/plans/auto-placement.md`). */
class NotePlacementTest {

    private val anchor = Rect(20f, 50f, 150f, 60f)

    @Test
    fun eachKindHasItsPlace() {
        assertIs<InMargin>(NotePlacement.policyOf(NoteKind.DEFINITION, anchor))
        assertIs<InMargin>(NotePlacement.policyOf(NoteKind.FORMULA, anchor))
        assertIs<BesideAnchor>(NotePlacement.policyOf(NoteKind.EXAMPLE, anchor))
        assertSame(PageBottom, NotePlacement.policyOf(NoteKind.ANNOUNCEMENT, anchor))
    }

    @Test
    fun eachKindHasItsWidthWithinThePage() {
        assertEquals(60f, NotePlacement.widthOf(NoteKind.DEFINITION, anchor, 210f))
        assertEquals(130f, NotePlacement.widthOf(NoteKind.EXPLANATION, anchor, 210f))
        assertEquals(90f, NotePlacement.widthOf(NoteKind.EXPLANATION, null, 210f))
        assertEquals(60f, NotePlacement.widthOf(NoteKind.EXAMPLE, Rect(20f, 50f, 40f, 60f), 210f))
        assertEquals(190f, NotePlacement.widthOf(NoteKind.ANNOUNCEMENT, anchor, 210f))
        // A narrow page
        assertEquals(60f, NotePlacement.widthOf(NoteKind.ANNOUNCEMENT, null, 80f))
    }
}
