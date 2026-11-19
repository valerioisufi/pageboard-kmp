# Module pageboard-store-room

The store of the documents in Room (SQLite) on Android, iOS and the desktop:
`RoomDocumentStore`, over the database `createPageboardDatabase()` opens in the data directory of the
application: the application opens it once per process, and gives the store its stroke modeller. Every version of the schema comes with its migration (`DRAW_DATABASE_MIGRATIONS`):
the documents are never dropped, a database without its migration is not opened.
