# Copy and paste between documents

The elements selected with the lasso and the pages of a document can be copied in one document
and pasted in another: in the same window after opening the other document, or in another window
of the desktop application. Today the elements copied stay with the editor of their document, and
a page cannot be copied at all. This document starts from the code as it is (main at 26c6ce8e,
2026-10-25): how copy and paste work today, then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The clipboard belongs to the editor of a document.** `SelectionManager.clipboard`
   (`drawview-canvas`) is a `SelectionGroup` holding the model objects of the source document
   (`DrawStroke`, `DrawText`, `DrawImage`, `DrawCustomElement`), its bounding box, the index of its
   page and its rotation. Each `DrawViewController` has its own `SelectionManager`: closing the
   document, or opening another one, loses what was copied; two windows do not see each other's.
2. **Pasting copies the objects.** `pasteSelection(targetScreen)` makes new elements (new ids)
   from the objects copied, moved by 10 mm (or centered on the point of the context menu), on the
   page under the point or the first one shown, on top of the stack in their order
   (`DrawPage.stackOrder`), and submits `AddElements.of(page, pasted)`; they are selected.
3. **An image keeps its resource id.** `DrawImage.resourceId` names a `DrawResource` of *its*
   document (`DrawDocument.resources`). Pasted in another document, the id names nothing there:
   the image would be drawn blank, and the other devices would never receive its file.
4. **The files are kept once for every document.** `MediaManager.storedFile` reuses the file of
   the same content (`persistence.fileWithContent(hash)`), so two documents may already point at
   the same path. Room deletes a file with its document only when no other document points at it
   (`FileRepository.deleteDocument`, `resourceDao.countByUri`); `KeyValueDocumentStore` deletes the
   files of the document whatever the others point at (`deleteDocumentLocked`): a bug already
   there, hit by two documents importing the same image.
5. **The clipboard of the system carries the text only.** `EditorCommands` (`drawview-ui`) puts the
   text of the texts copied in the clipboard of the system and remembers it (`copiedText`, per
   editor): a paste takes the elements when the clipboard of the system still holds that text,
   else the text of the system as a new text box. Copying elements without a text leaves
   `copiedText` null, so a text copied earlier in another application wins over the elements
   just copied.
