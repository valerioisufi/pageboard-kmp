# The tools of the external models, by group

Phase 7 of `docs/architecture.md` (design C, High Cohesion): `DocumentTools` (`drawview-agent`,
1550 lines) held the registry of the tools, sixteen handlers, their helpers, the bounds of a
proposal, the JSON schemas and the entries answered. Split by responsibility, with the same
behaviour and the same public API: the tools, their order, their names, schemas and answers do not
change (the tests of `drawview-agent` and `drawview-agent-ui` unchanged).

The responsibilities are assigned following the GRASP principles.

## Design

* **Controller / Indirection.** `DocumentTools` stays the registry: its constructor (internal), the
  list of the tools in the order the models see them, `call` (the access, `ready`).
* **High Cohesion.** One class per group of tools, each a `ToolGroup`:
  * `ReadingTools`: `list_documents`, `read_document`, `read_page`, `read_pages`, `read_stroke`,
    `render_page`, `search`, `read_guide`, and how a page is read;
  * `ProposalTools`: `preview_proposal`, `propose`, `proposal_status`, `list_activity`,
    `undo_batch`, and the images, names and plans of a proposal;
  * `LibraryTools`: `list_folders`, `list_trash`, `propose_library`, and the access to what a
    change of the library touches.
* **Pure Fabrication.** `ToolGroup` holds what every group shares: the documents, the access, the
  engines and the one planner, `withDocument`, the access to the folders, the mode of a proposal,
  the JSON formats and the bounds of what is answered.
* **Information Expert.** In files of their own: `ProposalLimits` (the bounds of a proposal and
  what its items refer to in a document), `ToolSchema` (the schemas of the arguments, reading
  them), `ToolEntries` (the entries answered and how they are made).

## Phases

| Step | Content | Status |
|------|---------|--------|
| 1 | The registry, the three groups and their base, the limits, schemas and entries in files of their own | done |

## Decisions

Taken alone, revertible:

* **Groups by what a tool does** (read, propose, the library) rather than by element type: the
  tools are few, their helpers go with the group that uses them.
* **One planner shared by the groups**, made by the registry: reading a page tells its geometry as
  the planning does.
* **The helpers top-level `internal`** (they were `private` to the file), the groups `internal`:
  nothing new is public.
