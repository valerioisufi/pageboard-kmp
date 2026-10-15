# Module drawview-store-room

The store of the documents in Room (SQLite) on Android, iOS and the desktop:
`RoomDocumentStore`; `DrawViewStore.documents` is the one of the data directory of the
application. Every version of the schema comes with its migration (`DRAW_DATABASE_MIGRATIONS`):
the documents are never dropped, a database without its migration is not opened.
