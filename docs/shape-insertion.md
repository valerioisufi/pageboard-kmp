# Inserting shapes

A shapes tool next to the pen: a line, an arrow, a box, an ellipse, a triangle... dragged on the
page, perfect at once, then edited as a shape (its ends, its corners, its color, its outline and
its fill). Today the only way to get a shape is to draw it with the pen and hold the pen still at
its end (`docs/shapes.md`): the shape becomes a stroke, perfect but no longer a shape. This
document starts from the code as it is (main at c99460eb, 2026-11-13): what exists, the
requirements, the experience, the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Shapes drawn by hand** (`docs/shapes.md`, in main since 2026-11-01). `ShapeRecognizer`
   (`drawview-model`, `model/shape/`) turns a stroke held still at its end into a `Shape` (`Line`,
   `Arrow`, `Polyline`, `Polygon`, `Ellipse`), and `ShapeSnapper` (`drawview-canvas`) gives the
   ink engine the points of the shape: what is stored is a stroke. It cannot be filled, its ends
   cannot be dragged, and it has the look of the pen (pressure, marker, dashed).
2. **Element types of the applications.** `ElementType<T>` (`drawview-model`) is a kind of element
   whose value is CBOR in a `DrawCustomElement`: a box (x, y, width, height, rotation) and a
   payload with its `version`. The documents store it (`CustomElementEntity`, Room as it is), the
   changes and the sync carry it (`AddElements`, `PlaceElements`, the update of the payload as a
   whole), the selection moves, rotates, resizes and copies it by its box, and a device without
   the type draws a placeholder in its box. `ElementRenderer<T>` (`drawview-canvas`) draws it in
   the millimeters of its box and says what hits it; `ElementRenderers` is given by the
   application (`DrawViewEngines.elements`, `None` in PencilNotes). Only the sample uses it
   (`ShapeType`, a star, an ellipse and a rectangle added in the middle of the page by a button).
   The identifiers `drawview.*` are reserved for the libraries.
3. **The export.** `PdfExporter.writeCustomElement` draws an element of a type as an image at
   300 dpi; the strokes are vector paths (`PdfPath`). The image export draws it by its renderer.
4. **The eraser** erases strokes; it does not touch the elements of the types.
5. **The selection.** `SelectionFrame` has the handles `ROTATION`, `CORNER`, `LEFT`, `RIGHT`
   (the side handles only for a text alone); `SnapManager` snaps a box being moved to the edges
   and centers of the other elements, when the guides are on.
6. **The tools.** `Tool` (`drawview-canvas`): `INK_PEN`, `INK_HIGHLIGHTER`, `TEXT_MARKUP`,
   `ERASER`, `TEXT`, `LAZO`, `PAN`; each has its button in `EditorTools` (`drawview-ui`), its
   settings in a popover (`ToolWithSettings`), a command with a letter (`P`, `H`, `U`, `E`, `T`,
   `L`), and its state in `DrawPreferences` (JSON, `ignoreUnknownKeys`).
7. **The stack.** One stack per page (`docs/z-order.md`): a new element goes on top.

## Requirements

1. **The shapes of notes**, and only those:
   * a **line**, an **arrow**, a **double arrow** (underlining, vectors, flows, diagrams);
   * a **rectangle**, a **rounded rectangle** (boxes around definitions and results, nodes);
   * an **ellipse** and a **circle**;
   * a **triangle**, a **right triangle**, a **diamond** (decisions in a flow chart), a
     **regular polygon** of five or six sides;
   * no decorative shapes (stars, hearts, clouds, speech bubbles).
2. **Drawn by a drag, perfect at once**: from one corner to the other (the boxes) or from one end
   to the other (the lines), with the stylus, the finger, the mouse and the trackpad. A tap puts
   the shape at a standard size where it was tapped.
3. **Exact when the user wants it**: a line straight at 0°, 15°, 30°, 45°... a square, a circle,
   a shape drawn from its center; aligned with the other elements by the guides; the end of an
   arrow on the corner, the middle of a side or the center of another shape.
