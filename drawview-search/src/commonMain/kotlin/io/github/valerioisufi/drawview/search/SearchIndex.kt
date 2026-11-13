package io.github.valerioisufi.drawview.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.valerioisufi.drawview.ml.ModelCatalog
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.dataDirectory
import io.github.valerioisufi.drawview.platform.syncLock
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * A [SearchChunk] stored in [SearchIndex], with its row [id] and the SHA-256 [hash] of its
 * formatted embedding text.
 */
public data class IndexedChunk(
    public val id: Long,
    public val chunk: SearchChunk,
    public val hash: String
)

/** An [IndexedChunk] with its relevance [score] (higher is better). */
public data class ScoredChunk(
    public val indexed: IndexedChunk,
    public val score: Double
)

/** A chunk whose embedding vector is not yet stored in [SearchIndex]. */
public data class UnembeddedChunk(
    public val hash: String,
    public val title: String,
    public val text: String
)

/**
 * An embedding vector truncated to [dimensions] and quantized to signed 8-bit integers with a
 * [scale] factor (`float[i] ≈ bytes[i] * scale`).
 */
public class QuantizedVector(
    public val scale: Float,
    public val bytes: ByteArray
) {
    /** Number of dimensions of this vector. */
    public val dimensions: Int get() = bytes.size

    /** Cosine similarity with a normalized [query] vector. */
    public fun dot(query: FloatArray): Float {
        val n = minOf(bytes.size, query.size)
        var sum = 0f
        for (i in 0 until n) {
            sum += query[i] * bytes[i]
        }
        return sum * scale
    }

    /** Reconstructs the floating-point vector. */
    public fun toFloatArray(): FloatArray = FloatArray(bytes.size) { i -> bytes[i] * scale }

    override fun equals(other: Any?): Boolean =
        other is QuantizedVector && other.scale == scale && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = scale.hashCode() * 31 + bytes.contentHashCode()

    public companion object {
        /**
         * Truncates [vector] to [dimensions], L2-normalizes it, and quantizes it to signed 8-bit
         * integers.
         */
        public fun fromFloatArray(
            vector: FloatArray,
            dimensions: Int = EmbeddingPrompts.DEFAULT_DIMENSIONS
        ): QuantizedVector {
            val normalized = truncateAndNormalize(vector, dimensions)
            var maxAbs = 0f
            for (v in normalized) {
                val a = abs(v)
                if (a > maxAbs) maxAbs = a
            }
            val scale = if (maxAbs > 0f) maxAbs / 127f else 1f
            val bytes = ByteArray(normalized.size) { i ->
                (normalized[i] / scale).roundToInt().coerceIn(-127, 127).toByte()
            }
            return QuantizedVector(scale, bytes)
        }

        /** Truncates [vector] to [dimensions] and normalizes it to unit length. */
        public fun truncateAndNormalize(
            vector: FloatArray,
            dimensions: Int = EmbeddingPrompts.DEFAULT_DIMENSIONS
        ): FloatArray {
            val size = minOf(dimensions, vector.size)
            val cut = vector.copyOf(size)
            var sumSq = 0.0
            for (v in cut) sumSq += v.toDouble() * v.toDouble()
            val norm = sqrt(sumSq).toFloat()
            if (norm > 0f) {
                for (i in cut.indices) cut[i] /= norm
            }
            return cut
        }
    }
}

/**
 * The search index of this device (`search/index.db` in the data directory by default, or
 * `":memory:"` in tests), backed by `BundledSQLiteDriver` (Pure Fabrication).
 *
 * It never touches the database of the documents or the sync log. When the `meta` table on disk
 * does not match [formatVersion], [embeddingModel], [dimensions] or [promptVersion], a new index
 * is built beside the old one (`<path>.new`) while queries keep reading the old one, and replaces
 * it atomically when [completeRebuild] is called.
 */
