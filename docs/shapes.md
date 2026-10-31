# Geometric shapes

A stroke drawn with the pen or the highlighter and held still at its end becomes the shape it
looks like: a straight line, an arrow, a broken line, a circle or an ellipse, a triangle, a
rectangle or a polygon. Today every stroke stays as the hand drew it, and there is no way to make
a straight line, a box around a definition or the axes of a graph other than drawing them by hand.
This document starts from the code as it is (main at dede5d77, 2026-10-31): how a stroke is drawn
and stored today, then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **A stroke is its inputs.** `DrawStroke` keeps the `StrokePoint`s it was drawn with (x, y in
   page millimeters, pressure, time) and its `BrushConfig`; the mesh is modeled again from them on
   every device (`StrokeModeller`, Google Ink in the stores and editors). What is stored and
   synchronized is the inputs, not the mesh.
2. **How a stroke is drawn.** `CanvasGestureHandler` (`drawview-canvas`) starts a stroke on the
   first down of an ink tool (`Mode.INK`), feeds each move to `InkStrokeEngine.addInput`, shows
   `engine.currentMesh()` on the live layer, and on the last up hands `engine.finishStroke()` to
   `DrawViewController.commitInkStroke`, which adds it (`AddElements`) on every page it covers.
   The engine can also be fed a list of points at once (`InkStrokeEngine.addInputs`).
3. **The long press.** A press that has not moved for `longPressTimeoutMillis` cancels the stroke
   and opens the context menu. Once the pointer moved beyond the touch slop there is no timer any
   more: a pen held still in the middle of a stroke does nothing.
4. **Shapes today.** Only the sample has shapes: an element type of its own (`ShapeType`, a star,
   an ellipse, a rectangle, filled), added in the middle of the page by a button. It shows how an
   application adds a type; it is not a tool for notes.
5. **The preferences of the editor.** `DrawPreferences` (JSON, `ignoreUnknownKeys`) holds the
   presets of the tools and the switches of the editor (`isSnapEnabled`, `isStylusOnlyMode`).

## Requirements

1. **Shapes that notes need**, and only those:
   * a **line** (underlining, tables, the sides of a figure), straight to 0°, 45° and 90° when
     near them;
   * an **arrow** (diagrams, vectors, flows): a line with a head drawn at its end in the same
     stroke;
   * a **broken line** of two to five segments (graphs, the axes of a plot drawn as one stroke);
   * an **ellipse**, a **circle** when it is nearly round (sets, nodes, circling a result);
   * a **triangle**, a **rectangle** (boxes around definitions and results), a **square** when it
     is nearly one, a **polygon** of five or six sides;
   * no decorative shapes (stars, hearts, clouds).
2. **Drawn, not picked from a menu**: the pen stays the pen. A stroke held still at its end for a
   moment becomes its shape while the pen is still down; lifted without holding it stays as drawn.
   It works with the stylus, the finger and the mouse.
3. **The shape is a stroke**: the color, size and family of the preset (pressure pen, marker,
   dashed line, highlighter), drawn evenly. It is erased, selected, moved, rotated, resized,
   copied, exported and synchronized as any stroke.
4. **Nothing stored or sent changes**: the shape is a stroke whose inputs are the points of the
   shape; no Room migration, no change of the protocol (`ProtocolFormatTest` as it is); other
   devices and older versions receive and draw it as a stroke.
5. **Undo** takes the shape away in one step, as a stroke.
6. **A stroke that looks like no shape stays as drawn**, held or not: no wrong guess.
7. **It can be switched off**: a preference of the user, on by default, kept with the others
   (`DrawPreferences`, read from the preferences written before it with its default).
8. **Every platform**: the recognition is headless common code, the gesture common code of
   `drawview-canvas`.

## Design

### The experience

* Draw a line and keep the pen still at its end: after about half a second the stroke turns into
  a straight line, under the pen. Lift: it stays. The same for a circle, a box, a triangle...
