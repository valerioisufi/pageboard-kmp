# The visual identity of PencilNotes

PencilNotes gets one icon on every platform: the P of its wordmark, in Cherry Bomb One, lime on the
dark green of the brand, underlined by a yellow stroke. One script draws it from its geometry and
writes every file the platforms take.

## Starting point

Two icons coexisted:

| Where | File | Icon |
|---|---|---|
| Android launcher | `mipmap-*/ic_launcher_foreground.webp` on a white background, no monochrome layer | The yellow and orange pencil tip of PencilNotes 2.1 |
| Android splash screen | `themes.xml` (light and night), the launcher foreground on white or black | The pencil tip |
| iOS | `AppIcon.appiconset/app-icon-1024.png` | The pencil tip |
| Play listing | `fastlane/metadata/android/*/images/icon.png`, `featureGraphic.png` (`tools/store/graphics.py`, from the iOS icon) | The pencil tip |
| GitHub | `docs/images/social-preview.png` (the same script) | The pencil tip |
| Desktop | `desktopApp/src/main/resources/icons/` (`icon.png`, `icon_mac.png`, `.icns`, `.ico`), also in the packages | A lime pencil (`DrawViewIcons.Pencil`) on dark green |
| The application | `PencilNotesMark` in `PencilNotesApp.kt`: next to the wordmark in the library, and in About | The lime pencil, in the colors of the theme |

The pencil tip clashed with the interface, and the green pencil, a generic icon of the toolbar,
said nothing of PencilNotes.

## Requirements

1. One icon everywhere: launcher, splash screen, iOS, desktop, store, GitHub, inside the
   application.
2. It reads at 48 px and below, and as an Android themed icon (one color, from the wallpaper).
3. It belongs to the brand: dark green `#3E691A`, lime `#BDF292`, yellow `#EBE865`, cream
   `#FDFDF5`, and Cherry Bomb One for the wordmark.
4. It is made again from one source when it changes, not drawn by hand per platform.
5. The name and the icon stay outside the MIT license (`pencilnotes/TRADEMARK.md`).

## Design

The icon is the P of Cherry Bomb One (the glyph of the font, 0.66 of its size on a canvas of
1024), lime on dark green, with a yellow stroke under it that grows and fades like a pen. The
directions compared were the P (with variants: a flicked underline, a yellow counter, tilted, on
lime, a bowl drawn as an ink loop), a slide with annotations, and the ring a student draws around
what matters; the slide was unreadable under 64 px and as a themed icon.

| Responsibility | Where | Pattern |
|---|---|---|
| The geometry of the icon (the glyph, the underline) and every file made from it | `tools/brand/icon.py` | Information Expert, Pure Fabrication |
| The Android adaptive icon: a color background, a vector foreground, a monochrome layer | `res/drawable/ic_launcher_*.xml`, `mipmap-anydpi-v26/ic_launcher.xml` | Creator (made by the script) |
| The splash screen: the foreground on the green of the icon, on the cream or dark background of the theme | `values*/themes.xml` | |
| The iOS icon, the desktop icons, the master SVG | `app-icon-1024.png`, `icons/*`, `tools/brand/pencilnotes-icon.svg` | Creator |
| The mark in the application, from the same paths | `PencilNotesLogo.kt` (made by the script), `PencilNotesMark` | Protected Variations: the icon changes in one place |
| The store images, from the iOS icon | `tools/store/graphics.py` | Indirection |

Run `python -I tools/brand/icon.py .`, then `python -I tools/store/graphics.py <shots> .` for the
store images.

## Phases

| Phase | Content | Status |
|---|---|---|
| 1. The identity | The directions as drafts, chosen with the maintainer: the P, underlined | Done |
| 2. The icon everywhere | The script, the icons of Android, iOS and desktop, the mark in the application, the store images | Done |
| 3. Checks | The Android debug build, the desktop application on a scratch data directory | Done |

## Decisions

- **The P and its underline**, chosen by the maintainer among the drafts (A1, the underline straight).
- **The palette stays as it is**: dark green, lime, yellow, cream; no new colors.
- **The mark in the application keeps the colors of the brand** whatever the theme or the dynamic
  colors of Android, as a launcher shows the icon (revertible: it took `primary` and
  `primaryContainer` before). Its corners follow the icon (9/40 of its size, from 14/40).
- **The Android icon is a vector**, with a monochrome layer for the themed icons; the bitmaps of
  the foreground are gone (the minimum SDK is 29, every launcher takes the adaptive icon).
- **The splash screen shows the icon on its green**, on cream in the light theme and `#1A1C18` in
  the dark one, the backgrounds of the application (revertible: white and black before).
- **The desktop tiles are rounded rectangles** (22.5% of the side), in the grid of macOS for the
  Dock (824 of 1024); the `.icns` is written by Pillow, not `iconutil`, so it is made on any
  system.

## Left for later

- The screenshots of the listing and of the README show the old mark in the library: they change
  when the screenshots are taken again (`docs/plans/store-listing.md`).
- The sample application keeps the logo of DrawView.
- An animated splash icon.
