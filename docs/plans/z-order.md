# Z-order

The stacking order of the elements of a page, chosen by the user: an element brought to the front
or sent to the back, forward or backward past the next element it overlaps, alone or with others
selected, on every platform, kept and synchronized like everything else. This document starts from
the code as it is (main at a4d1be5a, 2026-10-25): how a page stacks its elements today, then the
design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **An order per kind.** Every element has an `ElementOrder(stamp, index)` (`Stamp.kt`): the
   stamp of the change that put it on its page and its position in that change, written as
   `stamp/index` (text order = stacking order). The newest is on top. It travels in
   `Placement.order` and `ElementData.order` (a string in `ProtocolFormats`) and is stored as
   text (`orderKey` in the Room entities, the JSON of `KeyValueDocumentStore`, the operations log).
2. **Layers fixed by kind.** A page is painted kind by kind (`PageContentSnapshot.paintOrder`): the
   PDF pages, the marks of their text, the images, the elements of the applications, the texts,
   the strokes; the order counts only inside a kind. An image is never over the ink, and a
   highlighter stroke drawn after the writing tints it (25 % alpha, painted in order). The tiles,
   the thumbnails and `PdfExporter` follow `paintOrder`.
3. **Only "on top" can be said.** A `PlaceElements` without order gives the elements the order of
   the change (moving to another page puts them on top); nothing can put an element below another
   or between two: `(stamp, index)` is not dense.
4. **One register for the placement.** The page, the order and the box (or the transform) of an
   element are one last-writer-wins register (`placed`): a move and a reorder made apart on two
   devices keep one of the two, and undoing a reorder writes back the box it had then (moving the
   element back if another device moved it since).
5. **Pages.** They are ordered by fractional keys (`FractionalIndex`, then by id); `MovePages.toOrder`
   moves the fewest.
6. **The canvas.** The selected elements are left out of the tiles and drawn over them from the
   moment they are selected (`HiddenElements`, `SelectionManager.setSelection`): a change of order
   would not show while they are selected. `clearSelection` bakes the strokes back into the tiles
   (`bakeElementsSync`), right only for elements on top. A long press looks for the element under
   it by kind (`CanvasGestureHandler.showContextMenu`: the elements of the applications, then the
   images). The other devices' selections are hidden from the tiles only while they move.
