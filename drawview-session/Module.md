# Module drawview-session

The editor of the documents of a store: `DocumentEditorViewModel` opens a document through
`OpenDocuments`, creates its editor and writes its changes and the preferences of the user;
`EditorSessionScreen` shows it; `DrawViewSessions.flushAll` writes every pending change (e.g.
before the desktop application exits).
