# Tiles after a large zoom out

After a large zoom out, cells of the screen are empty (the bare paper) until their tiles are
rasterized. This plan keeps something drawn in every cell and fills the screen sooner.

## Starting point

`TileManager.drawTiles` draws, for a cell whose tile is missing, its nearest ancestor (at most 3
levels away), and otherwise its ready descendants (at most 2 levels away). Missing tiles are
launched in the order they are asked for, on 2 workers (`enqueueTile`), and a job runs even when its
tile has left the screen. `docs/performance.md` (finding 12, decision 9) left a scheduler for a
device that shows a cost: this is that device.

### Measures

A temporary probe, not committed, on the MatePad 10.4 (2000 x 1200, 60 Hz): a debug build of the
sample, an in-memory synthetic document of 8 A4 pages (nothing stored), and a camera driven by a
script from scale 1 to 16 in 1.5 s and back. A: back at once. B: 4 pans across the pages at scale 16
first, as reading a document zoomed in. A frame counts as blank for the area of its cells with no
tile, ancestor or descendant.

| Run | Strokes a page, zoom out | Largest blank area | Blank until, after the gesture | Own tiles everywhere, after the gesture | Jobs queued at the end of the gesture | Jobs for a level no longer shown |
|---|---|---|---|---|---|---|
| A (3 runs) | 400, 600 ms | 30–45% | 58–93 ms | 123–176 ms | 35–52 | 46–88 of about 215 |
| B (2 runs) | 400, 600 ms | 19–20% | 27–58 ms | 143–157 ms | 43–45 | 27–34 of about 257 |
| A (1 run) | 1,500, 300 ms | **88%** | **760 ms** | **926 ms** | 150 | **174 of 208** |
| B (1 run) | 1,500, 600 ms | 63% | 528 ms | 777 ms | 105 | 133 of 256 |

A tile of level 0 takes 11–12 ms at 400 strokes a page and 32–34 ms at 1,500; it waited up to
954 ms in the queue. The 18 tiles of level 0 alone would take about 290 ms on the 2 workers at 1,500
strokes, against the 926 ms measured.

So there are three causes:

1. **The coarse tiles are evicted.** At scale 16 the cache holds 64 tiles of level 4 and no tile of
   level 0 (18 before the zoom in; 8 left at 1,500 strokes, where the workers lagged). `TileCache`
   means to keep them, but the tiles of the current and of the previous frame are never evicted:
   read from the code, the cells on screen and their margin take the whole budget of 64 MB at this
   screen size (up to 40 cells, more with the margin), so the eviction falls on level 0.
2. **Nothing covers the cell.** Without those tiles no ancestor is left, and the descendants lie
   more than 2 levels away or cover a part of the cell.
3. **The queue draws what is no longer wanted.** Up to 84% of the jobs of a zoom out were for levels
   already left, in front of the tiles on screen.

Limits of these measures: synthetic handwriting and no PDF pages, a debug build, a script instead of
fingers.

## Requirements

1. After a zoom out of any size, no cell of a page reached before is left blank: it shows the page,
   blurred if need be. A page never reached shows it after one tile.
2. The tiles on screen are rasterized before the others, and tiles no longer wanted are not
   rasterized.
3. Writing, editing and the reading of a page cost nothing noticeable: no new stall on the UI thread,
   a bounded memory.
4. No stored format or protocol changes; the public API stays as it is if possible (`checkKotlinAbi`).

## Design

* **`TilePyramid.OVERVIEW_LOD`: every page near the screen keeps a coarse tile** (level -2: 2048
  world units, one tile for a page laid out up to 2048 wide). It is the base under every missing
  cell. *Information Expert*: the pyramid knows what a level covers.
* **`TileCache` holds the overviews in a budget of their own** (16 MB: the 16 pages seen last), so the
  tiles in use cannot evict them, nor they the tiles in use. *Information Expert*: the cache already
  decides what to evict. *Protected Variations*: the policy stays behind `put`.
