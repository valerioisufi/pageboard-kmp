# Full access for external models

The command line and the MCP server of PencilNotes (`docs/agent-tools.md`) reach a document only once
the user allowed it from its editor, change only the texts and add pages, and apply a change only to
a document open in an editor. This plan lets them change any document, open or not, in any way the
editor can, including removing things, without allowing each document; what is removed still goes
to the trash, and every change a model made can be undone. It starts from the code as it is (`main`
at fa756440, 2026-11-06).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The grants are per document.** `AgentGrants` (`enabled`, `documents`) is kept in the preference
   `agent.grants`; `DocumentTools.withDocument`, `list_documents`, `search` and `proposal_status`
   check `documents`. A document is allowed from the "External models…" sheet of its editor
   (`AgentReviewUi.AccessSheet`): there is no way to allow them all, and no sheet in the library.
2. **Direct mode lasts one connection.** `AgentConnection.direct` is in memory and ends with the
   connection; a command of `pencilnotes` opens one connection per command, so a command is never in
   direct mode.
3. **A change needs an editor.** `ExternalAgents.received` applies a proposal at once only when its
   document is in an editor (`AgentEditors.settle`), so that `HistoryManager` can undo it; otherwise
   it asks the application to open the document for its review (`openForReview`).
4. **What a model can change.** `ProposalItem` has `replace_text`, `remove_text`, `insert_text`,
   `add_page`. `read_page` gives the texts, the PDF text, the counts of strokes and images, and the
   area covered by ink, not the strokes or the images themselves.
5. **What the model already offers.** `DocumentChange` has every change the editor makes:
   `AddElements`, `RemoveElements`, `PlaceElements` (`StrokePlacement`, `BoxPlacement`,
   `TextPlacement`), `ReorderElements`, `UpdateTexts`, `RestyleMarkups`, `AddPage`, `RemovePage`,
   `MovePages`, `SetPageFormat`, `SetDocumentDefaults`, `RenameDocument`, `AddResource`,
   `CompositeChange`. There is no change of the color or width of a stroke: the editor has none either.
   What is removed is hidden, not destroyed (`removedElements`, `removedPages`, the trash of the pages
   of `docs/trash.md`).
6. **The library.** `DocumentCatalog` creates, moves and trashes documents and folders, restores
   from the trash, and deletes for good (`deleteDocument`, `deleteFolder`, `purgeTrash`);
   `OpenDocuments.rename` and `insert` rename and add documents.
7. **`base`** (`DrawText.base()`) tells whether a text changed since it was read, from its versions
   and a hash of its text. Strokes, images and pages have versions too (`ElementVersions`,
   `RemovePage.saw`), but no `base`.
8. **The library has actions** (`LibraryAction`, e.g. `search.settingsAction()`), but none of the
   agents.

## Requirements

1. **Any document, open or not.** A change reaches a document that is in no editor, without opening
   one.
2. **Any change the editor can make**, removing included: the elements of a page (texts, strokes,
   images, PDF pages), the pages, the documents and the folders.
3. **All documents at once.** The user can let the clients reach every document, the ones created
   later too, without allowing them one by one. Allowing them one by one stays.
4. **Review or apply at once, as the user chooses, and kept.** A setting of the application, not of a
   connection, so that it works for the command line.
5. **Never lose a note.** What is removed goes to the hidden elements, the hidden pages or the trash,
   as when the user removes it. No tool deletes for good or empties the trash. A change made on
   something changed since it was read is stale and overwrites nothing (as today).
6. **Every change of a model can be undone**, in an editor or not, as long as what it touched was not
   changed since.
7. **Off by default.** Nothing of today changes until the user turns it on; the stored grants read as
   before.
8. **Nothing new travels.** Every change is one of `DocumentChange` today: no protocol change, no
   Room change.

## Design

```
pencilnotes … | pencilnotes mcp
        │ local socket (as today)
        ▼
  AgentEndpoint ──► DocumentTools ── reads ──► OpenDocuments / DocumentCatalog
                         │ proposes
                         ▼
                    ProposalBook ── review (editor or library) ──┐
                         │ applyDirectly                        │ accepted
                         ▼                                      ▼
              ProposalApplier / LibraryApplier ──► DocumentSession.submit / DocumentCatalog
                         │ inverse
                         ▼
                    AgentActivity ── undo ──► the same, checked
```

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Which documents a client may reach and whether its changes are reviewed: `AgentGrants.allDocuments`, `AgentGrants.applyDirectly`, `AgentAccess.allows(documentId)` | `AgentAccess` (`drawview-agent`) | Information Expert, Protected Variations (one place answers for every tool) |
| What a document change of a model is, by meaning: the new items | `ProposalItem` (`drawview-agent`) | Polymorphism |
| What a change of the library is: `LibraryItem` | `LibraryItem` (`drawview-agent`) | Polymorphism, High Cohesion (apart from the items of one document) |
| The items turned into one `CompositeChange`, each checked against its `base` | `ProposalApplier`, `ProposalPlanner` (`drawview-agent`) | Information Expert |
| The items of the library applied on `DocumentCatalog` / `OpenDocuments`, each checked, with their inverse | `LibraryApplier` (`drawview-agent`) | Pure Fabrication |
| What a model read of an element or a page, to tell a change: `base` of every element and of a page | `ElementBase` (`drawview-agent`, internal) | Information Expert |
| What the models changed, its inverse, and its undo checked against what changed since; in memory | `AgentActivity` (`drawview-agent`) | Creator, Controller |
| A proposal applied at once on a document in no editor: opened by `OpenDocuments`, closed after | `ExternalAgents.received` (`drawview-agent`) | Controller |
| The sheet of the access, the review of the library items and the activity, from the library | `AgentReviewUi.libraryAction()` (`drawview-agent-ui`) | Controller, Polymorphism (a `LibraryAction` like the others) |
| The new commands | `AgentCli` (`pencilnotes/desktopApp`) | Low Coupling |

