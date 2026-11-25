# Advanced Shapes: Labels, Conversion, Live Connectors, Free Vertices, Geometry Tools & Collaborator Presence

This plan extends the shapes system (`docs/plans/shapes.md`, `docs/plans/shape-insertion.md`, and `docs/plans/shape-adjust.md`) with the remaining capabilities left for later: labels inside shapes, stroke-to-shape conversion, persisted connector links with live drag previews, vertex-editable free polygons and polylines, geometry tools (axes with ticks, grids, arcs, angles, interactive ruler & protractor), and real-time collaborator presence for shapes in progress.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator, Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected Variations).

## Starting point

1. **`ShapeElement` v1** (`pageboard-model`, `model/shape/ShapeElement.kt`): `ShapeElementType` (`pageboard.shape`, version 1) holds `kind` (`LINE`, `RECTANGLE`, `ROUNDED_RECTANGLE`, `ELLIPSE`, `TRIANGLE`, `RIGHT_TRIANGLE`, `DIAMOND`, `PENTAGON`, `HEXAGON`), `stroke`, `widthMm`, `dash`, `fill`, `startHead`, `endHead`.
2. **Stroke recognition & snap** (`ShapeRecognizer`, `ShapeSnapper`): holding a stroke still at its end turns it into a `DrawStroke`/`StrokeElement` using the points of the recognized shape.
3. **Connectors** (`ShapeConnectors`): lines joined to boxes by geometric proximity (`JOINED_MM = 0.3f`) only move when the selection or handle drag ends (`PlaceElements`); there is no live preview during drag and no persisted link.
4. **Presence** (`EditorActivity`, `Presence`, `CollaboratorsOverlay`): streams pointer position, in-progress ink strokes, text being written, and active selection transforms, but not shapes being drawn or resized.

## Requirements

1. **Text/label inside a shape**: `ShapeElement` v2 with `label`, `labelColor`, `labelFontSizePt`, and lossless migration from v1 CBOR payloads (`ShapeElementType.migrate`). Labels render in tiles, overlays, thumbnails, image export, and PDF export, and are readable by `pageboard-agent`.
2. **Stroke-to-shape conversion**: convert a selected stroke recognized by `ShapeRecognizer` into an editable `ShapeElement` from the selection bar (one undo step, preserving stack order), and optionally snap directly to an editable `ShapeElement` when holding a stroke via a preference (`snapToEditableShape`).
3. **Live connectors & persisted links**: lines/arrows store `startAnchor` / `endAnchor` (`ShapeAnchorRef`) when snapped to another shape (with geometric proximity fallback for v1 lines) and follow attached boxes in real time during drag and resize previews.
4. **Free polygons & polylines**: `POLYLINE` and `FREE_POLYGON` kinds with normalized `vertices` inside their box, multi-tap creation, and interactive vertex/midpoint handles (`Handle.VERTEX`, `Handle.ADD_VERTEX`, vertex deletion).
5. **Geometry tools**: `AXES` (with ticks), `GRID`, `ARC`, and `ANGLE` shape kinds with configurable `ShapeGeometryParams`, plus interactive on-canvas **Ruler** and **Protractor** guides (`GeometryGuidesManager`) that project pen strokes onto their edges/rims.
6. **Collaborator presence**: stream `ShapeDraftActivity` in `EditorActivity` / `Presence` (validated on receipt) and render remote drafts live in `CollaboratorsOverlay`.
7. **Sync & safety**: keep `PROTOCOL_VERSION = 4` (maintainer updates devices together), re-record `ProtocolFormatTest`, validate all untrusted CBOR fields (`ShapeElement.init`, `ShapeDraftActivity.isValid()`).

## Design

| Responsibility | Class / Module | GRASP |
|---|---|---|
| Shape v2 value, constraints, and v1→v2 migration | `ShapeElement`, `ShapeElementType` (`pageboard-model`) | Information Expert, Protected Variations |
| Geometry, hit-testing, anchors, and vector drawings for all kinds | `ShapeGeometry` (`pageboard-model`) | Pure Fabrication, High Cohesion |
| Converting recognized hand strokes into editable shapes | `ShapeConverter` (`pageboard-model`) | Pure Fabrication |
| Shape label rendering across Skiko and Android canvases | `drawShapeLabel` (`pageboard-canvas`) | Polymorphism, Protected Variations |
| Persisted anchor resolution & live drag/resize previews | `ShapeConnectors`, `SnapManager` (`pageboard-canvas`) | Information Expert, Indirection |
| Multi-vertex insertion, vertex handles, and stroke conversion | `ShapeInserter`, `ShapeEditing`, `SelectionFrame` (`pageboard-canvas`) | Controller, Polymorphism |
| Interactive ruler and protractor guides + ink projection | `GeometryGuidesManager` (`pageboard-canvas`) | Controller, Information Expert |
| Shape draft streaming and remote rendering | `ShapeDraftActivity` (`pageboard-model`), `Presence` (`pageboard-sync`), `CollaboratorsOverlay` (`pageboard-canvas`) | Low Coupling, Protected Variations |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `ShapeElement` v2 (labels, `POLYLINE`, `FREE_POLYGON`, `AXES`, `GRID`, `ARC`, `ANGLE`, `ShapeAnchorRef`, `ShapeGeometryParams`, v1→v2 migration), `ShapeGeometry`, `ShapeConverter`, label rendering in canvas & PDF, agent reading, `ShapeElementFormatTest`; ABI recorded | Pending |
| 2 | Persisted connector links (`SnapManager.anchorHitFor`, `ShapeInserter`, `ShapeConnectors`), live connector preview during drag & resize (`SelectionManager`, `ShapeEditing`, `SelectionOverlay`, `PageCanvas`), stroke-to-shape conversion (`ShapeEditing`, `ShapeSnapper`, `EditorPreferences.snapToEditableShape`); tests; ABI recorded | Pending |
| 3 | Vertex creation & editing (`ShapeInserter`, `SelectionFrame`, `ShapeEditing`), `GeometryGuidesManager` (ruler & protractor), UI (`ShapeSettings`, `SelectionActionBar`, `InkSettings`, `EditorCommands`, 7 locales); tests; ABI recorded | Pending |
| 4 | Real-time collaborator presence for shape drafts (`ShapeDraftActivity`, `Presence`, `SyncEngine`, `CollaboratorPresence`, `CollaboratorsOverlay`), `ProtocolFormatTest` re-recorded under v4; JVM & iOS simulator sync tests; ABI recorded | Pending |

## Decisions (revertible)

1. **`ShapeElement` v2 stays a `CustomElement` (`pageboard.shape`)**: reuses Room `custom_elements`, undo/redo, selection, and sync operations without changing the Room schema for shapes.
2. **Persisted connector links with geometric fallback**: v2 lines record `ShapeAnchorRef(elementId, anchorIndex)` when snapped, while v1 lines without refs continue to use `JOINED_MM = 0.3f`.
3. **Protocol version stays 4**: agreed with maintainer; `ProtocolFormatTest` is re-recorded.

## Left for later

* Agent `propose` support for inserting shapes with labels or connectors directly.
* Multi-page connectors spanning across two different pages.
