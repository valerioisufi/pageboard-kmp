# The lecture panel and the editor toolbar, by section

Phase 9 of `docs/architecture.md` (design C, High Cohesion): `LecturePanel.kt`
(`drawview-lecture-ui`, 1670 lines) and `EditorToolbar.kt` (`drawview-ui`, 918 lines) split into
files by section, with the same composables, the same behaviour and the same public API (the
screenshots unchanged). `LectureAssistant` gives its top-level declarations a file of their own;
its state is split in phase 11.

The responsibilities are assigned following the GRASP principles.

## Design

* **High Cohesion**, file by file, the declarations moved as they were (a `private` one becomes
  `internal` only when another file uses it):
  * `LecturePanel.kt`: the panel, its state and effects, the idle section, the list of the
    lectures; `LectureHeader.kt`: the header of a lecture (recording, import, sharing, notices,
    the banner of the professor's page); `LectureNotesTab.kt`: the tabs and the notes suggested;
    `LectureTranscriptTabs.kt`: the transcript, the outline, the course context;
    `LectureReviewTab.kt`: the review, its cost, where its pages go; `LecturePanelParts.kt`: what
    the sections share (texts, times, chips).
  * `EditorToolbar.kt`: the bar, its groups, its menus, the page indicator; `EditorTools.kt`: the
    tools in the bar with their settings; `ToolSettings.kt`: the settings of each tool.
  * `LectureArrivals.kt`: the offers of the lectures arriving, their notifications, the files
    exported.

## Phases

| Step | Content | Status |
|------|---------|--------|
| 1 | `LecturePanel`, `EditorToolbar` by section; the top-level declarations of `LectureAssistant` | done |
| 2 | The state of `LectureAssistant` (the lectures, the requests, the models) | done in phase 11 (`docs/lecture-backends.md`) |

## Decisions

Taken alone, revertible:

* **The state of the assistant with phase 11**: putting the lecture models behind `ModelBackend`
  draws the seams of the requests and the models; splitting it before would move the same code
  twice, in the part that records the user's lectures.