### The access

`AgentGrants` gains two fields, read as `false` when absent, so the value stored today reads as it
does now:

| Field | Meaning |
|---|---|
| `allDocuments` | every document is reached, the ones created later too; `documents` is kept for when it is turned off |
| `applyDirectly` | a proposal is applied when it arrives, its document open or not; a library item too |

`AgentAccess.allows(documentId)` is `enabled && (allDocuments || documentId in documents)`, and every
tool asks it instead of reading `documents`. `search` is given every document when `allDocuments`.

`AgentConnection.direct` and its switch go: `applyDirectly` replaces them, for the command line and for
MCP alike. Turning on `allDocuments` together with `applyDirectly` tells the user, in the sheet, that a
document read by the model may hold instructions for it, and that what it removes is in the trash.

The sheet is reached from the library too (`libraryAction()`), where it lists the waiting library items
and the activity.

### Applying without an editor

With `applyDirectly`, `received` applies every proposal at once through `OpenDocuments.open`, as the
synchronization does on a document in no editor. When the document is in an editor, the editor is
settled first (as today) and its `HistoryManager` records the change, so Ctrl+Z undoes it too. Without
`applyDirectly` nothing changes: a document proposal opens its editor for the review, a library item
waits in the sheet of the library.

### The new items of a document

| Item | Becomes | Check |
|---|---|---|
| `remove_elements(page, [id, base])` | `RemoveElements` | each `base` |
| `place_elements(page, [id, base, dx, dy, scale?, rotation?])` | `PlaceElements` (stroke, box, text placements) | each `base`, inside the page |
| `reorder_elements(page, [id, base], to: front/back/above/below, of?)` | `ReorderElements` | each `base` |
| `restyle_text(text, base, color?, size?, align?)` | `UpdateTexts` | `base` |
| `add_stroke(page, points, color, width, tool)` | `AddElements` | bounded points, inside the page |
| `restyle_stroke(stroke, base, color?, width?)` | `RemoveElements` + `AddElements` of a copy: the answer gives its new id | `base` |
| `add_image(page, path, area?)` | `AddResource` + `AddElements` | a file read by the application, decoded as an image, bounded in size |
| `remove_page(page, base)` | `RemovePage` (the trash of the pages) | `base` of the page |
| `move_page(page, after?)` | `MovePages` | the page and the one it follows are there |
| `duplicate_page(page, after?)` | the copy of `CopiedPages` | the page is there |
| `set_page_format(page, base, width?, height?, background?)` | `SetPageFormat` | `base` of the page |
| `rename_document(title)` | `RenameDocument` through `OpenDocuments.rename` | the name is free |

`read_page` gives every element with its id and `base`: a stroke by its box, color, width, tool and
number of points; an image and a PDF page by their box; a text by its style too. `read_stroke` gives
the points of a stroke, at most a set number. The `base` of an element or a page is its versions and a
hash of what the model was shown, like the one of a text.

A stroke is restyled by a copy with a new id because the model has no change of the style of a stroke:
adding one would be a protocol change, left for later.

### The library

The tools `list_folders` and `list_trash` read; `propose_library` proposes `LibraryItem`s:

| Item | On | Inverse kept by `AgentActivity` |
|---|---|---|
| `create_document(title, folder?, format?)` | `OpenDocuments.insert` | to the trash |
| `move_document(document, folder?)` | `DocumentCatalog.moveDocument` | moved back |
| `trash_document(document)` | `DocumentCatalog.trashDocument` | restored |
| `create_folder(name, parent?)` | `createFolder` | to the trash |
| `rename_folder(folder, name)` | `renameFolder` | renamed back |
| `move_folder(folder, parent?)` | `moveFolder` | moved back |
| `trash_folder(folder)` | `trashFolder` | restored |
| `restore(item)` | `restore` | to the trash again |

There is no item for `deleteDocument`, `deleteFolder` or `purgeTrash`, and none ever (requirement 5).
A document read only on this device (a shared one of which this device is a member) refuses the items
that change it, as today.

### The activity

