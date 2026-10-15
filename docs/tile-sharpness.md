# Tile sharpness

The content of the documents (ink, paper, PDFs, texts, images) as sharp on screen as the screen
allows. This document starts from the blur seen on a Windows desktop at 100% (3440 × 1440,
density 1), where the same documents are sharp on a Retina Mac (density 2) (2026-10-15).

The responsibilities are assigned following the GRASP principles (Information Expert, Low
Coupling, Protected Variations).

## Starting point

The committed content of a page is drawn from tiles of 512 pixels rasterized in background at the
levels of detail (LOD) of a pyramid: at LOD *k* a tile holds 2^*k* pixels per world unit, and the
world is laid out in screen pixels (`CalcPage`), so the camera scale (`cameraMatrix.a`) is in
screen pixels per world unit. `TileManager` draws the tiles of the LOD chosen by
`TilePyramid.calculateLod` through the camera matrix, filtered bilinearly
(`FilterQuality.Medium`).

A probe drew thin lines (ink, ruled paper) into tiles, put them on screen as `TileManager` does,
and compared them with the same lines drawn directly at the scale of the screen. The mean error
over the inked pixels (0–255):

| Case | Error |
|---|---|
| Zoom 1, tiles on whole pixels | 0 |
| Zoom 1, tiles half a pixel off | 39 |
| Zoom 1.1–1.41, tiles of LOD 0 magnified (the nearest LOD) | 31–40 |
| Zoom 1.1–1.41, tiles of LOD 1 shrunk | 13–14 |
| Bicubic filtering (`FilterQuality.High`), tiles on whole pixels | 11 |

| # | Problem | Cause |
|---|---|---|
| B1 | Tiles drawn half a pixel off | The pages are centered at `width / 2 − pageWidth / 2` (half a pixel on an odd width); a page starts after the previous one, of a fractional height (an A4 page 880 pixels wide is 1244.57 high); the camera moves by fractions of a pixel (trackpad, fling, bounce). Every pixel of the tiles is then spread over two pixels of the screen |
| B2 | Tiles magnified up to √2 | `calculateLod` rounds `log2(scale)` to the nearest LOD: between 1 and 1.41 the tiles of LOD 0 are magnified |
| B3 | Stair-stepped ink | The strokes are drawn with `drawVertices`, which Skia (and Android) never antialias |

The three are fractions of a pixel of the screen: on a screen of 2x and more a pixel is too small
to see them, on a screen of 1x it is not. The density itself is not the cause: Java reports a
scale of 1.0 on this screen, and multiplying the camera scale by the density (a commit dropped
from `fix/windows-desktop-build`) counts it twice, since the camera scale is already in screen
pixels.

## Requirements

1. At a zoom of 1 (and of its powers of two) every pixel of a tile falls on one pixel of the
   screen (B1).
2. On a screen of low density the tiles are not magnified (B2).
3. Nothing changes on the dense screens (Retina, iOS, Android phones): the same LODs, the same
   memory.
4. No change of the public API (`checkKotlinAbi`) or of the stored data: the layout of the world
   is computed at every start.

## Design

* **Information Expert.**
  * `CalcPage` lays the pages out, so it starts each page on a whole pixel.
  * `CameraPhysicsEngine` makes the render matrix, so its translation is on whole pixels. The
    physics keeps the exact position: only what is drawn (and the input mapped back through the
    same matrix) is rounded.
  * `TilePyramid` knows the LODs, so it holds the policy: `maxMagnification(density)` (√2 at 2x
    and more, 5% below) and `calculateLod(scale, maxMagnification)`, the lowest LOD whose tiles
    are magnified at most that much.
* **Low Coupling.** The density reaches `TileManager.drawTiles` (internal) as a parameter, from
  `DrawCanvas`, which reads it from `LocalDensity`. `TileManager` keeps no density of its own (the
  dropped commit kept one, set from two places).
* **Protected Variations.** Another threshold, or a curve between 1x and 2x, changes
  `maxMagnification` only.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | Pages and camera on whole pixels: `CalcPage.calcPagesRectOnWindow`, `CameraPhysicsEngine.getRenderMatrix`; `TileSharpnessTest` | Done |
| 2 | LOD by density: `TilePyramid.maxMagnification`, `calculateLod`, the density through `TileManager.drawTiles` and `drawPageTiles` from `DrawCanvas`; tests | Done |
| Later | Antialiased ink (B3) | |

### Phase 1 results

* An odd width, the second page of an A4 document and a scroll of 123.37 pixels: the pages start
  on whole pixels of the screen (`TileSharpnessTest`, which fails before the change: the first
  page started at 60.5).

### Phase 2 results

* The policy (`TileSharpnessTest`): the nearest LOD at 2x and more, the LOD above on screens of 1x
  to 1.75x as soon as the tiles would be magnified by more than 5%; `drawTiles` draws the tiles of
  LOD 1 at a zoom of 130% on a screen of 1x, those of LOD 0 on a screen of 2x.
* PencilNotes on the screen of 1x, a note with a stroke on ruled paper zoomed to 135%: the stroke
  and the lines are sharp, where they were blurred (and the edges of the stroke are smoother,
  being the tiles of LOD 1 shrunk).
* The screenshots of the explorer, recorded on Windows from the code before the change and
  verified after it: the editors differ by the edges of the pages, moved by less than a pixel;
  nothing else changes (the screenshots of the repository are recorded on macOS: they are
  verified there).
* The ABI dumps of the JVM and of Android do not change.

### Left for later

* **Antialiased ink (B3).** Two ways, to choose:
  * the outlines of the strokes from Ink (`ink_stroke_builder_get_outlines`, already used by the
    PDF export) filled as antialiased paths in the tiles and in the live layer: the edges are
    exact, each stroke keeps its outlines besides its mesh;
  * the tiles rasterized at twice their resolution on screens of low density and shrunk: no
    change to the strokes, four times the rasterization (PDF pages included).

  The thumbnails of the library show it too: a thin stroke shrunk without antialiasing breaks
  into dashes.
* `TrackpadGesturesTest.twoFingerScrollOfAMacTrackpadOverscrollsAndBouncesBack` fails on Windows
  before this work too (the bounce stops at 39.6 instead of 40).

## Decisions

Taken alone, revertible:

1. Two regimes, split at a density of 2, rather than a curve from 1x to 2x: the screens of 1x to
   1.75x are the ones where the pixels are seen (`maxMagnification`).
2. A tolerance of 5% before the LOD above (`maxMagnification`): the zoom of the opening and of
   "fit width" is 1.0 exactly, but a scale a hair above 1 (rounding of the zoom commands) must
   not take the tiles of LOD 1, four times as many pixels.
3. The translation of the camera rounded in the render matrix only, not in the physics
   (`getRenderMatrix`).

## Risks

* Memory on screens of low density: up to twice the tiles of the nearest LOD. In the worst case (a
  zoom just past a power of two) four pixels of tiles per pixel of the screen: 79 MB of tiles on
  screen for a window of 3440 × 1440, kept by `TileCache` beyond its budget of 64 MB as every
  tile on screen.
* A camera on whole pixels moves by whole pixels: a slow fling steps, and the focus of a pinch
  moves by less than half a pixel.