4. **Edited as a shape after it is drawn**: the ends of a line moved one by one, a box resized
   from its corners and its sides, turned, moved; its kind, color, width, outline (solid, dashed,
   dotted), fill (none, tinted, solid) and arrow heads changed, on one shape or on several at once.
5. **Like every element**: selected by the lasso, erased by the eraser, copied, pasted between
   documents, ordered in the stack, undone in one step, exported (as vectors in the PDF),
   synchronized, read by the agent tools.
6. **The pen stays the pen**: the shapes drawn by hand and held keep working as today.
7. **No user data at risk**: no Room migration; the value of a shape is a new stored format,
   written at version 1, recorded by a test so that a later version migrates it.
8. **Untrusted input**: the value of a shape received from another device is checked (finite
   numbers, sizes and counts within bounds); a value not understood is drawn as a placeholder,
   never fails the application.
9. **Every platform and form factor**: Android phones and tablets, desktop, iOS; the settings in a
   popover or in a sheet as the other tools (`docs/adaptive-ui.md`).

## The experience

### Choosing a shape

* A **Shapes** button in the tools, after the highlighter. Its icon is the shape chosen (a box,
  an arrow, an ellipse...) with the dot of its color, like the pen shows its ink: the user sees
  what a drag will draw before drawing it.
* A tap chooses the tool; a tap on it chosen, or a long press, opens its settings:
  * a **grid of the shapes** (two rows of six on a tablet, the same in a sheet on a phone), the
    one chosen marked;
  * the **color** (the inks of the pen and the color wheel), the **width** (pt, the scale of the
    pen), the **outline** (solid, dashed, dotted), the **fill** (none, tinted: the color at 20%,
    solid), the **heads** for the lines (none, at the end, at both ends);
  * a preview of the shape with its style at the top, as the pen shows its stroke.
* The style is the one of the last shape drawn or edited, kept with the preferences: a box drawn
  after another one looks the same.
* The letter **S** chooses the tool; on the desktop, the menu **Insert ▸ Shape** has every kind
  (the shape put in the middle of the page shown, selected, ready to be dragged where it goes),
  which also serves who cannot drag.

### Drawing

* **Drag**: the shape follows the pointer, drawn as it will be, with a small label near the
  pointer: the size (`42 × 30 mm`) or, for a line, its length and angle (`56 mm · 30°`).
* **Straight and regular by themselves**: a line within 4° of a multiple of 15° takes it; a box
  within 6% of a square, an ellipse within 6% of a circle, becomes one. The label says it
  (`square`, `circle`, `45°`) and a guide shows it, so the snap is never a surprise; moving on
  frees it.
* **Keys on the desktop**: Shift forces it (always square, circle, multiples of 15°), Alt/Option
  draws from the center, both together combine. On a touch screen a second finger held on the
  screen while drawing does what Shift does (the convention of drawing apps on tablets).
* **Guides**: with "Snap to Guides" on, the edges and the center of the shape snap to the ones of
  the other elements, the same guides as when they are moved. The ends of a line or an arrow
  snap to the anchors of the other shapes (corners, middles of the sides, centers), shown as dots
  while the end is near: an arrow joins two boxes exactly.
* **Lift**: the shape is in the document (one step of undo) and shows its handles. The tool stays
  the shapes tool: dragging a handle edits the shape, dragging inside it moves it, dragging
  elsewhere draws the next one and lets the first go.
* **A tap** (no drag): the shape at its standard size (20 mm, a line 30 mm) centered where it was
  tapped, selected.
* **Too small**: a drag shorter than the touch slop is a tap; a shape never ends up of zero size.

### Editing

* A shape is selected by a tap on it with the shapes tool or the lasso, or by the lasso as any
  element. A shape without fill is hit by its outline (a few dp around it), so that a box drawn
  around a text still lets the text be tapped inside.
* **A line or an arrow**: a handle on each end, nothing else; dragging an end keeps the other
  still, with the same snaps as when it was drawn (angles, anchors, Shift).
* **A box, an ellipse, a polygon**: the handles of the frame as for an image (corners, sides,
  rotation); Shift keeps the proportions, Alt resizes from the center.