`AgentActivity` keeps, in memory, the last batches applied for a client: when, which client, what, on
which document, and the inverse. Undoing a batch applies its inverse only when what it touched is as the
batch left it (the versions after it); otherwise it says what changed and does nothing, so an undo
after a Ctrl+Z of the same batch, or after the user wrote on it, overwrites nothing. Quitting the
application drops it, like the proposals: what was removed is still in the trash.

### Threats

The reach is much larger than in `docs/agent-tools.md`, so a prompt injection (a shared document or a
PDF telling the model to remove everything) matters more. The answers:

| Threat | Answer |
|---|---|
| A model removing many things at once | It goes to the trash and the hidden elements; the activity undoes it; at most a set number of documents and folders trashed per batch and per minute, beyond them refused |
| A model deleting for good | No tool does |
| `add_image` reading a file it should not | Only a file that decodes as an image, at most a set size; it is a file of the user, whose client could already read it |
| A client sending anything | As today: `AgentEndpoint` bounds and checks every message; the new items are checked by `ProposalLimits` |
| Off by default | `allDocuments` and `applyDirectly` start `false`; the stored grants read as before |

What travels between the devices are the operations of today, so `docs/untrusted-members.md` is
unchanged: `PROTOCOL_VERSION` stays 1 and `ProtocolFormatTest` is not re-recorded. A phase that would
need either stops and asks.

## Phases

One commit per phase on `feat/agent-full-access`, with the checks of `CLAUDE.md`.

| Phase | What | Status |
|---|---|---|
| 1 | The access: `allDocuments`, `applyDirectly`, `allows`; every tool on `allows`; `direct` of a connection removed; the sheet with the two switches and its warning, from the library too. Tests: the value stored today read as before (`enabled`, `documents`), a document created later reached with `allDocuments`, search on all; screenshots of the sheet; ABI recorded on purpose | done |
| 2 | Applying without an editor and `AgentActivity`: a proposal applied on a closed document, in an editor recorded by its history too, the undo of a batch, refused after a Ctrl+Z or a later change. Tests on the in-memory store and in a real editor; the command line against the application on a scratch directory | proposed |
| 3 | Reading: every element with its `base` in `read_page`, `read_stroke`, the `base` of a page. Tests: strokes, images, PDF pages, a scanned page, the bound on points | proposed |
| 4 | The items of the elements: `remove_elements`, `place_elements`, `reorder_elements`, `restyle_text`, `add_stroke`, `restyle_stroke`, `add_image`. Tests: each item, stale, one undo for a batch, two sessions converging, the limits | proposed |
| 5 | The items of the pages and `rename_document`. Tests: each item, a removed page in the trash of the pages, stale, convergence | proposed |
| 6 | The library: `LibraryItem`, `LibraryApplier`, `list_folders`, `list_trash`, `propose_library`, their inverses in the activity, the limits per batch and per minute. Tests: each item and its undo, a shared document read only, nothing deleted for good | proposed |
| 7 | The review of the new items: the panel of the editor (before and after of an element, a page), the library items and the activity in the sheet of the library, with Undo; the strings in the seven languages. Tests: screenshots (`verifyRoborazziJvm`), a real editor, the desktop application on a scratch directory. The clicks in its window are left to the user | proposed |
| 8 | The command line and MCP: the new tools in `tools/list`, `pencilnotes propose-library`, `pencilnotes activity`, `pencilnotes undo <batch>`, the exit codes; the help of the commands. Tests: the commands against the application on a scratch directory | proposed |

After phase 2 a closed document can be changed with the items of today; after phase 5 every change of a
document is there; phases 6–8 add the library and the commands.

## Decisions

Taken with the user (2026-11-06):

1. **Review or apply at once is a kept setting** (`applyDirectly`), not of a connection.
2. **Removing stops at the trash**: no tool deletes for good or empties it.
3. **The scope**: the elements of a page, the pages, the documents and the folders.

Taken alone, revertible:

4. **`allDocuments` keeps `documents`**: turning it off goes back to the documents allowed one by one.
5. **The per-connection direct mode goes**: two switches for one thing would be confusing, and the
   one of a connection never worked for the command line.
6. **Library items are reviewed in the library** when `applyDirectly` is off, like a document proposal
   in its editor.
7. **A stroke is restyled by a copy with a new id**: no new `DocumentChange`, so no protocol change.
8. **The activity is in memory**, like the proposals: keeping it would be a new stored format, and what
   it undoes is in the trash anyway.
9. **Limits on trashing** per batch and per minute, refused beyond them: a model in a loop cannot empty
   the library into the trash before the user looks.
10. **The commands stay on the desktop**, the application open, as in `docs/agent-tools.md`.

## Left for later

* A change of the style of a stroke that keeps its id: a protocol change.
* Moving an element from one page to another, or to another document (`docs/cross-document-paste.md`
  has the copy).
* The sharing of documents, the keys, the settings of the application: no tool, as today.
* The highlights and marks of the texts (`RestyleMarkups`).
* A headless host working with the application closed.
