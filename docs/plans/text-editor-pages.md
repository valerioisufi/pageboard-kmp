# Moving the text being written to another page

A text box moved with the lasso selection goes to the page under its center. The box of the text
being written does not: it stays on its page, however far it is dragged. This document starts from
the code as it is (main at 6d4d4af5, 2026-10-30), then gives the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The page of the editor.** `TextEditorManager` (`drawview-canvas`) holds the box in the
   millimeters of one page, `activeTextPageIndex` (public), set once by `open` and never changed
   while the text is written. The text edited (`activeTextItem`) stays in the document on that
   page, hidden (`hiddenElements.setDragged`) until the editor closes.
2. **The drag.** `CanvasGestureHandler` (mode `EDITOR_MOVE`) keeps the origin of the box and the
   offset of the pointer in the millimeters of that page (`editorMoveOrigin`, `editorMoveOffset`),
   divided by `pixelsPerMm(activeTextPageIndex)`, also while the camera scrolls at the edge
   (`onAutoScroll`), and calls `TextEditorManager.moveTo(x, y, free)`.
3. **The bound.** `moveTo` keeps `MIN_VISIBLE_MM` of the box on its page
   (`coerceIn(MIN_VISIBLE_MM - boxWidth, page.widthMm - MIN_VISIBLE_MM)`): this is why it never
   leaves it.
4. **Closing.** `finishEditing` saves on `activeTextPageIndex`: `UpdateTexts(page.id, …)` for a
   text edited, `AddElements` for a new one. `cancelEditing` shows the text again on that page.
5. **Who reads the page.** `TextBoxEditor` and `TextEditorOverlay` (`drawview-ui`) and
   `SelectionOverlay` place the field and the frame with `pageMmToScreen(activeTextPageIndex)`;
   `TextBoxEditor` takes the paper of that page; `PageManager.deletePages` closes the editor when
   its page is deleted; the draft shown to the other devices names that page (`reportDraft`).
6. **`UpdateTexts` moves a text.** It places each text on its page `pageId`, wherever the text
   is (`context.place`), and its inverse puts it back on the page it came from.
7. **The selection does it already.** `SelectionManager.transform` takes the page under the center
   of the moved selection (`findPageIndexAtWorld(…, nearest = true)`), maps the placements to it
   through the world, and submits `PlaceElements(oldPage, placements, newPage)` with
   `ReorderElements.onTop(newPage, ids)` in one `CompositeChange`.

## Requirements

1. **The box of the text being written goes to the page under its center** when dragged, live:
   the field, the frame, the snapping guides and the rows of the paper are the ones of that page
   while it is over it.
2. **The writing goes on**: the caret, the text, the selection and its undo steps in the field stay
   as they are when the box changes page.
3. **On closing**: a new text is added to the page it is on; a text edited and moved is moved to
   that page and changed there in one change (one step to undo), on top of its elements, like a
   selection moved there.
4. **Cancelled** (Escape, a page deleted, the text removed on another device): the text edited is
   shown again where it was, on its page.
5. **Its page deleted**: deleting the page the text edited is on, or the page the box is over,
   closes the editor as now.
6. **Nothing stored or sent changes**: `ReorderElements`, `UpdateTexts`, as they are; no migration, no change of the protocol.
7. **Every platform**: the rule lives in `drawview-canvas` (common code); the UI follows
   `activeTextPageIndex`, which is already observable.

## Design

### The experience

The box follows the pointer over the gap between the pages and onto the next page; when its center
is over another page, it belongs to it: its frame snaps to that page, the field takes its paper.
On Done the text is on that page. Undo puts it back on the page it came from, as it was.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The page of the box: the one under its center, its corner mapped there through the world | `TextEditorManager.moveTo` (`drawview-canvas`) | Information Expert (it holds the box and its page), High Cohesion (the bound and the page in one place) |
| The page the text edited came from, apart from the page of the box | `TextEditorManager.itemPageIndex` (new, private) | Information Expert |
| Saving on another page: `UpdateTexts(to, …)` + `ReorderElements.onTop` in one `CompositeChange` | `TextEditorManager.finishEditing` | Creator (the editor makes the change of what it edited), Protected Variations (the changes as they are, no new one) |
| The drag in world coordinates, so that it does not depend on the page | `CanvasGestureHandler` (`EDITOR_MOVE`, `onAutoScroll`) | Controller (the gesture only says where the pointer is), Low Coupling |
| Closing the editor when the page of the text edited or of the box is deleted | `PageManager.deletePages` | Information Expert |

### The editor

```kotlin
/**
 * Moves the box to ([xMm], [yMm]) of its page; when its center is then over another page, the
 * box goes to that page (its corner mapped there). A part of it always stays on the page it
 * is on; unless [free], it is snapped to what is around it.
 */
public fun moveTo(xMm: Float, yMm: Float, free: Boolean = false)
```

`moveTo` maps the center of the box to the world (`pageMmToWorld`), takes the page there
(`findPageIndexAtWorld(…, nearest = true)`, as the selection does) and, when it is another page,
maps the corner to its millimeters and sets `activeTextPageIndex`; then it snaps and bounds on that
page as now. The box is never scaled: the pages share the millimeters of the world.

The gesture keeps the origin of the box and the offset of the pointer in the world, and gives
`moveTo` the corner in the millimeters of the page the box is on at each step, so that a change of
page in the middle of the drag does not shift the box.

`finishEditing`, for a text edited whose page (`itemPageIndex`) differs from the page of the box:

```kotlin
CompositeChange(listOf(
    UpdateTexts(to.id, listOf(data)),
    ReorderElements.onTop(to.id, listOf(existing.id))
))
```

`cancelEditing` shows the text again on `itemPageIndex`, and an emptied text is removed from it.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | The box goes to the page under its center while dragged (editor + gesture in world coordinates); saving on that page (new and edited text, one change); cancel and page deletion on the page of the text; tests of `TextEditorManager` (move across pages, finish, undo, cancel) (`TextEditorPagesTest`), the drag across the pages (`TextGesturesTest`) | done |

## Decisions

Taken alone, revertible:

1. **Live, not on release**: the box belongs to the page under its center while it is dragged
   (snapping and paper of that page), unlike the selection, which changes page on release. The
   field is a live object on screen; changing page only at Done would snap it to the wrong page.
2. **The center decides**, with `nearest = true`, as for the selection: in the gap between two
   pages the box belongs to the nearest one.
3. **The bound stays** (`MIN_VISIBLE_MM` on its page): it now applies to the page the box is on,
   so it never stops the box from leaving it towards another page.

## Left for later

- `moveBy` (public, no caller in the repository) goes through `moveTo`, so it changes page too.