public class SearchIndex(
    public val path: String = defaultPath(),
    public val formatVersion: Int = FORMAT_VERSION,
    public val embeddingModel: String = ModelCatalog.EMBEDDING_GEMMA_2_TEXT.id,
    public val dimensions: Int = EmbeddingPrompts.DEFAULT_DIMENSIONS,
    public val promptVersion: Int = EmbeddingPrompts.VERSION
) : AutoCloseable {
    private val driver = BundledSQLiteDriver()
    private val lock = SyncLock()
    private val revisionState = MutableStateFlow(0L)

    /** Increments whenever the index is modified. */
    public val revision: StateFlow<Long> = revisionState.asStateFlow()

    private var readConnection: SQLiteConnection
    private var writeConnection: SQLiteConnection
    private var rebuilding: Boolean = false
    private var closed: Boolean = false
    private var cachedChunkVectors: List<Pair<IndexedChunk, QuantizedVector>>? = null

    /** Whether a new index is currently being built beside an older one whose metadata changed. */
    public val isRebuilding: Boolean get() = syncLock(lock) { rebuilding }

    init {
        ensureParentDirectory(path)
        val initial = driver.open(path)
        if (path != MEMORY_PATH) restrictToOwner(path)
        createSchema(initial)
        val existingMeta = readMeta(initial)
        val expected = expectedMeta()
        if (existingMeta.isEmpty()) {
            writeMeta(initial, expected)
            readConnection = initial
            writeConnection = initial
        } else if (existingMeta == expected) {
            readConnection = initial
            writeConnection = initial
        } else {
            readConnection = initial
            writeConnection = openRebuildConnection()
            rebuilding = true
        }
    }

    /** Metadata stored in the active index (`readConnection`). */
    public fun metadata(): Map<String, String> = syncLock(lock) {
        checkNotClosed()
        readMeta(readConnection)
    }

    /**
     * Completes a side-by-side rebuild if [isRebuilding] is `true`, replacing the old index with
     * the new one.
     */
    public fun completeRebuild() {
        syncLock(lock) {
            checkNotClosed()
            if (!rebuilding) return
            if (path == MEMORY_PATH) {
                readConnection.close()
                readConnection = writeConnection
                rebuilding = false
            } else {
                val rebuildPath = rebuildPath(path)
                readConnection.close()
                writeConnection.close()
                deleteDatabaseFiles(path)
                SystemFileSystem.atomicMove(Path(rebuildPath), Path(path))
                deleteSidecarFiles(rebuildPath)
                restrictToOwner(path)
                val reopened = driver.open(path)
                readConnection = reopened
                writeConnection = reopened
                rebuilding = false
            }
            cachedChunkVectors = null
            revisionState.value++
        }
    }

    /** The indexed `(documentId -> version)` pairs for [sourceId]. */
    public fun documentVersions(sourceId: String): Map<String, String> = syncLock(lock) {
        checkNotClosed()
        val result = LinkedHashMap<String, String>()
        writeConnection.prepare("SELECT documentId, version FROM documents WHERE sourceId = ?").use { stmt ->
            stmt.bindText(1, sourceId)
            while (stmt.step()) {
                result[stmt.getText(0)] = stmt.getText(1)
            }
        }
        result
    }

    /** All document identifiers currently present in the index. */
    public fun indexedDocumentIds(): Set<String> = syncLock(lock) {
        checkNotClosed()
        val ids = LinkedHashSet<String>()
        writeConnection.prepare("SELECT DISTINCT documentId FROM documents UNION SELECT DISTINCT documentId FROM chunks").use { stmt ->
            while (stmt.step()) {
                ids.add(stmt.getText(0))
            }
        }
        ids
    }

    /**
     * Replaces the chunks of [documentId] from [sourceId] with [chunks] and records [version].
     * Existing vectors in `vectors` are kept by hash so unchanged chunks do not need re-embedding.
     */
    public fun replaceChunks(
        documentId: String,
        sourceId: String,
        version: String,
        chunks: List<SearchChunk>
    ) {
        syncLock(lock) {
            checkNotClosed()
            val conn = writeConnection
            conn.execSQL("BEGIN IMMEDIATE TRANSACTION")
            try {
                deleteChunksOfSource(conn, documentId, sourceId)
                for (chunk in chunks) {
                    val hash = EmbeddingPrompts.hash(chunk.title, chunk.text)
                    val rowId = insertChunk(conn, documentId, chunk, hash)
                    conn.prepare("INSERT INTO chunks_fts(rowid, text) VALUES (?, ?)").use { fts ->
                        fts.bindLong(1, rowId)
                        fts.bindText(2, "${chunk.title}\n${chunk.text}")
                        fts.step()
                    }
                }
                conn.prepare("INSERT OR REPLACE INTO documents(documentId, sourceId, version) VALUES (?, ?, ?)").use { stmt ->
                    stmt.bindText(1, documentId)
                    stmt.bindText(2, sourceId)
                    stmt.bindText(3, version)
                    stmt.step()
                }
                conn.execSQL("COMMIT")
            } catch (e: Throwable) {
                runCatching { conn.execSQL("ROLLBACK") }
                throw e
            }
            cachedChunkVectors = null
            revisionState.value++
        }
    }

    /** Removes all chunks and the recorded version of [documentId] for [sourceId]. */
    public fun removeSource(documentId: String, sourceId: String) {
        syncLock(lock) {
            checkNotClosed()
            val conn = writeConnection
            conn.execSQL("BEGIN IMMEDIATE TRANSACTION")
            try {
                deleteChunksOfSource(conn, documentId, sourceId)
                conn.prepare("DELETE FROM documents WHERE documentId = ? AND sourceId = ?").use { stmt ->
                    stmt.bindText(1, documentId)
                    stmt.bindText(2, sourceId)
                    stmt.step()
                }
                conn.execSQL("COMMIT")
            } catch (e: Throwable) {
                runCatching { conn.execSQL("ROLLBACK") }
                throw e
            }
            cachedChunkVectors = null
            revisionState.value++
        }
    }

    /** Removes all chunks and versions of [documentId] across all sources. */
    public fun removeDocument(documentId: String) {
        syncLock(lock) {
            checkNotClosed()
            val conn = writeConnection
            conn.execSQL("BEGIN IMMEDIATE TRANSACTION")
            try {
                conn.prepare("DELETE FROM chunks_fts WHERE rowid IN (SELECT id FROM chunks WHERE documentId = ?)").use { stmt ->
                    stmt.bindText(1, documentId)
                    stmt.step()
                }
                conn.prepare("DELETE FROM chunks WHERE documentId = ?").use { stmt ->
                    stmt.bindText(1, documentId)
                    stmt.step()
                }
                conn.prepare("DELETE FROM documents WHERE documentId = ?").use { stmt ->
                    stmt.bindText(1, documentId)
                    stmt.step()
                }
                conn.execSQL("COMMIT")
            } catch (e: Throwable) {
                runCatching { conn.execSQL("ROLLBACK") }
                throw e
            }
            cachedChunkVectors = null
            revisionState.value++
        }
    }

    /**
     * Distinct chunks in the index that do not yet have a vector in `vectors`, up to [limit].
     */
    public fun unembeddedChunks(limit: Int = 64): List<UnembeddedChunk> = syncLock(lock) {
        checkNotClosed()
        if (limit <= 0) return emptyList()
        val result = ArrayList<UnembeddedChunk>()
        val sql = """
            SELECT c.hash, MIN(c.title), MIN(c.text)
            FROM chunks c
            LEFT JOIN vectors v ON v.hash = c.hash
            WHERE v.hash IS NULL
            GROUP BY c.hash
            LIMIT ?
        """.trimIndent()
        writeConnection.prepare(sql).use { stmt ->
            stmt.bindLong(1, limit.toLong())
            while (stmt.step()) {
                result.add(
                    UnembeddedChunk(
                        hash = stmt.getText(0),
                        title = stmt.getText(1),
                        text = stmt.getText(2)
                    )
                )
            }
        }
        result
    }

    /** Number of distinct chunk hashes in the index that do not yet have a vector. */
    public fun unembeddedCount(): Int = syncLock(lock) {
        checkNotClosed()
        val sql = """
            SELECT COUNT(DISTINCT c.hash)
            FROM chunks c
            LEFT JOIN vectors v ON v.hash = c.hash
            WHERE v.hash IS NULL
        """.trimIndent()
        writeConnection.prepare(sql).use { stmt ->
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }
    }

    /** Stores quantized embedding vectors keyed by chunk [EmbeddingPrompts.hash]. */
    public fun putVectors(vectors: Map<String, QuantizedVector>) {
        if (vectors.isEmpty()) return
        syncLock(lock) {
            checkNotClosed()
            val conn = writeConnection
            conn.execSQL("BEGIN IMMEDIATE TRANSACTION")
            try {
                conn.prepare("INSERT OR REPLACE INTO vectors(hash, scale, bytes) VALUES (?, ?, ?)").use { stmt ->
                    for ((hash, vector) in vectors) {
                        stmt.bindText(1, hash)
                        stmt.bindDouble(2, vector.scale.toDouble())
                        stmt.bindBlob(3, vector.bytes)
                        stmt.step()
                        stmt.reset()
                    }
                }
                conn.execSQL("COMMIT")
            } catch (e: Throwable) {
                runCatching { conn.execSQL("ROLLBACK") }
                throw e
            }
            cachedChunkVectors = null
            revisionState.value++
        }
    }

    /**
     * Searches the words of [query] with FTS5 (`unicode61 remove_diacritics 2`, BM25 ranking, with
     * the last word matched as a prefix while typed).
     *
     * Chunks matching all query words are ranked first, followed by chunks matching a subset of
     * the query words ordered by their BM25 sum. With [documentIds], only the chunks of those
     * documents count, so that the best ones elsewhere do not fill the [limit].
     */
    public fun searchWords(query: String, limit: Int = DEFAULT_SEARCH_LIMIT, documentIds: Set<String>? = null): List<ScoredChunk> = syncLock(lock) {
        checkNotClosed()
        if (limit <= 0) return emptyList()
        val words = searchWordsOf(query)
        if (words.isEmpty()) return emptyList()

        val conn = readConnection
        val collected = LinkedHashMap<Long, ScoredChunk>()

        // 1. Exact/prefix match of all words (or content words when there are stopwords)
        val contentWords = words.filter { it.length > 1 && it !in STOPWORDS }.ifEmpty { words }
        val allTermsExpr = ftsExpression(contentWords, operator = "AND", prefixLast = true)
        queryFts(conn, allTermsExpr, limit, documentIds).forEach { scored ->
            collected[scored.indexed.id] = scored.copy(score = scored.score + ALL_WORDS_BOOST)
        }

        // 2. Any content word (BM25 sum ranks chunks with more and rarer words higher)
        if (contentWords.size > 1 && collected.size < limit) {
            val anyTermExpr = ftsExpression(contentWords, operator = "OR", prefixLast = true)
            for (scored in queryFts(conn, anyTermExpr, limit * 2, documentIds)) {
                if (collected.size >= limit) break
                if (scored.indexed.id !in collected) {
                    collected[scored.indexed.id] = scored
                }
            }
        }

        collected.values.sortedByDescending { it.score }.take(limit)
    }

    /**
     * Compares [queryVector] with every stored chunk vector in memory (brute force) and returns
     * the top [limit] chunks by cosine similarity; with [documentIds], of those documents only.
     */
    public fun searchVectors(queryVector: FloatArray, limit: Int = DEFAULT_SEARCH_LIMIT, documentIds: Set<String>? = null): List<ScoredChunk> = syncLock(lock) {
        checkNotClosed()
        if (limit <= 0) return emptyList()
        val pairs = chunkVectorsLocked().let { all ->
            if (documentIds == null) all else all.filter { (chunk, _) -> chunk.chunk.anchor.documentId in documentIds }
        }
        if (pairs.isEmpty()) return emptyList()
        val normalizedQuery = QuantizedVector.truncateAndNormalize(queryVector, dimensions)
        pairs.map { (chunk, vector) ->
            ScoredChunk(chunk, vector.dot(normalizedQuery).toDouble())
        }
            .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.indexed.id })
            .take(limit)
    }

    /** All chunks currently in the active index. */
    public fun allChunks(): List<IndexedChunk> = syncLock(lock) {
        checkNotClosed()
        val list = ArrayList<IndexedChunk>()
        val sql = """
            SELECT id, documentId, sourceId, kind, anchorType, pageId, pageNumber,
                   leftMm, topMm, rightMm, bottomMm, lectureId, startMs, title, text, hash
            FROM chunks
            ORDER BY id ASC
        """.trimIndent()
        readConnection.prepare(sql).use { stmt ->
            while (stmt.step()) {
                list.add(readIndexedChunk(stmt))
            }
        }
        list
    }

    override fun close() {
        syncLock(lock) {
            if (closed) return
            closed = true
            cachedChunkVectors = null
            runCatching { readConnection.close() }
            if (writeConnection !== readConnection) {
                runCatching { writeConnection.close() }
            }
        }
    }

    private fun chunkVectorsLocked(): List<Pair<IndexedChunk, QuantizedVector>> {
        cachedChunkVectors?.let { return it }
        val loaded = ArrayList<Pair<IndexedChunk, QuantizedVector>>()
        val sql = """
            SELECT c.id, c.documentId, c.sourceId, c.kind, c.anchorType, c.pageId, c.pageNumber,
                   c.leftMm, c.topMm, c.rightMm, c.bottomMm, c.lectureId, c.startMs, c.title, c.text, c.hash,
                   v.scale, v.bytes
            FROM chunks c
            JOIN vectors v ON v.hash = c.hash
            ORDER BY c.id ASC
        """.trimIndent()
        readConnection.prepare(sql).use { stmt ->
            while (stmt.step()) {
                val indexed = readIndexedChunk(stmt)
                val vector = QuantizedVector(
                    scale = stmt.getDouble(16).toFloat(),
                    bytes = stmt.getBlob(17)
                )
                loaded.add(indexed to vector)
            }
        }
        cachedChunkVectors = loaded
        return loaded
    }

    private fun queryFts(conn: SQLiteConnection, matchExpression: String, limit: Int, documentIds: Set<String>?): List<ScoredChunk> {
        if (matchExpression.isBlank()) return emptyList()
        val result = ArrayList<ScoredChunk>()
        val sql = """
            SELECT c.id, c.documentId, c.sourceId, c.kind, c.anchorType, c.pageId, c.pageNumber,
                   c.leftMm, c.topMm, c.rightMm, c.bottomMm, c.lectureId, c.startMs, c.title, c.text, c.hash,
                   bm25(chunks_fts) AS rank
            FROM chunks_fts
            JOIN chunks c ON c.id = chunks_fts.rowid
            WHERE chunks_fts MATCH ?
            ORDER BY rank ASC, c.id ASC
            LIMIT ?
        """.trimIndent()
        conn.prepare(sql).use { stmt ->
            stmt.bindText(1, matchExpression)
            // Chunks of other documents do not count: read until the limit is reached among the others
            stmt.bindLong(2, if (documentIds == null) limit.toLong() else -1L)
            while (result.size < limit && stmt.step()) {
                val indexed = readIndexedChunk(stmt)
                if (documentIds != null && indexed.chunk.anchor.documentId !in documentIds) continue
                val bm25 = -stmt.getDouble(16)
                result.add(ScoredChunk(indexed, bm25.coerceAtLeast(0.0)))
            }
        }
        return result
    }

    private fun ftsExpression(words: List<String>, operator: String, prefixLast: Boolean): String =
        words.mapIndexed { index, word ->
            if (prefixLast && index == words.lastIndex) {
                "\"$word\"*"
            } else {
                "\"$word\""
            }
        }.joinToString(" $operator ")

    private fun deleteChunksOfSource(conn: SQLiteConnection, documentId: String, sourceId: String) {
        conn.prepare("DELETE FROM chunks_fts WHERE rowid IN (SELECT id FROM chunks WHERE documentId = ? AND sourceId = ?)").use { stmt ->
            stmt.bindText(1, documentId)
            stmt.bindText(2, sourceId)
            stmt.step()
        }
        conn.prepare("DELETE FROM chunks WHERE documentId = ? AND sourceId = ?").use { stmt ->
            stmt.bindText(1, documentId)
            stmt.bindText(2, sourceId)
            stmt.step()
        }
    }

    private fun insertChunk(conn: SQLiteConnection, documentId: String, chunk: SearchChunk, hash: String): Long {
        val sql = """
            INSERT INTO chunks(
                documentId, sourceId, kind, anchorType, pageId, pageNumber,
                leftMm, topMm, rightMm, bottomMm, lectureId, startMs, title, text, hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepare(sql).use { stmt ->
            stmt.bindText(1, documentId)
            stmt.bindText(2, chunk.sourceId)
            stmt.bindText(3, chunk.kind.name)
            when (val anchor = chunk.anchor) {
                is SearchAnchor.Document -> {
                    stmt.bindText(4, ANCHOR_DOCUMENT)
                    stmt.bindNull(5)
                    stmt.bindLong(6, 1L)
                    stmt.bindNull(7)
                    stmt.bindNull(8)
                    stmt.bindNull(9)
                    stmt.bindNull(10)
                    stmt.bindNull(11)
                    stmt.bindLong(12, 0L)
                }
                is SearchAnchor.Page -> {
                    stmt.bindText(4, ANCHOR_PAGE)
                    stmt.bindText(5, anchor.pageId)
                    stmt.bindLong(6, anchor.pageNumber.toLong())
                    val bounds = anchor.bounds
                    if (bounds != null) {
                        stmt.bindDouble(7, bounds.left.toDouble())
                        stmt.bindDouble(8, bounds.top.toDouble())
                        stmt.bindDouble(9, bounds.right.toDouble())
                        stmt.bindDouble(10, bounds.bottom.toDouble())
                    } else {
                        stmt.bindNull(7)
                        stmt.bindNull(8)
                        stmt.bindNull(9)
                        stmt.bindNull(10)
                    }
                    stmt.bindNull(11)
                    stmt.bindLong(12, 0L)
                }
                is SearchAnchor.Lecture -> {
                    stmt.bindText(4, ANCHOR_LECTURE)
                    stmt.bindNull(5)
                    stmt.bindLong(6, 1L)
                    stmt.bindNull(7)
                    stmt.bindNull(8)
                    stmt.bindNull(9)
                    stmt.bindNull(10)
                    stmt.bindText(11, anchor.lectureId)
                    stmt.bindLong(12, anchor.startMs)
                }
            }
            stmt.bindText(13, chunk.title)
            stmt.bindText(14, chunk.text)
            stmt.bindText(15, hash)
            stmt.step()
        }
        return conn.prepare("SELECT last_insert_rowid()").use { stmt ->
            check(stmt.step()) { "last_insert_rowid returned no row" }
            stmt.getLong(0)
        }
    }

    private fun readIndexedChunk(stmt: SQLiteStatement): IndexedChunk = IndexedChunk(
        id = stmt.getLong(0),
        chunk = SearchChunk(
            sourceId = stmt.getText(2),
            kind = SearchChunkKind.valueOf(stmt.getText(3)),
            anchor = readAnchor(
                anchorType = stmt.getText(4),
                documentId = stmt.getText(1),
                pageId = stmt.getTextOrNull(5),
                pageNumber = stmt.getLong(6).toInt(),
                leftMm = stmt.getFloatOrNull(7),
                topMm = stmt.getFloatOrNull(8),
                rightMm = stmt.getFloatOrNull(9),
                bottomMm = stmt.getFloatOrNull(10),
                lectureId = stmt.getTextOrNull(11),
                startMs = stmt.getLong(12)
            ),
            title = stmt.getText(13),
            text = stmt.getText(14)
        ),
        hash = stmt.getText(15)
    )

    private fun readAnchor(
        anchorType: String,
        documentId: String,
        pageId: String?,
        pageNumber: Int,
        leftMm: Float?,
        topMm: Float?,
        rightMm: Float?,
        bottomMm: Float?,
        lectureId: String?,
        startMs: Long
    ): SearchAnchor = when (anchorType) {
        ANCHOR_PAGE -> SearchAnchor.Page(
            documentId = documentId,
            pageId = pageId.orEmpty(),
            bounds = if (leftMm != null && topMm != null && rightMm != null && bottomMm != null) {
                Rect(leftMm, topMm, rightMm, bottomMm)
            } else {
                null
            },
            pageNumber = pageNumber
        )
        ANCHOR_LECTURE -> SearchAnchor.Lecture(
            documentId = documentId,
            lectureId = lectureId.orEmpty(),
            startMs = startMs
        )
        else -> SearchAnchor.Document(documentId)
    }

    private fun openRebuildConnection(): SQLiteConnection {
        val target = if (path == MEMORY_PATH) {
            MEMORY_PATH
        } else {
            val rPath = rebuildPath(path)
            deleteDatabaseFiles(rPath)
            rPath
        }
        val conn = driver.open(target)
        if (target != MEMORY_PATH) restrictToOwner(target)
        createSchema(conn)
        writeMeta(conn, expectedMeta())
        return conn
    }

    private fun expectedMeta(): Map<String, String> = mapOf(
        META_FORMAT to formatVersion.toString(),
        META_EMBEDDING_MODEL to embeddingModel,
        META_DIMENSIONS to dimensions.toString(),
        META_PROMPT_VERSION to promptVersion.toString()
    )

    private fun createSchema(conn: SQLiteConnection) {
        conn.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS documents (" +
                "documentId TEXT NOT NULL, " +
                "sourceId TEXT NOT NULL, " +
                "version TEXT NOT NULL, " +
                "PRIMARY KEY(documentId, sourceId))"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS chunks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "documentId TEXT NOT NULL, " +
                "sourceId TEXT NOT NULL, " +
                "kind TEXT NOT NULL, " +
                "anchorType TEXT NOT NULL, " +
                "pageId TEXT, " +
                "pageNumber INTEGER NOT NULL DEFAULT 1, " +
                "leftMm REAL, " +
                "topMm REAL, " +
                "rightMm REAL, " +
                "bottomMm REAL, " +
                "lectureId TEXT, " +
                "startMs INTEGER NOT NULL DEFAULT 0, " +
                "title TEXT NOT NULL, " +
                "text TEXT NOT NULL, " +
                "hash TEXT NOT NULL)"
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_chunks_document_source ON chunks(documentId, sourceId)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_chunks_hash ON chunks(hash)")
        conn.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(" +
                "text, tokenize = 'unicode61 remove_diacritics 2')"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS vectors (" +
                "hash TEXT PRIMARY KEY NOT NULL, " +
                "scale REAL NOT NULL, " +
                "bytes BLOB NOT NULL)"
        )
    }

    private fun readMeta(conn: SQLiteConnection): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        conn.prepare("SELECT key, value FROM meta").use { stmt ->
            while (stmt.step()) {
                map[stmt.getText(0)] = stmt.getText(1)
            }
        }
        return map
    }

    private fun writeMeta(conn: SQLiteConnection, values: Map<String, String>) {
        conn.prepare("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use { stmt ->
            for ((k, v) in values) {
                stmt.bindText(1, k)
                stmt.bindText(2, v)
                stmt.step()
                stmt.reset()
            }
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "SearchIndex is closed" }
    }

    private fun SQLiteStatement.getTextOrNull(index: Int): String? =
        if (isNull(index)) null else getText(index)

    private fun SQLiteStatement.getFloatOrNull(index: Int): Float? =
        if (isNull(index)) null else getDouble(index).toFloat()

    public companion object {
        /** Current schema format version stored in `meta`. */
        public const val FORMAT_VERSION: Int = 1

        /** Path used for an in-memory database (tests). */
        public const val MEMORY_PATH: String = ":memory:"

        internal const val DEFAULT_SEARCH_LIMIT: Int = 50
        private const val ALL_WORDS_BOOST: Double = 100.0

        private const val META_FORMAT = "format"
        private const val META_EMBEDDING_MODEL = "embedding_model"
        private const val META_DIMENSIONS = "dimensions"
        private const val META_PROMPT_VERSION = "prompt_version"

        private const val ANCHOR_DOCUMENT = "DOCUMENT"
        private const val ANCHOR_PAGE = "PAGE"
        private const val ANCHOR_LECTURE = "LECTURE"

        private val STOPWORDS = setOf(
            "il", "lo", "la", "le", "gli", "un", "una", "uno", "di", "da", "in", "su", "per",
            "con", "tra", "fra", "che", "chi", "cui", "non", "del", "al", "dal", "nel", "sul",
            "dei", "ai", "dai", "nei", "sui", "degli", "agli", "dagli", "negli", "sugli",
            "della", "alla", "dalla", "nella", "sulla", "delle", "alle", "dalle", "nelle", "sulle",
            "ed", "od", "se", "ma", "o", "e", "a", "i",
            "the", "of", "to", "in", "for", "on", "with", "as", "by", "at", "from", "or", "an", "is", "are"
        )

        /** Default path of the index (`search/index.db` in the data directory of the application). */
        public fun defaultPath(): String = dataDirectory() + "/search/index.db"

        internal fun rebuildPath(path: String): String = "$path.new"

        private fun ensureParentDirectory(path: String) {
            if (path == MEMORY_PATH) return
            val parent = Path(path).parent ?: return
            SystemFileSystem.createDirectories(parent)
        }

        private fun deleteDatabaseFiles(path: String) {
            if (path == MEMORY_PATH) return
            runCatching { SystemFileSystem.delete(Path(path), mustExist = false) }
            deleteSidecarFiles(path)
        }

        private fun deleteSidecarFiles(path: String) {
            if (path == MEMORY_PATH) return
            runCatching { SystemFileSystem.delete(Path("$path-wal"), mustExist = false) }
            runCatching { SystemFileSystem.delete(Path("$path-shm"), mustExist = false) }
            runCatching { SystemFileSystem.delete(Path("$path-journal"), mustExist = false) }
        }
    }
}
