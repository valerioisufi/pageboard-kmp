# External models on the documents

A model the application does not ship (Claude Code, Codex, a script, a service chosen by the user)
reads a document and proposes changes to it, from the terminal or from a panel of the application;
the user reviews what it proposes, and what is accepted is an ordinary change of the document: it is
stored, undone and synchronized as if the user had made it. This document starts from the code as it
is (`main` at d4b0c35e, 2026-11-03): what exists today, then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Every change is a value.** `DocumentChange` (`drawview-model`) is serializable, applies itself
   and gives the change undoing it; `CompositeChange` makes several changes one operation. What is
   removed is hidden, not destroyed (`DrawDocument.removedPages`, `DrawPage.removedElements`).
2. **One session per document, one process per database.** `OpenDocuments` keeps one
   `DocumentSession` per document, shared by the editor and the synchronization, and is passed to
   the library, the editor, the collaboration and the export. `createDrawDatabase` says that Room
   must not open the same file more than once.
3. **A second process would break the log.** `DocumentSession.submit` numbers an operation
   `applied[replica] + 1`, with the replica of the store (`store.replicaId()`, one per database). A
   second process on the database would make operations with the same `OperationId` as the
   application, with different contents; the application would not see them in its model either.
   No lock on the data directory was found in the code of the application, so two copies of it may
   already be able to open the same database (to be confirmed by phase 1, on a scratch directory).
4. **Undo belongs to the editor.** `HistoryManager` (`drawview-canvas`) records the inverse of every
   `ChangeOrigin.LOCAL` change of its session, per editor, 50 at most. A document with no editor open
   has no history.
5. **Reading.** `DocumentOutline` reads the texts, the custom elements and the PDF pages without
   modeling the strokes; `SearchEngine` searches the notes; `HandwritingReader` keeps the
   transcription of the handwriting (`internal` in `drawview-lecture`, tied to its models).
6. **Placing.** `TextFlow` and `PlacementPlanner` are headless (`drawview-model`), given the height
   of a text as a function; `TextPlacer` is the part in the canvas, tied to `DrawViewController`.
   `TextData.estimatedHeight` guesses a height without laying the text out, and a text box is laid out
   when drawn, not stored (`docs/text-heights.md`).
7. **Versions.** `TextData.versions` (`Versions`, with `covers`) tells what changes a text has seen.
8. **The models.** `ModelBackend` is `internal` to `drawview-lecture`, `ModelProvider` is a closed
   enum (`GEMINI`, `LOCAL`), stored in the settings of the assistant. The answers are structured by
   a schema; there are no tool calls.
9. **The extension points.** `EditorExtension` gives commands and panels, which the editor places
   beside the canvas or over it. `SecureStore` keeps the secrets.
10. **No way in for a local client.** The application has no local socket or command line today.
11. **JVM.** The libraries target JVM 11; `java.net.UnixDomainSocketAddress` needs JDK 16.

## Requirements

1. **Any model, without the application knowing it**: a client of the Model Context Protocol (MCP)
   and a plain terminal command use the same tools.
2. **Nothing changes in the document without the user**: by default a model proposes, the user
   accepts or rejects each item, and one accepted batch is undone in one step. Applying directly is a
   choice made for one connection, never a default.
3. **Never lose a note**: the model works on what it read; what changed after it read is not
   overwritten, the item is reported as stale.
4. **The application stays the only writer of its database**: no client opens it.
5. **The device stays closed by default**: the access is off until the user turns it on, is local to
   the machine, and is granted per document.
