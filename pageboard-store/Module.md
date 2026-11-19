# Module pageboard-store

The storage of the documents, headless: the ports (`DocumentCatalog` of the documents and
folders, `OperationLog` of the operations, `SharingStore` of the shared documents, all of them in
`DocumentStore`; `PreferenceStore` of the preferences as text by key), `OpenDocuments` (the
documents being changed, one session each, shared by the editor and the synchronization) and
`KeyValueDocumentStore`, a store on any key-value storage (in memory for the tests).
