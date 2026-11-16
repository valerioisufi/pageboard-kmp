# Shapes: following the pen after the snap, lines joined to the boxes

Two things left for later by `docs/plans/shapes.md` and `docs/plans/shape-insertion.md`: adjusting a shape
while the pen is still held after it snapped, and connectors (arrows that stay joined to the boxes
when the boxes move). This document starts from the code as it is (main at 9d7e1184, 2026-11-14).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The snap** (`ShapeSnapper`, `CanvasGestureHandler.onHold`): a stroke held still at its end
   becomes its shape (`ShapeRecognizer`), drawn again in the engine through the points of the shape;
   the moves after it add nothing (`strokeSnapped`), and the lift commits it.
2. **The shape elements** (`ShapeElement`, type `drawview.shape`): a line goes from the middle of the
   left side of its box to the middle of the right side; the ends of a line snap to the anchors of
   the other shapes (`ShapeGeometry.anchors`: corners, middles of the sides, center, inside the
   outline by half its width) when it is drawn or its ends are dragged.
3. **Moving** a selection makes one `PlaceElements` at the drop (`SelectionManager.transform`);
   resizing a shape by its handles makes one at the release (`ShapeEditing.endHandle`).

## Requirements

1. **After the snap, the pen still down**: the shape follows it, turning and growing around its
   pivot (the start of a line, an arrow, a broken line; the middle of a closed shape) as the pen
   moves from where it was held; lifting commits it; one step of undo, as before. An arrow keeps the
   length of its head.
2. **Connectors**: an end of a line (or an arrow) on an anchor of a box shape follows that anchor
   when the box is moved, turned (with the selection) or resized by its handles, in the same step of
   undo; the other end stays. A line selected with the box moves with the selection, as it did.
3. **Nothing stored or sent changes**: no new field in the shapes, no migration, no change to what
   travels. A line is joined to a box as long as its end is on one of its anchors.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The pivot of a shape and the shape turned and scaled as the pen goes from a point to another | `Shape.pivot`, `Shape.followed` (`drawview-model`, `model/shape/`) | Information Expert (each shape knows its geometry), Pure Fabrication |
| The shape that snapped, while its pen is down; drawing it again where the pen goes | `ShapeSnapper.adjust`, `release` (`drawview-canvas`) | Information Expert (it made the shape), Controller |
| Sending the moves after the snap to it | `CanvasGestureHandler` (Mode.INK, `strokeSnapped`) | Controller |
| Which lines are joined to the shapes moved, and their new boxes | `ShapeConnectors` (`drawview-canvas`, internal), from `ShapeGeometry.anchors`, `lineEnds`, `lineBox` | Pure Fabrication, Low Coupling (no data stored) |
| Adding them to the change of the move, of the resize | `SelectionManager.transform`, `ShapeEditing.endHandle` | Creator (of the change), Controller |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `Shape.pivot`, `Shape.followed`; `ShapeSnapper.adjust` and `release`, the gesture; `ShapeConnectors` in the move and the resize; tests (`ShapeFollowTest`, `ShapeGesturesTest`, `ShapeToolTest`); ABI of `drawview-model` recorded on purpose | Done |

### Phase 1 results

* `Shape.followed(pivot, from, to)` maps every point of the shape by the turn and the scale taking
  `from` to `to` around `pivot`; an ellipse keeps its kind (its radii scaled, its rotation turned);
  a pen within 1 mm of the pivot leaves the shape as it is.
* The pen after the snap: `ShapeSnapper` keeps the shape, its pivot, the point where the pen was
  held and the inputs drawn; each move draws the shape again through `strokeInputs`, the live stroke
  replaced; the lift commits the engine as before (one stroke, one step of undo).
* `ShapeConnectors.follow`: an end within 0.3 mm of an anchor of a shape moved goes where the anchor
  goes (the transform of the selection for a move, the place of the anchor in the new box for a
  resize); the line gets the box of its new ends (`ShapeGeometry.lineBox`).
* Tests: `ShapeFollowTest` (a line, an arrow keeping its head, a square and a circle around their
  middle, a pen on the pivot); `ShapeGesturesTest.aLineThatSnappedFollowsThePenStillDown` (pointer
  events: the line turned, still straight, undone in one step); `ShapeToolTest` (a line joined to a
  box dragged with it, a line apart left, undone in one step; a line joined to the side of a box
  resized by its handle).

## Decisions

Taken alone on 2026-11-14 (the user asked to go on without asking), every one revertible:

1. **Joined by geometry, not by a stored link**: no field, no migration, nothing new travels; the
   lines drawn before this work join their boxes too. The cost: two shapes whose anchors meet by
   chance are joined as well, and the line does not follow the box while it is dragged, only at the
   drop (the preview shows the box alone).
2. **A text inside a shape is left for later**: it needs the label in the value of the shape (a new
   field of `ShapeElement`, its version 2), which changes what travels between the devices; CLAUDE.md
   asks to agree on it with the user first.
3. **The pivot of a closed shape is its middle**: it grows evenly; the corner held follows the pen
   only in its distance and its direction from the middle.

## Left for later

* A text inside a shape (a label in a box), with the version 2 of `ShapeElement` and its migration,
  agreed with the user (it changes the shapes that travel).
* The lines following the box while it is dragged (the preview of the move).
* A stored link from a line to its box, if the joins by geometry prove ambiguous.