6. **What the model reads is untrusted, and so is what the client sends**: a shared document or an
   imported PDF may hold instructions addressed to the model, and a client may send anything. See
   [Threats](#threats).
7. **The other devices are not involved**: nothing new travels, the accepted changes are operations
   like any other.
8. **Tablets and phones too**, in a later stage: they have no terminal, so the model is called by the
   application itself, with the same tools.
9. **It degrades**: without the search no search tool, without a transcription no handwriting read,
   without the access no socket. The application without this module is the application of today.

### What a model can do

| | v1 |
|---|---|
| Texts, formulas and code in text boxes | read, replace, add, remove |
| Pages | read, add |
| PDF pages, images | read their text or see them, not change them |
| Handwriting | read its transcription and see the page, **not change it** |
| Trash, sharing, folders, deleting a document, keys | no tool at all |

## Design

```
Claude Code / Codex / script            panel in the application (stage 2)
        │ pencilnotes mcp | pencilnotes …       │ a model chosen by the user
        ▼ local socket                          ▼
  AgentEndpoint ───────────────► DocumentTools ◄──── AgentLoop
                                     │ reads            │
                                     ▼                  ▼
                               OpenDocuments      ProposalBook
                                                        │ the user accepts
                                                        ▼
                               DocumentSession.submit(CompositeChange)
                                      │ stored, undone, synchronized
```

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| What a model can read and propose, with its schema, whoever calls it | `DocumentTools`, `AgentTool` (`drawview-agent`) | Information Expert, High Cohesion, Polymorphism (one tool, every caller) |
| The items of a proposal turned into a `CompositeChange`, each checked against what it read | `ProposalApplier` (`drawview-agent`) | Information Expert, Pure Fabrication |
| The proposals waiting, their items and their outcome, in memory | `ProposalBook` (`drawview-agent`) | Creator, Controller |
| Which documents a client may reach, and how | `AgentAccess` (`drawview-agent`) | Information Expert, Protected Variations |
| The height of a text, the search, the handwriting, the image of a page: given by the application | `AgentEngines` (`drawview-agent`), ports with no default | Indirection, Protected Variations (rule 1 of the libraries) |
| One message of the client turned into a call of a tool and its answer, bounded and checked | `AgentEndpoint` (`drawview-agent`): JSON-RPC, no socket | Controller, Low Coupling |
| The local socket, the lock of the data directory, the command line, the MCP bridge | `AgentHost`, `DataDirectoryLock`, `AgentCli` (`pencilnotes/desktopApp`) | Low Coupling (not published: the libraries stay on JVM 11) |
| The review: the list of the proposals, the texts before and after, accept and reject | `AgentReviewPanel` (`drawview-agent-ui`), a panel and a command of `EditorExtension` | Controller, Polymorphism (no new hook in `EditorExtension`) |
| The access turned on, the documents granted, the clients connected | `AgentSettingsSection` (`drawview-agent-ui`) | Controller |
| Stage 2: a model asked with the tools, step by step | `AgentLoop`, `ChatModel` (port), `OpenAiCompatibleModel` (`drawview-agent`) | Indirection, Protected Variations |

`drawview-agent` is headless (model, store, serialization): no Compose, no socket, no provider in
stage 1. It joins the headless modules of `CLAUDE.md` and `docs/modularization.md`.

### The tools

Reading, on `OpenDocuments` (the document is opened if nobody uses it, and closed after):

| Tool | Answer |
|---|---|
| `list_documents` | the documents granted: id, title, number of pages |
| `read_document` | the pages in order: id, index, format, what is on them, in numbers |
| `read_page` | its texts (id, Markdown, box, `base`), the text of its PDF page, its handwriting when transcribed |
| `render_page` | the page as a PNG, bounded (when the application gives the port) |
| `search` | the passages found in the documents granted (when the application gives the search) |

Proposing:

| Tool | What |
|---|---|
| `propose` | a title and a list of items; answers a proposal id. The document is not touched |
| `proposal_status` | waiting, or the outcome of each item: accepted, rejected, stale |

The items:

| Item | Becomes | Its check when accepted |
|---|---|---|
| `replace_text(page, text, base, markdown)` | `UpdateTexts` | same `base` |
| `remove_text(page, text, base)` | `RemoveElements` | same `base` |
| `insert_text(page, markdown, after?)` | `AddElements`, `AddPage` for what does not fit | the page is there; placed when accepted |
| `add_page(after?)` | `AddPage`, in the format of the page it follows (`docs/new-page-format.md`) | the page it follows is there |

`base` is an opaque value made by the reading tools from `TextData.versions` and a hash of the text.
The hash is there because a text written before the versions has none.

### The proposal

```
propose → checked (limits, the document granted, not read only, not in the trash) → waiting
waiting → shown in the panel of the editor → each item accepted or rejected
accepted items → their base checked → one CompositeChange → submit → one entry of the history
```

* **One operation, one undo.** The items accepted are one `CompositeChange`, so `HistoryManager`
  records one inverse and one Ctrl+Z undoes the batch.
* **The review happens in the editor.** So there is always a `HistoryManager` when something is
  accepted. A proposal for a document that is not open asks the application to open it
  (`AgentEngines.openForReview`); the client gets `waiting` in any case.
* **The text being written.** A proposal on a text open in the text editor is shown as stale until
  it is closed: the two would otherwise replace each other.
* **Inserted text** is in the blue of the assistant, as the notes of a model
  (`docs/text-import.md`), at the size of its page (`TextSizePolicy`). A replaced text keeps its color.
* **Placement** is computed when accepted, on the page as it is then, by `TextFlow`, with the height
  from `AgentEngines` (the renderer in the application, `estimatedHeight` otherwise).
* **In memory only.** Quitting the application drops the proposals waiting: the client can propose
  again, the document was not touched, and no stored format is added. See
  [Decisions](#decisions).

### Direct mode

A client granted "apply directly" for one connection has its proposals accepted at once, with the
same checks, when the document is open in an editor (so that they can be undone); on a closed document
it stays a proposal. The grant ends with the connection. The panel shows what was applied.

### The local access

* **Off by default.** The setting starts the host; turning it off stops it and closes the clients.
* **A Unix domain socket in the data directory**, in a directory readable by the user only, never a
  port. It follows `drawview.dataDir`, so a scratch directory has its own and the real notes are out
  of reach of a test. On macOS the path of a socket is limited to about 104 bytes: when the path is
  longer the socket goes in a private directory of the temporary directory, named by the hash of the
  data directory (phase 3 tests it).
* **Per document.** A client names itself when it connects (not proof of anything: the name is for the
  user). A document is reached when granted from the application, for this connection or until
  revoked. A read of a document not granted answers an error, not a prompt: the prompts come from the
  application's list, so that a client cannot flood the user with them.
* **The grants** are preferences of the application: a new key, read as "none" when absent. No existing
  format changes.
* **Application closed:** the command fails with a clear message and its exit code. It does not start
  the application, and it does not open the database.

### The lock of the data directory

`DataDirectoryLock` takes a `FileLock` on a file of the data directory before the database is opened,
in `main`. A second copy of the application tells the user and exits, instead of writing on the same
database. The system releases the lock if the process dies. This closes point 3 of the starting point
for the application as well, whatever happens to the rest of this plan: that is why it is phase 1.

### The command line

The one binary of the application: `main` looks at its first argument before anything of the UI is
touched, so that a command opens no window and no icon in the Dock.

```bash
pencilnotes docs
pencilnotes read <document> [--page <n>] [--json]
pencilnotes search "<text>" [--json]
pencilnotes propose <document> --file <items.json>
pencilnotes status <proposal> [--wait]
pencilnotes mcp
```

`pencilnotes mcp` is the bridge a client of MCP starts: it reads and writes the messages on its
standard input and output and forwards them to the socket. The other commands are the same tools, for
a person or a script. The exit codes tell the cases apart: 0 done, 2 application not running or access
off, 3 document not granted, 4 refused by the checks, 5 items stale or rejected.

### Threats

| Threat | Answer |
|---|---|
| Instructions in a document, for the model (a shared document, a PDF): prompt injection | It cannot be stopped at the source, so it is contained: the review; the grant per document; no tool that reaches the network, the trash, the sharing, the keys; `search` limited to what is granted; the answers mark the content of a document as data |
| A client sending anything | `AgentEndpoint` bounds a message before reading it, checks its JSON nesting before decoding, never allocates a length announced; what is not understood fails the call or the connection, never the application |
| A proposal too large | At most a set number of items and characters per proposal and of proposals waiting per client; beyond them, refused |
| A model replacing a text with an empty or truncated one | It is shown before and after in the review; nothing is destroyed, and it is undone at once |
| Another process of the user | It can already read `~/.pencilnotes`. The socket adds no reach beyond the user, and writing passes the review |
| The content sent to a provider | The client is the user's: the application says so when the access is turned on. In stage 2, the panel names the provider and asks before the first request of a document |

No message reaches the other devices but the operations accepted, so what `docs/untrusted-members.md`
requires of the devices stays as it is. `PROTOCOL_VERSION` does not change and `ProtocolFormatTest` is
not re-recorded: a phase that needs either stops and asks.

### Stage 2: the panel in the application

`AgentLoop` asks a `ChatModel` with the tools of `DocumentTools`, one step after another, and ends in a
proposal, reviewed as any other. On every device.

* `ChatModel` is a port of `drawview-agent`; `OpenAiCompatibleModel` speaks the chat API many
  providers and Ollama offer, over the Ktor client of the project.
* The address, the model and the key are the settings of the panel; the key goes in `SecureStore`.
  They are new keys: `AssistantSettings` and `ModelProvider` of the lecture assistant stay as they are,
  so no stored settings change. Sharing the providers with it comes with the shared model runtime
  planned in `CLAUDE.md`, in its own plan.
* **Costs.** The panel shows an estimate before a request to a billed provider, with a limit of steps.
  The tests use a fake model: no paid request is run by them.

## Phases

One commit per phase on `feat/agent-tools`, with the checks of `CLAUDE.md`.

### Stage 1: from the terminal, on the desktop

| Phase | What | Status |
|---|---|---|
| 1 | `DataDirectoryLock` in the desktop application: a second copy told and closed; a test on a scratch directory that the lock is released when the holder dies | done |
| 2 | `drawview-agent`: `DocumentTools` (reading), `ProposalApplier`, `ProposalBook`, `AgentAccess`, `AgentEngines`, on the in-memory store. Tests: every item, `base` stale, read-only document, chained text, one operation and one inverse for a batch, undo restoring the document, two sessions converging on an accepted batch. Module, `Module.md`, `api/`, Dokka | proposed |
| 3 | `AgentEndpoint`, `AgentHost` on the socket, `AgentCli` and `pencilnotes mcp`, the setting turning it on. Tests: oversized and deeply nested messages, unknown methods, access off, path too long for a socket; the tools called by a real MCP client on a scratch directory | proposed |
| 4 | `drawview-agent-ui`: the review panel, the grants, the document opened for a review, direct mode, wired in PencilNotes. Screenshots (`verifyRoborazziJvm`), the desktop application on a scratch directory | proposed |

### Stage 2: every device

| Phase | What | Status |
|---|---|---|
| 5 | `AgentLoop`, `ChatModel`, a fake model for the tests, stop by a limit of steps, a stale item told back to the model | proposed |
| 6 | `OpenAiCompatibleModel`, the settings with the key in `SecureStore`, the estimate, the chat in the panel on Android, iOS and desktop. Tried with Ollama first (free); a billed provider only with an explicit yes | proposed |

Every phase of stage 1 is useful without the next: after phase 4 the feature is complete on the desktop,
and stage 2 can wait. Nothing in stage 1 depends on a provider.

## Decisions

Taken alone, revertible:

1. **A client never opens the database**: the socket to the running application is the only way in.
   This one follows from point 3 of the starting point, and reverting it needs a design of its own.
2. **The application must be open** for stage 1. A headless host would need the lock of phase 1 and a
   process the application hands over to: left for later.
3. **Proposals in memory**: quitting drops them. Keeping them is a new stored format with its tested
   migration, for little gain, since the model can propose again.
4. **The review by default, direct mode per connection**: a wrong batch is seen before it is on the
   page, not looked for afterwards among the pages.
5. **Handwriting is read only**: a model cannot rewrite it in the hand of the user, and removing ink
   on the strength of a transcription, which may be wrong, is not safe. Moving and removing strokes
   can be added as items later.
6. **Tools by meaning, not operations**: `DocumentChange` is the format of the log and travels between
   the devices. A public tool on it would make every change of that format a change for the clients.
7. **MCP by hand**: `initialize`, `tools/list` and `tools/call` over JSON-RPC, on
   `kotlinx.serialization`, for a small dependency and a parser of our own bounds. The official SDK is
   the alternative if the subset grows. The version of the specification is checked in phase 3.
8. **The socket, the lock and the command line stay in the application**, not in a published module:
   they are the only parts needing JDK 16, and another application may want another way in.
9. **One binary** for the application and the commands. A separate small launcher can come later if
   the start of the JVM is too slow for a command.
10. **No hook in `EditorExtension`**: a panel and a command are enough, like the import of a text.
11. **Blue for inserted text**: what a model wrote looks like what a model wrote (`docs/text-import.md`).

## Left for later

* A headless host working with the application closed.
* Moving and removing strokes, moving elements, images added by a model.
* Moving, removing and changing the format of pages.
* A proposal shown on the canvas, in place, rather than in a panel.
* The socket reached from another device of the user (an agent on the Mac on the tablet's documents):
  the accepted changes already reach it by the sync when the document is shared.
* The author of a change (a mark that it was written by a model): this would be a protocol change.
* Sharing the models with the lecture assistant, in the plan of the shared model runtime.
