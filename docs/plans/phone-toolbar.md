# The floating tools of a phone

The bar of the tools at the bottom of the editor on a phone, when it does not fit its width.

## Starting point

- On a phone (`ToolbarArrangement.FLOATING_TOOLS`, e.g. 411 dp wide) the tools float at the
  bottom of the canvas: `FloatingTools` in `EditorToolbar.kt`, one row scrolled by
  `fadingHorizontalScroll` (`components/ScrollFade.kt`) holding `EditorTools`: undo, redo, a
  divider, the drawing tools.
- The row does not fit a phone: on a PDF note there are nine buttons after undo and redo.
- A `ToolButton` chosen asks to be brought into view (`BringIntoViewRequester`), so the row
  scrolls to it. With the text tool chosen on a PDF note (the store screenshots, see
  `docs/plans/store-listing.md`), the row scrolled until undo showed half, at the left edge;
  with the hand chosen, undo and redo leave the view entirely. The button brought into view
  stops at the edge of the row, under its fade.
- The tablet and the desktop show the tools in `ToolGroup`, in the top bars, where they fit.

## Requirements

1. Undo and redo are always whole and in view on a phone, whatever tool is chosen.
2. The drawing tools still scroll when they do not fit, with the fade telling there are more.
3. The tool chosen is brought into view whole, out of the fade.
4. Something cut at the edge tells there are more tools: not a row that happens to end between
   two buttons, where the fade fades nothing.
5. The bars of the tablet and of the desktop do not change.

## Design

- **Information Expert:** `FloatingTools` knows the floating layout, so it splits it: the
  history (`EditorHistory`: undo and redo) fixed at the start, a divider, then the drawing tools
  in a row of their own that scrolls and fades (`weight(1f, fill = false)`, so a wide window
  shows the bar as wide as its tools, as now).
- **Low Coupling:** `EditorTools` takes `withHistory` (default `true`), so the top bars
  (`EditorToolbar`, `DrawScreen`) call it as they do and keep undo and redo in their group.
- **Protected Variations:** the fade has one length, `ScrollFadeLength`, which `ToolButton` uses
  as the margin it asks around itself when brought into view: the chosen tool ends past the
  fade, whole, and the one cut at the edge is the one faded. A row that fits does not scroll, so
  the bars that fit (tablet, desktop) look the same.
- **Pure Fabrication:** `peekingWidth(pitch)` in `ScrollFade.kt` sizes a scrolling row that does
  not fit so it ends in the middle of an item (`pitch` is a button and the space after it): half
  a tool shows under the fade at the end that has more. Without it a phone of 411 dp shows five
  whole tools and a gap, and nothing says the row scrolls. The bar gets narrower by less than a
  button and stays centered.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | Undo and redo fixed out of the scrolling tools; the chosen tool brought into view past the fade; the row cut in the middle of a tool; a phone screenshot with the last tool chosen | Done |

## Decisions

- Undo and redo fixed rather than a snap to whole items: the snap would still let undo leave the
  view, and it needs a lazy row or a custom fling for a few buttons. Revertible.
- `peekingWidth` reads the width of the content by its intrinsic width; the buttons are of one
  size, so one pitch is enough. Revertible.
- The margin of `bringIntoView` applies to every `ToolButton` in a scrolling row, the tool group
  of a narrow tablet window too: it only changes where a scroll stops. Revertible.
- The new screenshot `editor-floating-tools-scrolled-phone-light.png` (the hand chosen, the last
  tool) and the two phone references that change, `editor-phone-light.png` and
  `editor-phone-dark.png`, are to be recorded on the Mac: the references of the Windows host differ
  in antialiasing. Rendered on Windows before and after the change, every other `editor-*`
  screenshot (tablet, desktop, phone in landscape) is the same to the pixel.

## Left for later

- A snap to whole items in the scrolling tools, if a cut tool at the fade still reads badly.
