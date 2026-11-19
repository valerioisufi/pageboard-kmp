# The smaller ones: duplicates and translations

Phase 12 of `docs/plans/architecture.md` (design E). Nothing stored or synced changes.

The responsibilities are assigned following the GRASP principles.

## Design

* **Information Expert.** `codePointAt` and `codePoints` (a surrogate pair read as one code point)
  in `drawview-model` (`model.text`), used by the PDF fonts (`drawview-pdf`) and the export
  (`drawview-export`) instead of a copy in each.
* **Pure Fabrication.** `SwitchRow` in the design components of `drawview-ui`: an option, what it
  does, its switch (with its check), the whole row toggling it. It replaces the copies of the
  export sheet, the settings of the lecture assistant and the access of the external models; the
  last two take its look (their screenshots recorded again).
* **The translations.** `drawview-agent-ui` and `drawview-search-ui` were in English only: they
  get Italian, the language the applications are used in.

## Phases

| Step | Content | Status |
|------|---------|--------|
| 1 | `codePointAt` in `drawview-model`, `SwitchRow` in `drawview-ui` | done |
| 2 | Italian for `drawview-agent-ui` and `drawview-search-ui` | done |
| 3 | One drawing of the page backgrounds (renderer, page grid, template picker) | later |
| 4 | German, Spanish, French, Japanese, Korean for `drawview-agent-ui`, `drawview-search-ui`, `drawview-lecture-ui` (about 2000 strings) | later |

## Decisions

Taken alone, revertible:

* **Italian first**: the other languages of `drawview-ui` are for other users, when the
  applications are published; Italian is what the maintainer sees today.
* **The backgrounds later**: one drawing changes the look of the thumbnails and of the template
  picker, to be looked at by the maintainer, not only by the screenshots.