* **The action bar** of the selection gains, when it holds shapes only: the color, the width,
  the outline, the fill, the heads (lines), and the kind (a rectangle made an ellipse in the same
  box). They change all the shapes selected at once, one step of undo; the change becomes the
  style of the tool.
* Copy, cut, paste, duplicate, delete, order in the stack: as every element.

### What does not change

* The pen and the highlighter, and their shapes held at the end of a stroke.
* The strokes, the texts, the images: nothing of their storage or their look.

## Design

### A shape is an element of a built-in type

The shape is a `DrawCustomElement` of the type `drawview.shape`, declared by the libraries
(`ShapeElementType`), not a new `DrawElement` class:

* the box, the rotation, the stack, the selection, the changes, Room and the sync already carry
  the elements of a type: no Room migration, no new operation, `ProtocolFormatTest` as it is;
* a device or an application without the type draws a placeholder in its box and keeps the shape;
* the type is the API a third party uses (CLAUDE.md: a third-party element type uses the same API
  as the built-in ones), so the shapes prove it.

What travels gains a new kind of payload in the operations that already exist: the frames, the
messages and the operations keep their format. The devices of the user are updated together; an
older version shows the shapes as placeholders until it is updated.

### The value

```kotlin
@Serializable
public data class ShapeElement(
    val kind: ShapeKind,              // LINE, RECTANGLE, ROUNDED_RECTANGLE, ELLIPSE, TRIANGLE,
                                      // RIGHT_TRIANGLE, DIAMOND, PENTAGON, HEXAGON
    val stroke: Int,                  // ARGB of the outline
    val widthMm: Float,               // width of the outline
    val dash: ShapeDash = SOLID,      // SOLID, DASHED, DOTTED
    val fill: Int? = null,            // ARGB of the fill, null = none
    val startHead: ShapeHead = NONE,  // NONE, OPEN, FILLED: the heads of a line
    val endHead: ShapeHead = NONE,
)
```

* **The geometry is the box.** A box shape fills its box (a rounded rectangle's radius is a
  fraction of its smaller side, a triangle has its apex at the middle of the top). A line goes
  from the middle of the left side of its box to the middle of the right side: its box is as long
  as the line, turned by its angle (`rotation`), and as high as its hit band. So moving, turning
  and resizing a shape are the moves of the box the editor has, and the ends of a line are its
  `LEFT` and `RIGHT`.
* **An arrow is a line with heads**, a double arrow a line with both: one kind, the heads a
  style, so that a line becomes an arrow from the action bar.
* **The width is in millimeters of the page**, not of the box: resizing a box does not thicken
  its outline (the renderer draws in the millimeters of the box, which are the ones of the page).
* **Version 1**, `ShapeElementType.version`; a later format migrates it (`migrate`). An enum
  value not known (a kind added later) makes the value unreadable: a placeholder, never a wrong
  shape.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The value of a shape, its format and its checks (finite, width 0.05–20 mm, colors) | `ShapeElement`, `ShapeElementType` (`drawview-model`, `model/shape/`) | Information Expert (the value knows what is valid), Protected Variations (the version and `migrate`) |
