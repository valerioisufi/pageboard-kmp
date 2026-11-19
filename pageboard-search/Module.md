# Module pageboard-search

The search in the notes of a Pageboard library, and the answers written from the passages found:
the index on the device (`SearchIndex`, an SQLite database of its own with FTS5 for the words and
quantized vectors for the meaning), the sources of the searchable pieces (`SearchSource`,
`DocumentSearchSource`), the incremental indexer (`SearchIndexer`), the search (`SearchEngine`,
words and EmbeddingGemma 2) and "Ask your notes" (`NotesAnswerer`).

Headless: no Compose; the interface in the library is `pageboard-search-ui`.