* Lift right away: the stroke is the one drawn, as today.
* Nothing recognized: the stroke stays as drawn, and lifting commits it.
* The switch "Hold to draw shapes" is in the settings of the pen and of the highlighter (one
  preference for both).

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| What a drawn stroke looks like: the shape and its parameters, or nothing | `ShapeRecognizer` (`drawview-model`, `model/shape/`) | Pure Fabrication (no model object owns the recognition), High Cohesion (geometry only, no Compose), Protected Variations (a better recognizer, a learned one, can replace it behind the same function) |
| The shapes and the points that draw each one | `sealed interface Shape` with `Line`, `Arrow`, `Polyline`, `Ellipse`, `Polygon` and `points()` | Information Expert (each shape knows its geometry), Polymorphism (no `when` on a kind outside them) |
| Regularizing a shape: angles to 0°/45°/90°, a circle, a square | `ShapeRecognizer` (after the fit) | Information Expert (it knows the fit and how far it is) |
| Noticing that the pen is held still at the end of a stroke | `CanvasGestureHandler` (`drawview-canvas`), a timer like the one of the long press | Controller (it already owns the pointer events and the stroke in progress) |
| Turning the stroke in progress into its shape: recognize, give the engine the points of the shape, show it | `ShapeSnapper` (`drawview-canvas`, internal), used by the gesture handler | Indirection (the gesture handler does not know the recognition), Low Coupling |
| Committing the shape | `DrawViewController.commitInkStroke`, as it is | Protected Variations (the shape enters the document as any stroke) |
| The preference | `DrawPreferences.isShapeRecognitionEnabled`, `ToolManager` / controller state | Information Expert (the preferences of the editor live there) |
| The switch | the settings of the pen and the highlighter (`drawview-ui`) | Low Coupling (the UI calls the controller only) |

### Recognition

`ShapeRecognizer.recognize(points: List<StrokePoint>, tolerance: Float): Shape?`, with a
`tolerance` in page millimeters given by the caller from the zoom (a few dp on the screen), so that
a shape drawn small on a zoomed page is recognized as well as a big one.

1. **Clean**: drop the points of the hold at the end (the pen still), resample at even spacing.
2. **Closed or open**: closed when the ends are nearer than about a fifth of the diagonal of the
   bounds.
3. **Open**:
   * a line when no point is farther than the tolerance (relative to the length) from the segment
     between the ends;
   * otherwise the corners by Ramer–Douglas–Peucker: an **arrow** when the last one or two
     segments are short and turn back by more than 120° around the end of a long one (the head);
     a **broken line** of two to five segments otherwise; nothing with more corners.
4. **Closed**:
   * an **ellipse** fit from the moments of the points (center, axes, rotation): an ellipse when
     the normalized radius of every point stays near 1; a **circle** when the axes differ by less
     than about a tenth;
   * otherwise the corners by Ramer–Douglas–Peucker on the closed path: three, a **triangle**;
     four with right angles (within about 12°), a **rectangle**, a **square** when the sides
     differ by less than about a tenth, a quadrilateral as a polygon otherwise; five or six, a
     **polygon**; nothing with more.
5. **Regularize**: a line, a side of a rectangle, the axis of an ellipse within about 6° of 0°,
   45° or 90° takes that angle.