* **The fallback of a cell, in `TileManager`:** its nearest ancestor at any distance, stale ones
  included (a stale overview is better than a blank cell), with the ready descendants drawn over it.
  Today the descendants are drawn only without an ancestor. *Controller*: `drawTiles` stays the one
  place choosing what a cell shows.
* **`TileQueue`, a new internal class: what the workers rasterize next.** Each frame says what it
  wants, in order of priority; what the frame no longer asks for leaves the queue; a tile being
  rasterized is left to finish. *Pure Fabrication*: no model concept owns this, and `TileManager`
  loses the job. *Low Coupling*: it knows keys and priorities, not the bitmaps. Priorities:
  1. a missing overview of a page on screen (one tile covers a whole page);
  2. the cells on screen, from the center out;
  3. the margin around the screen, and the missing overviews of the pages near it;
  4. a stale tile drawn in place of a missing one. A stale overview is refreshed only then, so that
     writing does not rasterize it again and again.
* **The number of workers stays 2** unless phase 3 measures a gain.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | The overviews: `OVERVIEW_LOD`, their budget in `TileCache`, the fallback of a cell; tests (an overview survives a cache full of level 4, a cell without its tile draws it, a stale one too); measured on the MatePad | Done |
| 2 | `TileQueue`: priorities, what a frame stops wanting dropped; `isRasterizing` keeps its meaning; tests of the order and of the drop; `TileBenchmark` gets the zoom out; measured on the MatePad | Proposed |
| 3 | Only if the measures show a gain: 3 workers instead of 2, measured with the frames on the UI thread | Proposed |

The target, on the MatePad with the same script: no blank area once a page has its overview, the
screen filled about as fast as its own tiles take (about 300 ms in the run of 926 ms), and the jobs
for levels no longer shown down to the ones already running.

## Decisions (revertible)

1. **Level -2 and one tile a page**, for its cost: 1 MB a page, one tile to rasterize. A page of 1168
   px (the MatePad) shows 4 times magnified from 292 px at scale 1, for the moments before its tiles come. Level -1
   would be half as blurred, at 4 MB and 4 tiles a page.
2. **A budget of 16 MB for the overviews**, on top of the 64 MB: at most 25% more memory.
3. **Priority by groups, the center first within the cells on screen**, instead of a finer cost.
4. **A stale overview is refreshed only when drawn**, not as soon as nothing else waits as this plan
   first said: it would take a worker at every change of a page being written (135 ms a tile at
   1,500 strokes), for a tile no one looks at. The other levels work the same way already. The price:
   after a zoom out, a cell may show for a moment a page as it was before a change other than a
   stroke (strokes are baked in every level).
5. **The budget of 64 MB does not change**, and the tiles in the margin stay protected: unprotected,
   a tile of the margin would be evicted and rasterized again at every frame on this screen.

## Results

Same probe and script, on the MatePad. "Own tiles" is the time after the end of the gesture until
every cell on screen has its own tile.

| Run | Before: blank, own tiles | Phase 1: blank, own tiles |
|---|---|---|
| A, 400 strokes (3 runs) | 30–45%, 123–176 ms | **0%**, 107–175 ms |
| B, 400 strokes (2 runs) | 19–20%, 143–157 ms | **0%**, 142–143 ms |
| A, 1,500 strokes | 88%, 926 ms | **0%**, 874 ms |
| B, 1,500 strokes | 63%, 777 ms | **0%**, 777 ms |

An overview takes 39 ms to rasterize at 400 strokes a page, 135 ms at 1,500.

## Left for later

* A budget following the size of the screen (`docs/performance.md`, "the tiles"), which would also
  keep the intermediate levels.
* Sharper tiles during the gesture: while the levels pass, the workers still rasterize the level of
  the moment.
* `feat/ink-latency` also changes `TileManager` (`blankCells`, `isScreenRegionDrawn`): whichever
  branch lands second is rebased on the other, and a cell counts as blank there only without an
  overview.