| The outline of a shape in its box: the paths, closed or not, the heads, what a point hits, the anchors | `ShapeGeometry` (`drawview-model`, `model/shape/`) | Pure Fabrication (one geometry for the renderer, the PDF, the hit, the eraser, the snaps), High Cohesion (no Compose) |
| Drawing a shape (outline, dash, fill, heads) | `ShapeRenderer` (`drawview-canvas`, `render/`), always in `ElementRenderers` | Polymorphism (an `ElementRenderer` as any type), Low Coupling (the tiles do not know shapes) |
| A shape in the PDF as vector paths | `PdfExporter` asks `ElementRenderers` for an optional `VectorElement` capability; `ShapeRenderer` gives its paths | Polymorphism (an application's type can do the same), Protected Variations (others stay images) |
| The drag that draws a shape: the box from the pointer, the constraints, the snaps, the preview, the commit | `ShapeInserter` (`drawview-canvas`, `controller/`, `controller.shapeInserter`) | Controller (one place for the gesture's decisions), Creator (it makes the element: it has the box and the style) |
| The constraints: angles by 15°, square, circle, from the center | `ShapeConstraints` (`drawview-model`, pure functions) | Pure Fabrication, Information Expert (geometry only, tested headless) |
| The anchors of the other shapes for the ends of a line | `SnapManager.snapPoint`, with `ShapeGeometry.anchors` | Information Expert (the snaps live there), Indirection |
| Routing the pointer of `Tool.SHAPES` to the inserter, a tap or a drag | `CanvasGestureHandler` (`Mode.SHAPE`) | Controller (it owns the pointer events) |
| The handles of a shape: the ends of a line, the frame of a box | `SelectionFrame` (`Handle.START`, `Handle.END`) with `ShapeEditing` deciding them for a shape alone | Polymorphism (the frame asks what handles an element has), Low Coupling |
| Changing the style or the kind of the shapes selected | `ShapeEditing.restyle` through `CustomElementManager.update` (one composite change) | Information Expert, Creator of the change |
| Erasing a shape the eraser touches | `EraserManager` asks `ElementRenderers.hits` along the eraser path | Polymorphism (by the type's hit, so other types can be erased too) |
| The kind and the style of the tool | `ShapeToolState` in `DrawPreferences` (`shapeTool`, with defaults) | Information Expert (the preferences of the editor live there) |
| The button, the settings, the action bar controls, the commands | `ShapeToolButton`, `ShapeSettings`, `ShapeStyleControls` (`drawview-ui`), `EditorCommands.shapes` | Low Coupling (the UI calls the controller only), High Cohesion |
| What the agent reads of a shape | `ElementItems` (`drawview-agent`): the kind, the style and the box | Information Expert |

### The gesture

* `Tool.SHAPES` gives `Mode.SHAPE` on the first down of a pointer that draws (in stylus-only
  mode only the stylus; a finger pans).
* On the down: if it falls on a handle of the shape selected, the handle is dragged (the
  selection as it is); inside the shape selected, it is moved; otherwise `ShapeInserter.begin`.
* Each move: `ShapeInserter.update(point, modifiers)` makes the box from the start and the
  pointer, applies the constraints (`ShapeConstraints`, forced by Shift or by a second finger
  held, by themselves within their thresholds), then the guides (`SnapManager.snapBox` or
  `snapPoint`), and shows the preview on the live layer with the label.
* The up: `ShapeInserter.commit()` adds the element on top (`CustomElementManager.add`, one
  `AddElements`) and selects it; a drag shorter than the slop puts the shape of standard size.
* A cancel (a second finger that zooms, Escape) drops the preview, nothing is added.

### Hostile values

The operation carrying a shape has its nesting checked by the sync (`cborNestsWithin`); its
payload is a byte string the serializer of `ShapeElement` reads without skipping anything (an
unknown key, an unknown kind or a value of the wrong type fails the read), then the checks of
`ShapeElement` (its `init`): a value outside its bounds is not read (a placeholder). The renderer
never allocates from the value (the paths have a bounded number of points, 720 at most for a
curve; the dash pattern is bounded by the width). A test feeds it random bytes and values at the
edges.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `ShapeElement`, `ShapeKind`, `ShapeDash`, `ShapeHead`, `ShapeElementType` (`drawview.shape`, version 1, the checks), `ShapeGeometry` (paths, heads, hits by outline and by fill, anchors), `ShapeConstraints`; tests of every kind, of the hits, of the constraints, of hostile payloads; `ShapeElementFormatTest` recording a payload of every kind (the stored format); ABI of `drawview-model` recorded on purpose | Done |
| 2 | `ShapeRenderer` always in `ElementRenderers`; the vectors in the PDF (`VectorElement`); the eraser erasing the elements a type says it hits; screenshot tests of every kind and style; a PDF export test (vector paths, no image) | Proposed |
| 3 | `Tool.SHAPES`, `Mode.SHAPE` and `ShapeInserter`: the drag, the tap, the constraints (Shift, Alt, a second finger, the thresholds), the guides and the anchors, the preview with its label, the commit selected, undo in one step; gesture tests | Proposed |
| 4 | Editing: `Handle.START`/`END` for a line alone, the frame for the other shapes, the snaps while dragging them, the hit by outline when it has no fill; `ShapeEditing.restyle` and the change of kind; tests | Proposed |
| 5 | The UI: the Shapes button with the icon of its kind and its dot, `ShapeSettings` (grid, color, width, outline, fill, heads, preview), the controls of the action bar, the commands (`S`, menu Insert ▸ Shape), the strings in the seven languages, the icons; `ShapeToolState` in `DrawPreferences` with a test reading the preferences written before it; the toolbar width (`ToolbarLayout`) with one more tool, checked at the widths of `docs/header-fit.md`; screenshots (`verifyRoborazziJvm`); the desktop application on a scratch directory | Proposed |
| 6 | The agent tools: `read_page` lists the shapes (kind, style, box); proposing shapes is left for later | Proposed |

### Phase 1 results

* `ShapeElement`, `ShapeKind`, `ShapeDash`, `ShapeHead` and `ShapeElementType` (`drawview.shape`,
  version 1) in `drawview-model` (`model/shape/ShapeElement.kt`). The checks are the `init` of
  the value: the width from 0.05 to 20 mm, finite; heads only on a line, a fill only on a shape of
  a box. `withKind` and `withStyleOf` change a kind or a style keeping what the other has.
* `ShapeGeometry`: the paths of every kind in its box (the outline inside the box, its outer edge
  on the edge; the curves within 0.005 mm, 720 points at most), the heads of a line (4.5 times
  its width, 2.5 mm at least, 45% of the line at most; a filled head ends the shaft at its base),
  `lineHeight`, `lineBox` and `lineEnds` (a line along its box), `hits` (by the outline, inside
  when filled), `anchors`; `ShapeBox` maps between a box and its page.
* `ShapeConstraints`: `line` (multiples of 15° within 4°, always when forced; its angle read
  counterclockwise from the right) and `box` (a square within 6%, from the center).
* What changed from the design: the fields at their default are not written (the default of the
  CBOR of the library), so the defaults are part of the format, said in the KDoc.
* Tests: `ShapeGeometryTest`, `ShapeConstraintsTest`, `ShapeElementFormatTest` (a payload of
  every kind recorded, read back; a width out of bounds, an unknown kind and a newer version not
  read; 5000 random or damaged payloads, never a failure, never a value out of its checks).

## Decisions

* **A shape is an element of a built-in type, not a stroke** (revertible until it is released):
  it can be filled and edited as a shape, and nothing of Room or of the frames changes. The price:
  an older version draws a placeholder.
* **Not a new `DrawElement` class** (revertible): it would need a Room table, a migration, new
  operations and a protocol change, for what the types already give.
* **An arrow is a line with heads** (revertible): one kind, the heads a style.
* **The geometry is the box, a line along its box** (revertible): every move of the editor works
  on shapes, at the price of no free polygons (their points moved one by one).
* **The tool stays chosen after a shape** (revertible): several boxes in a row are drawn without
  going back to the button; the handles of the last shape are there to correct it.
* **One style for the tool, the last one used** (revertible), not presets as the pen: the shapes
  of a page look the same without managing presets.
* **The shapes held at the end of a stroke stay strokes** (revertible): the pen keeps its look,
  and nothing a user drew changes.

## Left for later

* **Converting** a stroke recognized as a shape (`ShapeRecognizer` on its inputs) into an
  editable shape, from the action bar of the selection; or the shape held at the end of a stroke
  becoming a shape element by a preference.
* **Connectors**: arrows that stay joined to the boxes when the boxes move.
* **A text inside a shape** (a label in a box, a node of a diagram).
* **Free polygons and broken lines** edited by their points.
* **Geometry**: axes with ticks, grids, arcs and angles, a ruler and a protractor.
* **Proposing shapes from the agent tools** (`propose`), placed as the texts are.
* **The shape being drawn** shown to the collaborators as the strokes in progress are.