The thresholds are constants of the recognizer, tuned with the tests (synthetic strokes with
noise, and strokes recorded by hand in the tests' resources).

### The points of a shape

* Edges at the even spacing of the pen (about 0.5 mm), each corner repeated, so that Google Ink
  keeps the corners sharp (checked by the tests: the mesh of a rectangle within half a stroke of
  its corners).
* An ellipse by 64 points or more, by its perimeter.
* Pressure: the median of the stroke drawn (the width the user saw), the same on every point; the
  input tool type of the stroke drawn.
* Times at an even speed over the time the stroke took.

### The gesture

* In `Mode.INK`, after the pointer moved beyond the slop, each move farther than a few dp from
  the last still position restarts a timer of about 500 ms (`withTimeoutOrNull`, like the long
  press). When it fires, `ShapeSnapper.snap(engine, …)`: recognize the inputs; if a shape comes
  back, cancel the stroke in the engine, start it again with the same brush, add the points of the
  shape, show the new mesh. The stroke is marked as snapped: later moves do not add inputs, the
  up commits it as it is.
* Nothing recognized: the stroke goes on as drawn (the user may keep drawing).
* The presence of the collaborators (`reportActivity`) shows the shape as the stroke in progress:
  the snapped stroke counts as a new stroke (`strokeCount`), so that its inputs travel from the
  first one (the presence sends only the inputs not sent yet of the same stroke) and the other
  devices start its preview again. Nothing of the protocol changes.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `Shape` and `ShapeRecognizer` in `drawview-model` (`model/shape/`): line, arrow, broken line, ellipse, circle, triangle, rectangle, square, polygon, the regularization, the points of each shape; tests on synthetic strokes with noise and on strokes that are no shape (handwriting, spirals, scribbles) that must give nothing; a test that the Ink mesh of a rectangle and a triangle keeps its corners; ABI of `drawview-model` recorded on purpose | Done |
| 2 | The hold at the end of a stroke in `CanvasGestureHandler` and `ShapeSnapper` in `drawview-canvas`: the live mesh replaced by the shape, committed on the up, undone in one step; `DrawPreferences.isShapeRecognitionEnabled` (default on) with a test reading the preferences written before it; tests of the gesture (held: a shape; lifted: the stroke; held on a scribble: the stroke) | Proposed |
| 3 | The switch in the settings of the pen and of the highlighter (`drawview-ui`), its screenshots (`verifyRoborazziJvm`); the desktop application on a scratch directory, the shapes drawn with the mouse and checked after a restart and in the PDF export | Proposed |

### Phase 1 results

* `Shape` (`drawview-model`, `model/shape/`) is sealed: `Line`, `Arrow` (its `wings` at 28° from
  the shaft), `Polyline`, `Polygon` (triangles, rectangles, squares, polygons of five and six
  sides), `Ellipse` (`isCircle`). Each gives its `path` and whether its points are corners;
  `Shape.strokeInputs(drawn)` gives the inputs of the stroke.
* What changed from the design, found by the tests:
  * **Closed** is decided by the last 30% of the stroke coming back near its start, not by its
    ends alone: a circle drawn past its start is closed, the part past it cut away.
  * **The sides of a polygon are fit lines**: the line through the middle 70% of the points of each
    side, the corners where two of them meet. The corners found first lie on a corner drawn round,
    which pulled the sides inside: boxes with round corners were not recognized.
  * **The corners of a broken line must be sharp**: the direction turns by 60% of the angle within
    3.5% of the points around it. A smooth curve (an S) was taken for a broken line of five
    segments.
  * **The pace of the inputs**: a point every 0.5 mm every 6 ms, and a stop of 60 ms in six inputs
    on each corner. At 2 ms and 8 ms Google Ink cut the corners (the ink reached 0.14 mm past a
    corner of a 1 mm stroke instead of 0.5 mm).
* Tests: `ShapeRecognizerTest` (every shape; flicks at the end of a line; arrows with an open and a
  closed head; a rectangle starting where the stroke started; no shape for handwriting, a spiral, a
  scribble, a curve, an open arc, a dot; each kind drawn by 30 hands at three amounts of drift, 990
  strokes, all right), `ShapeStrokeTest` in `drawview-ink` (the Ink mesh of a rectangle and of a
  triangle reaches past each corner by 80% of its half width or more, pressure pen and marker).

## Decisions

* **A shape is a stroke, not an element of its own** (revertible): nothing stored or sent changes,
  and every tool that works on strokes works on shapes. The price: a shape is not edited as a
  shape after it is drawn (its corners cannot be dragged, it has no fill); it is moved, resized
  and rotated by the selection as any stroke.
* **Recognized on a hold, not on every stroke** (revertible): a stroke is never changed unless the
  user asks for it, so handwriting is never mistaken for a shape.
* **One preference for the pen and the highlighter** (revertible).

## Left for later

* Adjusting a shape while the pen is still held after it snapped (dragging resizes it, turns it,
  or moves the end of a line), as other note applications do.
* A shape tool for the mouse and the trackpad: dragging a box or a segment gives a perfect shape
  without drawing it.
* Filled shapes and shapes edited by their points: an element of their own (a new element type,
  so a change of the stored format and of what travels).
* Axes with ticks, grids, arcs and angles for geometry; arrows that stay attached to the boxes
  they join.