7. **Undo.** `placementOf` holds the order: the inverse of a change of order comes for free.
8. **Stamps from other devices** are bounded since `docs/plans/untrusted-members.md` phases 4 and 5:
   `DocumentSession.admits` refuses an operation stamped ahead or carrying a stamp after its own
   (`DocumentChange.stamps()`, the orders' stamps among them). `DrawDocument.latestStamp()` reads
   the stamps of the orders to keep the clock after them. An older version parsing a key that
   looks like `hex.hex.id/hex` would take it for a stamp, possibly far ahead.
9. **Commands and menus.** `EditorCommands` defines every action once (`Command`, `Shortcut`,
   `CommandGroup`): the menu bar of the desktop (`DrawViewMenuBar`), the shortcuts (`CommandHost`),
   the tooltips, the lists of iPadOS and Android. The selection bar (`SelectionActionBar`) has
   edit, cut, copy, delete and the style of the texts; the context menu of the canvas
   (`CanvasContextMenu`) is the one of the page. No command orders the elements.

## Requirements

1. Four commands on the selection: **Bring to Front**, **Bring Forward**, **Send Backward**,
   **Send to Back**; one element or several.
2. **One stack for the content of a page**: images, elements of the applications, texts and
   strokes are ordered together. The PDF pages and the marks of their text stay under it, fixed:
   they are the paper.
3. **Forward and backward pass the next element the selection overlaps**: every press changes
   what is seen (a step in the whole stack of a page of two thousand strokes would most often show
   nothing). Several elements move as a block, their order among themselves kept.
4. A command that would change nothing is disabled (the selection is already above, or below,
   everything it overlaps).
5. **The order is seen as it is** while the elements are selected; they are lifted over the rest
   only while they are moved, scaled or rotated.
6. **The notes look the same**: every page stored or received before this version is painted
   exactly as before (each kind in its layer, in its order); nothing stored is rewritten to get
   there. The new elements go on top of everything.
7. **Moves and reorders are apart**: a move and a reorder of the same element made on two devices
   both stay; undoing a reorder changes the order only.
8. **Convergence**: devices applying the same operations in any causal order end with the same
   stack (`ConvergenceTest` with reorders).
9. **Untrusted input**: an order received is checked (its form, its length, not after the stamp
   of its operation): a member cannot put an element above every future "Bring to Front", nor
   send keys without bound. Refused, the operation closes the connection; the application goes on.
10. **Every platform**: the selection bar, the context menu (long press, secondary click), the
    menu bar of macOS, Windows and Linux (and the lists of iPadOS and Android, from the commands),
    the shortcuts; undo and redo as for every change.

## Design

### The experience

* **Selection bar.** One button, "Arrange" (the icon of the layers), after delete: a menu of the
  four commands with their icons, their shortcuts where there is a keyboard. One button only: the
  bar of a phone has no room for four.
* **Context menu.** A long press or a secondary click on a selected element, or on an element (it
  becomes selected), opens the menu of the selection: cut, copy, delete, and the four commands.
  Elsewhere on the page it is the menu of the page, as now.
* **Menu bar.** A menu "Arrange" after "Format" (as in Keynote and Pages): the four commands.
* **Shortcuts.** Those of Keynote and Pages, with letters, so that they work on every layout (on an
  Italian keyboard `[` and `]` need ⌥, and ⌘] ⌘[ ⌥⌘] ⌥⌘[ of Figma cannot be told apart):

  | Command | macOS, iPadOS | Windows, Linux, Android |
  |---|---|---|
  | Bring to Front | ⇧⌘F | Ctrl+Shift+F |
  | Bring Forward | ⌥⇧⌘F | Ctrl+Shift+Alt+F |
  | Send Backward | ⌥⇧⌘B | Ctrl+Shift+Alt+B |
  | Send to Back | ⇧⌘B | Ctrl+Shift+B |

  The brackets of Figma and Office (⌘] ⌘[ ⌥⌘] ⌥⌘[, Ctrl elsewhere) run them too, not shown.
* **What is seen.** The selected elements stay at their level, under the frame and the handles:
  a command shows its result at once. Pressed to be moved, they are lifted over the rest; dropped,
  they go back to their level.
* **Undo.** Every command is one step of the history.
* **The stack of a new element.** On top of everything: a pasted image covers the ink under it
  (before, it went under all the strokes).

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| A position in the stack: comparing, a key between two (dense), the key of a change on top, the key of an order stored before (read in the layer of its kind), its form and its bounds | `ElementOrder` (`drawview-model`) | Information Expert, Protected Variations (the format of the keys) |
| The stack of a page: its content in order, kept sorted as elements come and go | `DrawPage` (`stack`, `PageElements`), `PageContentSnapshot.paintOrder` | Information Expert |
| The new orders of a selection moved in the stack (front, forward, backward, back): the fewest keys, none when nothing changes | `ReorderElements.of(page, ids, StackMove)` (`drawview-model`) | Pure Fabrication, Creator (like `MovePages.toOrder`) |
| Changing the order of elements, the last change winning on every device, undone by the previous orders | `ReorderElements`, the register `DrawElement.ordered` | Information Expert, Polymorphism (`DocumentChange`) |
| Refusing an order received that is malformed, too long, or after the stamp of its operation | `DocumentSession.admits` with `DocumentChange.orders()` | Information Expert, Protected Variations |
| Keeping the order register (stored, snapshot) | the Room entities and `Migration19To20`, `ElementVersions.ordered` (`KeyValueDocumentStore`, snapshots) | Information Expert |
| The use case "arrange the selection": submitting the change, keeping the selection | `SelectionManager.arrange`, `canArrange` (`drawview-canvas`) | Controller |
| The selection at its level, lifted during a gesture, drawn until the tiles hold it again | `SelectionGroup.isLifted`, `SelectionManager` (lift, settle), `TileManager.isRegionCurrent` | Information Expert, Low Coupling |
| The element under a point, the topmost in the stack | `DrawPage.stack` (the canvas taking the last one its hit test finds) | Information Expert |
| The four commands, their shortcuts, menu and icons | `EditorCommands` (`CommandGroup.ARRANGE`), `SelectionActionBar`, `CanvasContextMenu` | Controller, Polymorphism (the presenters of `Command`) |

### Keys

An `ElementOrder` is a key compared as text, of printable ASCII (`!` to `~`), at most 256
characters, never ending with `!`:

* **Layers of the orders written before.** A stored or received `stamp/index` (it starts with a
  hexadecimal digit) is read in the layer of its kind: `P` PDF pages, `Q` marks, `R` images, `S`
  elements of the applications, `T` texts, `U` strokes, followed by the text as it was. The pages
  stored before look the same: each kind above the previous ones, in its order. Nothing is
  rewritten: the key read is the one written when the element is saved again.
* **On top.** The key of a change putting elements on top (a new element, "Bring to Front", a move
  to another page) is `V` followed by the stamp of the change and the position in it: above every
  key before it, and deterministic (made from the stamp only, not from what the device has).
* **Between.** "Forward", "backward" and "back" write keys between two others (a fraction in base
  94, as `FractionalIndex` in base 62), made by the editor and carried by the change. Every key is
  above `G`, so that it never starts with a hexadecimal digit.
* **Ties** (the same key made on two devices) are broken by the id of the element.
* **Old versions** read every new key as no order (it starts with a letter that is not
  hexadecimal): they never take it for a stamp. The new version no longer reads stamps out of the
  orders (`latestStamp` reads the register `ordered`).
* **Received**: a key of the new form must not be after `V` + the stamp of its operation + `/~`,
  the greatest key of that change: no element above the next "Bring to Front"; an order stored
  before keeps the check of its stamp (`stamps()`).

### The order register

`DrawElement.ordered` is the stamp of the last change of its order. `ReorderElements` writes the
order of the elements it names whose `ordered` is older; `AddElements` and `AddPage` write it with
the elements they add or put back. `PlaceElements` and `UpdateTexts` never write it, a move to
another page included: the editor sends that move with a `ReorderElements.onTop` of the page it
goes to, in one change (writing the order only when the element changes page would depend on
where the element is on each device, and the devices would part). The elements stored before get
`ordered` = `placed` (Room migration, snapshots without it): for them nothing changes.

### Stack moves

The selection is ordered as in the stack; its bounds are the union of the bounds of its
elements.

* **Front**: on top of everything (keys of the change), when an element not selected above one of
  them overlaps the selection.
* **Back**: under the lowest element of the stack, when one not selected below overlaps it.
* **Forward**: just above the lowest element not selected that is above the highest selected
  one and overlaps the selection; the selection becomes one block there.
* **Backward**: just under the highest element not selected that is below the lowest selected one
  and overlaps the selection.

### The canvas

* The selection is not hidden from the tiles when it is made: the tiles keep drawing it at its
  level and the overlay draws the frame. `beginTransformation` (the press on the selection) lifts
  it: hidden from the tiles, drawn live on top. `applyTransformation` puts it back: shown in the
  tiles again, drawn by the overlay until the tiles of its region are drawn again
  (`TileManager.isRegionCurrent`), so that it never disappears for a frame. The bake of
  `clearSelection` is no longer needed.
* The overlay draws the selected elements (lifted) in the order of the stack.
* A long press takes the topmost element under it (the last one of `DrawPage.stack`).
* Pasted elements keep their order among themselves.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | The order of the model, stored and synchronized: `ElementOrder` keys (layers of the orders before, on top, between, bounds, form), the stack of a page and `paintOrder`, the register `ordered` and `ElementVersions.ordered`, `ReorderElements` with `StackMove`, moves and texts leaving the order, `admits` checking the orders, `latestStamp`; Room 20 (`Migration19To20` tested with `MigrationTestHelper`), the key-value store; `ProtocolFormatTest` recorded again under version 1, `ConvergenceTest` with reorders, `docs/plans/collaboration.md`; the editor putting what it moves to another page on top there; tests (the look of the pages before, keys between their bounds, moves of a selection, undo, a move and a reorder apart, refused orders), the sync modules on the iOS simulator; ABI of `drawview-model` | Done |
| 2 | Canvas: the paint order of the tiles and of the overlay, the selection at its level and lifted during a gesture, `isRegionCurrent`, the topmost element under a long press, `SelectionManager.arrange` and `canArrange`, the order of pasted elements; tests; ABI of `drawview-canvas` | Done |
| 3 | Interface: the four commands and `CommandGroup.ARRANGE` (menu bar, shortcuts, tooltips), the button and its menu in the selection bar, the context menu of a selection, the icons, the strings in the 7 languages; tests of the commands, screenshots; ABI of `drawview-ui`; the desktop application on a scratch directory | Done |

The model, its stores and the format of what travels change together: a phase stores and sends
the register it adds, so that its checks pass.

The format of what travels changes (keys, `reorderElements`, `ElementVersions.ordered`): it is a
change of the protocol, recorded again under version 1 as agreed (the two devices of the user
updated together, after a last synchronization).

### Phase 1 results

* `ElementOrder` (`drawview-model`) is a key of printable ASCII compared as text (`key`, at most
  `MAX_LENGTH` = 256 characters): `parse` (a malformed text is no order), `onTop(stamp, index)`
  (`V`, the stamp, the position), `between(below, above[, count])` (base 94, by halves), and
  internally `inLayer` (an order `stamp/index` written before read as the letter of its kind
  followed by it: `P` PDF pages, `Q` marks, `R` images, `S` elements of the applications, `T`
  texts, `U` strokes), `built` (a page built outside of the changes, as it always was),
  `spread` (a stack keyed again), `isWellFormed`, `isNotAfter(stamp)`. Every element reads its
  order in its layer when it is set.
* `DrawPage.stack` (the images, the elements of the applications, the texts and the strokes by
  `DrawPage.stackOrder`: order, then id), made once per content; `PageContentSnapshot.stack` and
  `paintOrder` = PDF pages + marks + stack, so the tiles, the thumbnails and the PDF export paint
  the stack; `detached` keeps the orders.
* The register `DrawElement.ordered`, in `latestStamp` (instead of the stamps of the orders), in
  the snapshots (`ElementVersions.ordered`, `null` read as `placed`).
* `ReorderElements(pageId, orders: List<ElementOrdering>)`: each element takes its order (`null`:
  on top, the order of the change) when its `ordered` is older, its previous orders as its
  inverse; the page of the change is versioned whatever the devices find, as `PlaceElements`.
  `ReorderElements.of(page, ids, StackMove)` (`StackMoves`, Pure Fabrication) moves a selection
  as a block past what its bounds overlap, `null` when nothing would change, the stack keyed again
  when the keys would be too long; `ReorderElements.onTop(pageId, ids)`.
* `PlaceElements`, `UpdateTexts` (and `AddElements` placing an element again) never change the
  order; `placementOf` no longer gives one. `AddElements` writes the register of the elements it
  adds or puts back. The first run of `ConvergenceTest` with reorders found that writing the order
  only on a change of page made the devices part (where an element is differs between devices
  while changes travel): a move to another page now goes with `ReorderElements.onTop`, as the
  editor sends it (`SelectionManager.applyTransformation`).
* `DocumentChange.orders()`: `DocumentSession.admits` refuses an operation carrying a malformed
  order, or one after the greatest key of its stamp (`V` + stamp + `/~`); an order written before
  keeps the check of its stamp (`stamps()`).
* Room 20: the column `ordered` in the six tables of the elements, `Migration19To20` (the column
  added, filled with `placed`); `DrawDatabaseMigrationTest` checks on a database of version 19
  holding every kind, with stamps mixing the kinds, that the page is painted as before and that
  every element gets `ordered` = `placed`. The key-value store reads a document stored before (its
  orders `stamp/index`, no register) with the same stack and `ordered` = `placed`.
* `ProtocolFormatTest` recorded again under version 1: `reorderElements`, `ElementOrdering`,
  `ElementVersions.ordered`. `ConvergenceTest` makes reorders (the four moves, keys between two
  made apart, moves to another page put on top) and compares the register: 30 seeds of 150 steps
  in the build, verified on 600 seeds of 300 steps.
* Tests: `ElementOrderTest` (a page written before keeps its look, a page built keeps its layers,
  a new element on top of everything, keys between their bounds on 3000 random cases, on top above
  the keys before it, the versions before reading every new key as no order, malformed keys, the
  stack keyed again), `StackMoveTest` (forward, backward, front, back, blocks, moves that change
  nothing, undo, the same moves on two devices, the same key made apart, a move and a reorder
  apart both kept, undo leaving the move of another device, an edit of a text keeping its order,
  a move to another page, the orders received checked). ABI of `drawview-model` recorded again on
  purpose: `ElementOrder` replaced, `ReorderElements`, `ElementOrdering`, `StackMove`,
  `DrawElement.ordered`, `DrawPage.stack`, `PageContentSnapshot.stack`, `DocumentChange.orders`.

### Phase 2 results

* The selection is drawn at its level: `setSelection` no longer leaves it out of the tiles. It is
  lifted (`SelectionGroup.isLifted`: hidden from the tiles, drawn live by the overlay) by
  `beginTransformation` and by every live change (`moveBy`, `translateBy`, `scaleBy`,
  `rotateBy`, `resizeText`, whoever calls them), and put back by `applyTransformation` (also after
  a press without a move) and `clearSelection`. Put back, its elements are drawn by the overlay
  until the tiles of their region are drawn again (`SettlingElements`,
  `TileManager.isRegionCurrent`: none of the tiles drawn there in the frame stale), so that they
  never disappear for a frame; the bake of `clearSelection` (`bakeElementsSync`) is gone: it drew
  the strokes on top, right only for the topmost ones.
* The overlay draws the lifted and the settling elements in the order of the stack, as the
  overlay of the collaborators draws what they move; the tiles, the thumbnails and the export
  paint `paintOrder` (phase 1).
* `SelectionManager.arrange(StackMove)` and `canArrange` (read by the composition: it follows the
  selection): one change, one step of the history, the selection kept at its new level.
* The context menu (long press, secondary click) on the selection keeps it and opens its menu;
  elsewhere it selects the topmost image, element of an application or text under the pointer in
  the stack (`DrawPage.stack`), or drops the selection for the menu of the page. A long press on
  the selection (not with a mouse) puts it back at its level and opens its menu, the text of a PDF
  under it left alone.
* Pasted elements keep the order of what was copied among themselves, on top of the page.
* Tests: `ArrangeTest` (the selection at its level until moved, lifted while moved and after a
  press, arranged, undone and redone, elements moved to another page on top there, pasted in their
  order), `EditorCoreTest` and `CustomElementEditingTest` (a selection lifted only while it
  moves). ABI of `drawview-canvas` recorded again on purpose: `SelectionGroup.isLifted`,
  `SelectionManager.arrange`, `canArrange`.

### Phase 3 results

* `EditorCommands.bringToFront`, `bringForward`, `sendBackward`, `sendToBack` (`arrangements`):
  enabled with a selection that can move (`SelectionManager.canArrange`), in the document that can
  be written; the shortcuts of Keynote and Pages first (⇧⌘F, ⌥⇧⌘F, ⌥⇧⌘B, ⇧⌘B; Ctrl+Shift+F,
  Ctrl+Shift+Alt+F, Ctrl+Shift+Alt+B, Ctrl+Shift+B elsewhere), the brackets of Figma after them
  (⌥⌘], ⌘], ⌘[, ⌥⌘[), run but not shown; `keyName` writes `[` and `]`.
* `CommandGroup.ARRANGE` (after `FORMAT`, the menu "Arrange", "Disponi"): the menu bar of the
  desktop, the list of the shortcuts of Android and the labels of the menus of the system take it
  from the commands.
* The selection bar: a button "Arrange" (the icon of the layers) after delete, opening the
  `CommandMenu` of the four commands, their shortcuts written where there is a keyboard.
* The context menu of a selection (`CanvasContextMenu`): cut, copy, delete, then the four
  commands, with their icons, their shortcuts and their states; elsewhere the menu of the page.
* Icons `ArrowUpToLine` and `ArrowDownToLine` (Lucide) for the front and the back, the chevrons
  for one step; the strings in the 7 languages (the words of Keynote and Office: "Porta in primo
  piano", "Porta avanti", "Porta indietro", "Porta sullo sfondo").
* Tests: `CommandTest` (the commands disabled without a selection, enabled by what the selection
  overlaps, run by ⇧⌘F, ⌥⇧⌘F, Ctrl+Shift+B and Ctrl+[, written as each platform writes them, one
  step of the history each; every key named and no shortcut shared). Screenshots: the menu of a
  selection over another text (`selection-menu`), the selection bars recorded again with their new
  button. ABI of `drawview-ui` recorded again on purpose (`CommandGroup.ARRANGE`, the strings).
* The desktop application run on a scratch directory (`-Ddrawview.dataDir`): it starts, makes its
  database at version 20, no error. Its window was not driven by hand: the screenshots and the
  tests above show the menus and the commands; trying them on the user's devices is left to the
  user.

## Decisions

Proposed on 2026-10-24 and taken by the user on 2026-10-25 ("one stack", and "write the plan and
implement it"), every one revertible:

1. One stack for the content; the PDF pages and their marks fixed under it.
2. New elements on top of everything, ink included.
3. Forward and backward pass the next element the selection overlaps (bounds, not shapes); a
   selection moves as a block.
4. The order in its own register (`ordered`), apart from the placement: a Room migration and a
   new change, so that a move and a reorder made apart both stay and undo changes the order only.
5. The orders written before read in the layer of their kind, never rewritten.
6. The shortcuts of Keynote (letters), the brackets of Figma as aliases.
7. Protocol version 1, the format recorded again.
8. No layers panel, no lock: the page is the group.

Taken alone while writing the plan, revertible:

9. Keys of printable ASCII in base 94, at most 256 characters; a page whose keys would grow past
   that gets its keys made again (one change of order of its whole stack).
10. The menu bar names the menu "Arrange" (Disponi), after Format.
11. The bounds of a stroke are its box: a diagonal line overlaps what its box covers.

## Risks

* **A change of semantics for the operations already logged.** A move on the same page logged
  with an order no longer writes it: the devices that applied it with the old code and the ones
  applying it with the new could differ when it was made apart from another change of order.
  Synchronizing the two devices before updating them avoids it.
* **Tiles drawn again on a press**: lifting the selection rasterizes its region; the overlay keeps
  drawing it meanwhile, so the cost is time, not a flash.
* **The look of the pages before**: the layers of the orders written before give the same stack;
  the migration test and a test of a page of every kind check it.

## Left for later

* Selecting the element under another (a long press again on the same place going down the
  stack).
* Locking an element as a background.
* Layers.
