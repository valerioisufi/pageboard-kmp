# Tile sharpness

The content of the documents (ink, paper, PDFs, texts, images) as sharp on screen as the screen
allows. This document starts from the blur seen on a Windows desktop at 100% (3440 × 1440,
density 1), where the same documents are sharp on a Retina Mac (density 2) (2026-10-15); the Mac
then showed the tiles slightly blurred at some zooms too (2026-10-17).

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

The three are fractions of a pixel of the screen: on a screen of 2x and more a pixel is smaller and
they are seen less, on a screen of 1x they are seen at once; B2 is seen on a Retina Mac too,
between two LODs. The density itself is not the cause: Java reports a scale of 1.0 on this
screen, and multiplying the camera scale by the density (a commit dropped from
`fix/windows-desktop-build`) counts it twice, since the camera scale is already in screen pixels.

## Requirements

1. At a zoom of 1 (and of its powers of two) every pixel of a tile falls on one pixel of the
   screen (B1).
2. The tiles are not magnified, on every screen (B2).
3. One rule for every platform: the density does not enter the rendering of the tiles.
4. No change of the public API (`checkKotlinAbi`) or of the stored data: the layout of the world
   is computed at every start.

## Design

* **Information Expert.**
  * `CalcPage` lays the pages out, so it starts each page on a whole pixel.
  * `CameraPhysicsEngine` makes the render matrix, so its translation is on whole pixels. The
    physics keeps the exact position: only what is drawn (and the input mapped back through the
    same matrix) is rounded.
  * `TilePyramid` knows the LODs, so it holds the policy: `calculateLod(scale)` is the lowest LOD
    whose tiles are magnified at most by `MAX_MAGNIFICATION` (5%).
* **Low Coupling.** The density does not reach the tiles: the camera scale is already in screen
  pixels. `TileManager` keeps no density (the dropped commit kept one, set from two places).
* **Protected Variations.** Another tolerance changes `MAX_MAGNIFICATION` only.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | Pages and camera on whole pixels: `CalcPage.calcPagesRectOnWindow`, `CameraPhysicsEngine.getRenderMatrix`; `TileSharpnessTest` | Done |
| 2 | LOD by density: `TilePyramid.maxMagnification`, `calculateLod`, the density through `TileManager.drawTiles` and `drawPageTiles` from `DrawCanvas`; tests | Done, replaced by 3 |
| 3 | One rule for every screen: `TilePyramid.MAX_MAGNIFICATION`, the density out of `drawTiles` and `drawPageTiles`; tests | Done |
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

### Phase 3 results

* `calculateLod` takes the LOD of a power of two up to 5% above it, the LOD above beyond, on every
  screen (`TileSharpnessTest`); `drawTiles` draws the tiles of LOD 0 at a zoom of 100%, those of
  LOD 1 at 130%. On this screen of 1x nothing changes from phase 2.
* `TileScreenTest`: a Retina window zoomed just past a change of LOD (1.06) rasterizes its tiles
  once and keeps them, beyond the budget of the cache; the tiles of LOD 0 leave no seam from 60%
  to 104%.
* To see on the Retina Mac: the zooms between two LODs (100–141%, 200–283%) as sharp as the
  powers of two.

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

Taken by the maintainer on 2026-10-17:

1. One rule for every screen: the tiles are never magnified, on the dense screens too, at the cost
   of twice the tiles. It replaces the two regimes of phase 2 (the nearest LOD at 2x and more,
   no magnification below), taken alone and dropped when the Mac showed the blur too.

Taken alone, revertible:

2. A tolerance of 5% before the LOD above (`MAX_MAGNIFICATION`): the zoom of the opening and of
   "fit width" is 1.0 exactly, but a scale a hair above 1 (rounding of the zoom commands) must
   not take the tiles of LOD 1, four times as many pixels.
3. The translation of the camera rounded in the render matrix only, not in the physics
   (`getRenderMatrix`).

## Risks

* Memory: up to twice the tiles of the nearest LOD, on every screen. In the worst case (a zoom
  just past a power of two) four pixels of tiles per pixel of the screen, kept by `TileCache`
  beyond its budget of 64 MB as every tile on screen: about 95 MB of tiles on screen on a
  MacBook Pro 14" (3024 × 1964, 47 MB before), 90 MB on an iPad Pro 12.9" (45 MB), 79 MB for a
  window of 3440 × 1440, 41 MB on an Android phone of 1080 × 2400 (21 MB): to watch on the
  phones. Zooming rasterizes twice the pixels in background.
* A camera on whole pixels moves by whole pixels: a slow fling steps, and the focus of a pinch
  moves by less than half a pixel.