6. **The pages.** A page is a `DrawPage` with its size, its background (its own when
   `hasCustomBackground`, else the document's) and its elements of every kind: strokes, texts,
   images, custom elements, the PDF page (`DrawPdf`, naming a PDF resource) and the marks on its
   text (`TextMarkup`, naming the same resource). `AddPage(PageData)` inserts a page with its
   content where its order key puts it. The menus of a page (the context menu on the canvas, the
   menu of a page in the grid) add, delete, reorder and change the background; nothing copies a
   page, and the grid selects no pages.
7. **The editors.** `DocumentEditorViewModel` (`drawview-session`) creates the controller of the
   document it opens; PencilNotes and the sample have one on Android and iOS, one per window on the
   desktop (`main.kt`, `rememberDesktopWindowManager`).
8. **What travels.** A paste is made of the changes the editor already sends: `AddResource`,
   `AddElements`, `AddPage`. The file of a resource travels by its hash, in the document that names
   it.

## Requirements

1. **Elements across documents**: strokes, texts, images and custom elements copied (or cut) in a
   document are pasted in any other one, with the same look: images and their files included.
2. **Pages across documents**: a page copied in a document is pasted in another one (or the same:
   a duplicate), with its size, its background and all its content (PDF page and marks included),
   after the page chosen.
3. **Across windows** of the desktop application, and across the documents opened one after the
   other on Android and iOS, while the application runs.
4. **The last copy wins**: elements, pages, or a text copied in another application, whichever was
   copied last.
5. **Never lose a note**: a file shared by two documents stays as long as a document points at it,
   in every store; nothing stored changes format (no Room migration, no new stored format).
6. **Nothing new travels**: a paste is the changes made today (`ProtocolFormatTest` as it is, the
   protocol at 1); the other devices of the target document receive the elements, the pages and the
   files as for an image imported there.
7. **Undo** removes what was pasted, in one step.
8. **No global mutable state**: the clipboard is an instance the application gives its editors;
   an editor given none has its own, as today.
9. **Every platform**: the rule lives in common code (`drawview-model`, `drawview-canvas`).

## Design

### The experience

* **Elements**: Copy, Cut and Paste as today (the bar of the selection, the context menu, the
  shortcuts, the menu bar). Pasted in another document with the keyboard (no point given), they go
  on the page shown at the place they had on their page, kept inside the page; in the same
  document, 10 mm away, as today. With the context menu, centered on its point, as today.
* **Pages**: "Copy page" in the context menu of a page and in the menu of a page in the grid;
  "Paste page" beside it once a page was copied, putting the page after that one. Paste (the
  shortcut, the menu bar) with a page copied puts it after the page shown.
* **A file not here** (an image of another device not yet received, or deleted with its document
  meanwhile): the elements showing it are left out of the paste, and the editor says so.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| What was copied, detached from its document: the values of the elements or of the pages, the resources they name with their files, the bounds, the source document | `CopiedContent` (sealed: `CopiedElements`, `CopiedPages`), `CopiedResource` (`drawview-model`, headless) | Information Expert (it holds everything a paste needs), Protected Variations (the source document may be closed or deleted) |
| Making the changes that paste it into a document: new ids, the resources mapped (the one of the same content, else a new resource on the same file), the positions, the order keys | `ContentPaste` (`drawview-model`, `change` package), pure functions | Pure Fabrication, Creator (it creates the elements and the pages of the target), High Cohesion (one place for elements and pages), testable without Compose |
| The last thing copied, shared by the editors of an application, with the text put in the clipboard of the system | `EditorClipboard` (`drawview-canvas`, Compose state) | Pure Fabrication, Indirection (the editors know only the instance they are given) |
| Copying the selection, pasting it at a point | `SelectionManager.copySelection`, `cutSelection`, `pasteSelection` (`drawview-canvas`) | Controller, Information Expert (it knows the selection and the viewport) |
| Copying a page, pasting pages after one | `PageManager.copyPage`, `pastePages` (`drawview-canvas`) | Controller, Information Expert (it knows the pages and their order) |
| Choosing what a paste takes (elements, pages, a text of the system) | `EditorCommands.pasteAt` (`drawview-ui`) | Controller |
| Giving every editor the same clipboard | `DrawViewController(clipboard = …)`, `DocumentEditorViewModel(clipboard = …)`; PencilNotes and the sample create one per process | Creator (the application owns its editors), Low Coupling |
| Keeping a file shared by documents | `KeyValueDocumentStore.deleteDocumentLocked` (`drawview-store`), as Room does | Information Expert (the store knows which documents point at a file) |

### What is copied

```kotlin
/** A resource named by what was copied, with its file on this device (empty: not received yet). */
public class CopiedResource(public val resource: ResourceData, public val path: String)

/** What was copied in [sourceDocumentId], detached from it (see `docs/plans/cross-document-paste.md`). */
public sealed class CopiedContent {
    public abstract val sourceDocumentId: String
    public abstract val resources: List<CopiedResource>
}

/** Elements of a page: their values in stack order, their bounds and rotation on that page. */
public class CopiedElements(…, public val elements: List<ElementData>, public val bounds: Rect,
    public val baseRotation: Float, public val sourcePageId: String) : CopiedContent()

/** Pages with all their content, in their order. */
public class CopiedPages(…, public val pages: List<PageData>) : CopiedContent()
```

* The values (`ElementData`, `PageData`) are the ones the changes already carry: immutable, no
  model object of the source is kept, so closing or editing the source changes nothing copied.
* A page copied keeps the background it shows: its own, or the document's then (made its own),
  so that it looks the same in a document of another paper (decision 3).

### Pasting

`ContentPaste` builds one `CompositeChange` for the target document:

* **Resources**: for each resource named, the target's resource of the same hash when it has one
  (its id is used), else `AddResource.of(newId, type, path, hash)` on the same file (kept once, as
  for an import). A resource whose file is not here (empty path, or the file gone) is not added,
  and the elements naming it are left out (reported back to the editor).
* **Elements**: new ids, moved by the offset (a stroke by its transform, a box by its `x`, `y`),
  the resource id mapped, no order (`null`): `AddElements` puts them on top, in their order.
* **Pages**: new page ids and element ids, order keys after the page chosen
  (`FractionalIndex.between`), the resources of the PDFs and of the marks mapped, one `AddPage`
  each.
* The editor submits the change (one step to undo) and selects the elements pasted, or shows the
  first page pasted.

### The clipboard shared

```kotlin
/** The last content copied in the editors given this instance (one per application). */
@Stable
public class EditorClipboard {
    public var content: CopiedContent? by mutableStateOf(null)
    /** The text put in the clipboard of the system with [content] (see `EditorCommands`). */
    public var systemText: String? by mutableStateOf(null)
}
```

* `DrawViewController` takes `clipboard: EditorClipboard = EditorClipboard()`;
  `DocumentEditorViewModel` passes the one it is given. PencilNotes and the sample create one per
  process (the desktop one in `application {}`, for all its windows; on Android outside the
  activity, so that it survives a rotation).
* **The last copy wins** (requirement 4): copying puts in the clipboard of the system the text of
  the texts copied when there is one, and remembers the text the system holds after the copy
  (`systemText`). A paste takes the content of the editor when the system still holds
  `systemText`, else the text of the system. Copying elements without a text no longer lets an
  older text of another application win.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `KeyValueDocumentStore` keeps the files other documents point at when a document is deleted; test in `drawview-store` (two documents with the same image, one deleted, the file kept; the last deleted, the file deleted) | Done |
| 2 | `CopiedContent`, `CopiedResource`, `ContentPaste` in `drawview-model`: copying elements and pages into values, the changes pasting them (ids, offsets, order on top, order keys, resources mapped by hash or added on the same file, elements of a missing file left out); tests in `drawview-model` (into another document and the same, images, PDF pages with their marks, custom elements, background kept, undo, what the changes send); ABI recorded on purpose | Done |
| 3 | `EditorClipboard` in `drawview-canvas`, given to `DrawViewController` and `DocumentEditorViewModel`; `SelectionManager` copy, cut and paste through it (placement in another document); `PageManager.copyPage`, `pastePages`; `EditorCommands` choosing what to paste by the last copy; tests in `drawview-canvas` (two controllers on one clipboard: elements, an image, a page; read-only target; the last copy wins); ABI recorded on purpose | Done |
| 4 | The menus: "Copy page" and "Paste page" in the context menu of a page and in the page grid, the strings in every language; PencilNotes and the sample sharing one clipboard (all the desktop windows); screenshots recorded again where the menus change; the desktop application on a scratch directory, copying between two windows | Done |

### Phase 1 results

* `KeyValueDocumentStore.deleteDocumentLocked` deletes a file of the document (and its copy in
  the storage) only when no other document points at it: the resources of the other documents
  are read from the documents open, else from the storage (`pathsOfOtherDocumentsLocked`), as
  `fileWithContent` does. Deleting a folder deletes its documents one after the other, so a file
  shared only by documents of the folder goes with the last of them.
* Tests: `SharedResourceFilesTest` (`drawview-store`, `jvmTest`, files in a temporary directory:
  a document deleted keeps the file another document points at, its own file deleted; the last
  document deleted from a store reloaded, the file deleted; a folder deleted keeps the file of a
  document outside it, the file of its documents only deleted). Both fail without the change.
* Nothing stored changed: the keys and the JSON of the storage are as they were; the public API
  of `drawview-store` is as it was (`checkKotlinAbi`). Room already kept the shared files
  (`FileRepository.deleteDocument`).

### Phase 2 results

* `CopiedContent.kt` (`drawview-model`, `change` package): `CopiedResource`, `CopiedContent`,
  `CopiedElements`, `CopiedPages`, and `DrawDocument.copyElements(page, elements, bounds,
  baseRotation)` and `DrawDocument.copyPages(pages)` making them. The elements are copied in
  their stacking order; a page is copied with its stack in its order, then its PDF pages and
  marks (outside of the stack), with the background it shows (`PageData.background` never
  `null`).
* `ContentPaste.kt`: `CopiedElements.pasteInto(document, page, dx, dy, fileIsHere)` and
  `CopiedPages.pasteInto(document, index, fileIsHere)` give `PastedElements` and `PastedPages`:
  the change (`null` when nothing is pasted), the model objects it adds (to select the elements,
  to show the pages), the elements left out. `fileIsHere` is `isFileHere` by default (a path not
  empty, of a file on this device), given by the tests.
  * Every element gets a new identifier and no order of its source: pasted elements go on top
    (`AddElements`), in their order. The texts start a content of their own (`Versions.NONE`):
    no stamp of the source document travels, so another device admits the paste whatever the
    clocks of the source (`DocumentSession.admits`).
  * The stack of a page pasted is kept with new orders under every order put on top
    (`ElementOrder.spread`, the keys a stack gets when keyed again): `AddPage` would otherwise put
    the kinds in their layers. The PDF pages and the marks get the order of the change.
  * The pages get order keys between the page before [index] and the next one, after pages of
    equal keys, as `DrawDocument.orderKeyAt` does.
  * A resource is the same one in its own document, else the target's resource of the same hash,
    else a new `AddResource` on the same file when it is here; a resource not here leaves out the
    elements naming it (a PDF page pasted without its PDF keeps its writing).
  * Resources and elements or pages are one `CompositeChange`: one step to undo.
* Tests: `ContentPasteTest` (`drawview-model`): elements pasted in another document keep their
  look and stack, with new identifiers, moved, the image on a new resource of the same file, the
  source as it was; another device of the target admits and receives every operation of the
  paste; the resource of the same content is reused; pasted in its own document an image keeps
  its resource, on top; an image whose file is not here is left out (nothing pasted when it was
  alone); what was copied does not follow a later change of the source; a page pasted between two
  pages of a document of another paper keeps its size, its background (made its own), its PDF
  page and its mark on one new resource, its stack (a text over a stroke, against the layers of
  the kinds), and is undone in one step; a page pasted twice in its document gives two duplicates
  on its resource; without its PDF a page keeps its writing.
* ABI of `drawview-model` recorded again on purpose: the new classes and functions only.
* Nothing stored or sent changed: the paste is made of `AddResource`, `AddElements` and
  `AddPage` as they are.

### Phase 3 results

* `EditorClipboard` (`drawview-canvas`): `content` (the `CopiedContent` copied last) and
  `systemText`, Compose state. `DrawViewController(clipboard = EditorClipboard())` and
  `DocumentEditorViewModel(clipboard = EditorClipboard())`: an editor given none keeps its own,
  as before.
* `SelectionManager`: `clipboard` (a `SelectionGroup` of the model objects) is gone; `copySelection`
  and `cutSelection` put `document.copyElements(…)` in the clipboard of the application;
  `pasteSelection(targetScreen)` pastes it with `pasteInto`. Without a point: 10 mm away in its own
  document, as before; in another document at the place it had on its page, shifted inside the
  page when it would cross its edges. Some elements left out: the message
  `PASTED_WITHOUT_MISSING_FILES`, the frame fitted around the others (`fitFrame`, taken out of
  `applyTransformation`). `canPaste()` is about elements only.
* `PageManager`: `copyPage(pageIndex)`, `canPastePages()`, `pastePages(pageIndex)` (after the page,
  at the end for an invalid index; the first page pasted shown, the message when elements are
  left out).
* `EditorCommands` (`drawview-ui`): `copiedText` is gone; after a copy (`shareCopied`) the text of
  the texts copied goes to the clipboard of the system, and what the system holds then is kept in
  `EditorClipboard.systemText`. `pasteAt` takes a text of the system that differs from it (copied
  after in another application), else the content: elements at the point, or pages after the page
  of the context menu (after the page shown without a point). `copyPage`, `canPastePages`,
  `pastePagesAfter` for the menus of phase 4. The message is shown by `DrawScreen`; its string and
  the strings of the menus of phase 4 in the seven languages.
* Tests: `CrossDocumentPasteTest` (`drawview-canvas`: elements copied in a document pasted in
  another at their place, selected, undone in one step, the source as it was; in their own
  document 10 mm away; on a smaller page inside it; an image whose file is not here left out with
  the message, the frame around the others; a page pasted in a document of another paper with its
  own and its content, undone; a read-only document takes nothing; editors given no clipboard keep
  their own), `CommandTest.theLastCopyWinsAcrossTheDocumentsAndTheOtherApplications`
  (`drawview-ui`: a page copied after a text of another application wins, a text copied after in
  another application wins, texts copied in a document reach the system and their elements are
  pasted in the other).
* Two behaviors of the paste of before kept, found by `ArrangeTest` and `EditorChangesTest`
  (`drawview-model`, `ContentPaste`): a stroke pasted takes the mesh it was copied with
  (`CopiedContent.strokeMeshes`, internal: the meshes never change), not modeled again, also in a
  page pasted; an element naming a resource its own document does not have (an image of no file)
  is pasted with that name as it was, not left out.
* ABI recorded again on purpose: `drawview-canvas` (`EditorClipboard`, the parameter of the
  controller, the message, the page functions; `SelectionManager.clipboard` removed),
  `drawview-session` (the parameter of the model), `drawview-ui` (the strings only).

### Phase 4 results

* The context menu of a page (`CanvasContextMenu`): "Copy page" after "Add page after"; the item
  Paste is named "Paste page after" when what a paste puts is pages (`EditorCommands.pastesPages`:
  pages copied last, no text of another application copied after), and puts them after the page
  of the menu.
* The menu of a page in the grid (`PageGridOverlay`, given the commands of the editor by
  `DrawScreen`): "Copy page", and "Paste page after" once pages were copied.
* `DocumentLibrary(clipboard = …)` (`drawview-explorer`) gives it to the editor of the library.
  PencilNotes and the sample keep one `EditorClipboard` for the process (a private value of
  `PencilNotesApp.kt` and `App.kt`, outside the activities of Android), given to the library and
  to every document window of the desktop: the editors of all the windows share it.
* Screenshots: `editor-context-menu-tablet-landscape-light` (`drawview-explorer`) recorded again,
  with "Copy page"; the others of explorer, ui, sync-ui, lecture-ui and export-ui unchanged.
* ABI recorded again on purpose: `drawview-explorer` (the parameter of `DocumentLibrary`),
  `drawview-ui` (a `ComposableSingletons` lambda named again by the compiler, nothing public).
* Checks: `jvmTest`, `checkKotlinAbi`, `verifyRoborazziJvm` of the five UI modules, the Android
  and iOS compilations, the two Android applications, the consumer checks. PencilNotes desktop run
  on a scratch directory (`-Ddrawview.dataDir`): it starts, its database at version 21, no error
  in its output. Its windows were not driven by hand: the copy between two windows goes through
  the same `EditorClipboard` the tests share between two editors; trying it on the maintainer's devices
  is left to the maintainer.

## Decisions

Proposed on 2026-10-25 and approved by the maintainer the same day ("implement phase 1"); every one
revertible:

1. **A clipboard of the application, not of the system.** What is copied stays in the memory of
   the application: across its documents and windows, not to another application, not after it
   is closed, not across devices. A format of its own in the clipboard of the system (a MIME type
   on the desktop and Android, a pasteboard type on iOS) is left for later: it needs a format
   written and read across versions, and code on every platform.
2. **The same file, not a copy.** An image or a PDF pasted points at the file it had (kept once by
   its content, as for an import): no bytes are copied; the stores keep a file while a document
   points at it (phase 1 makes `KeyValueDocumentStore` do so, as Room does).
3. **A page keeps the background it shows**, made its own when it followed its document: pasted in
   a notebook of another paper, the writing stays on its lines. The other choice, following the
   paper of the target, is a change of one line.
4. **Elements of a file not here are left out**, with a message: an image without its file would be
   drawn blank, and its file might never come to the devices of the target.
5. **Elements pasted with the keyboard in another document keep their place on the page**, kept
   inside it; in the same document they move by 10 mm, as today.
6. **One page at a time.** The grid selects no pages; `CopiedPages` holds a list, so copying several
   pages needs only a selection in the grid (left for later, unless wanted now).
7. **Paste with a page copied inserts the page** after the page shown, rather than doing nothing.

## Risks

* **The public API of `drawview-model`, `drawview-canvas`, `drawview-session` changes**
  (new classes, parameters with a default): nothing is published yet, the ABI is recorded again on
  purpose, the consumer checks run.
* **The lectures recorded on a page** (`drawview-lecture` attachments) are not copied with it: the
  page pasted has the writing, not the recording.
* **A large page** (thousands of strokes) is copied as values: the memory of its points, once.

## Left for later

* A format of the clipboard of the system: copying to other applications, across devices
  (Universal Clipboard), after the application is closed (decision 1).
* Selecting several pages in the grid to copy them (decision 6).
* Copying the lectures recorded with a page.
* Dragging elements or pages from one window to another.
