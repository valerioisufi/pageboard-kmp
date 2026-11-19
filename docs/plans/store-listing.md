# The store listing of PencilNotes and the images of the repository

What a visitor sees before installing PencilNotes or reading the code: the listing on Google Play
(texts, icon, feature graphic, screenshots) and the images of the README and of a link to the
repository. This document starts from main at 6a9597f6 (2026-11-18).

## Starting point

* `docs/plans/play-console.md` had a draft of the short description and a list of screenshots to
  take; no image existed.
* The README showed two images of the Roborazzi tests: a library with empty notes and the lecture
  panel alone.
* No social preview for GitHub.

## Requirements

1. Screenshots of the real application, release build, with notes a student would have: slides
   written on by hand, formulas, a sketch, a library with courses.
2. What Play asks: the icon (512 px), the feature graphic (1024 x 500), phone screenshots (at most
   2:1) and the 7" and 10" tablet ones, in English and Italian.
3. The texts say only what the application does, as `docs/privacy.md` says it.
4. Everything can be made again after a change of the interface.

## Design

| Responsibility | Where | GRASP |
|---|---|---|
| The listing, per language | `pencilnotes/androidApp/fastlane/metadata/android/<locale>/` (the layout of fastlane, read by F-Droid too) | Information Expert |
| The slides of the demo | `tools/store/slides.py` (a PDF with its text layer, no library) | Pure Fabrication |
| Strokes drawn by a hand on the emulator | `tools/store/strokes.py`: paths written as input events to the touchscreen, a point at a time | Pure Fabrication, Indirection |
| The captioned screenshots, the feature graphic, the social preview, the images of the README | `tools/store/graphics.py` | Pure Fabrication |

### How the screenshots are made

* The API 29 tablet emulator (2560 x 1600; on Android 12+ the colors follow the wallpaper, so the
  green of PencilNotes shows on Android 10), the release bundle installed with `bundletool`, the
  status bar in demo mode (9:41, full battery, no notifications).
* The demo: `slides.py` writes "Calculus I, Lecture 4: Limits"; it is imported, written on with
  `strokes.py` (with `adb root`; the shapes `ellipse`, `arrow`, `underline`, `check`, `axes`,
  `bounds`, `wiggle`), its text marked with the text-markup tool, and
  a text box with a formula typed with `adb shell input text`. Three other notes and two folders.
* The phone screenshots: the same emulator with `wm size 1080x2160` and `wm density 420`.
* `python -I tools/store/graphics.py <shots dir> <repo dir>` writes the rest.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | The demo notes and the screenshots on the emulator (6 tablet, 3 phone) | Done |
| 2 | The graphics: captioned screenshots in two languages, feature graphic, icon, social preview, the README images | Done |
| 3 | The texts in English and Italian, the README and `play-console.md` pointing to them | Done |

## Decisions

* **The category is Education** (alone, revertible): the listing speaks to students; Productivity is
  the other fit.
* **The screenshots come from an emulator**, not from the tablet: the strokes are drawn by
  `strokes.py` as a hand would (alone, revertible: shots from the tablet with real handwriting would
  be better still).
* **English captions on English screenshots for Italian too**: the interface in the shots is in
  English; the captions are translated.
* **No video** for now: it needs a real hand on a real tablet.

## Found while taking them

* The snippet of a text box in the search results shows its Markdown and LaTeX as written
  (`Squeeze: $\cos x \le \frac{\sin x}{x}…`), while the answer above renders them.
* "Ask your notes" cites [2, 3] in its answer but lists only the passage [1] under "Cited passages".
* On a phone the toolbar of the editor scrolls to the selected tool and cuts the first ones (the
  undo button half out).

## Left for later

* The three findings above.
* Screenshots of the lecture assistant (it needs a recorded lecture with suggestions) and of the
  sync between two devices.
* The listing in the other languages of the application (de, es, fr, ja, ko).
* Publishing the listing with fastlane `supply` from the CI, with the key of the Play Console in the
  secrets.
