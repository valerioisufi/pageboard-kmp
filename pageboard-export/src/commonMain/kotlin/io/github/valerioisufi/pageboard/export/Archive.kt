package io.github.valerioisufi.pageboard.export

import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.change.DocumentSnapshot
import io.github.valerioisufi.pageboard.model.change.snapshot
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.platform.newResourcePath
import io.github.valerioisufi.pageboard.store.OpenDocuments
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Why an archive cannot be read (see [ArchiveImporter]): [reason], and what was found.
 */
public class ArchiveException(public val reason: Reason, message: String) : Exception(message) {
    /** What is wrong with an archive. */
    public enum class Reason {
        /** Not an archive of the application, or a damaged one. */
        NOT_AN_ARCHIVE,

        /** An archive of a later version of the application: it is to be updated first. */
        TOO_NEW
    }
}

/**
 * The archive of the application (see `docs/plans/archive.md`): notes with all their content, to keep
 * them apart or to bring them to another library. A ZIP, its entries stored:
 *
 * * `manifest.json`: `{"format": "pageboard-archive", "version": 1, "documents": [...]}`, each
 *   document with its `name`, its `folders` (the path of its folder in the library, from the folder
 *   exported), its `entry` and its `files` (the file of each resource, by its identifier);
 * * `documents/<n>/document.json`: the whole state of the note ([DocumentSnapshot], as JSON);
 * * `documents/<n>/files/<resource>`: the PDFs and the images of the note.
 *
 * Its [VERSION] grows when what it holds changes; a reader reads the versions up to its own, and a
 * later one is refused ([ArchiveException.Reason.TOO_NEW]) rather than read wrong.
 */
public object ArchiveFormat {
    /** What the manifest says it is. */
    public const val FORMAT: String = "pageboard-archive"

    /** The version of the archives written. */
    public const val VERSION: Int = 1

    /** The extension of an archive, without its dot. */
    public const val EXTENSION: String = "drawview"

    /**
     * The media type an archive is saved with. It is a ZIP, but declared as `application/zip` the
     * file picker of Android adds `.zip` to its name (`notes.drawview.zip`): a generic type keeps
     * the name given.
     */
    public const val MIME_TYPE: String = "application/octet-stream"

    /**
     * The name of the folder an archive called [fileName] is imported into: the name without the
     * extension of an archive, nor the `.zip` a file picker may have added (`notes.drawview.zip` →
     * `notes`).
     */
    public fun folderNameOf(fileName: String): String {
        var name = fileName.trim()
        for (suffix in listOf(".zip", ".$EXTENSION")) {
            if (name.endsWith(suffix, ignoreCase = true)) name = name.dropLast(suffix.length)
        }
        return name.ifBlank { EXTENSION }
    }

    internal const val MANIFEST = "manifest.json"

    /** The JSON of the archives: a note written as its snapshot is, the keys not known refused. */
    internal val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = false
    }
}

/**
 * Writes notes as an archive of the application ([ArchiveFormat]): their state copied in their
 * sessions (see `OpenDocument.edit`), so that a change arriving meanwhile cannot make an archive of
 * two states, and the files of their resources.
 */
public class ArchiveExporter(private val documents: OpenDocuments) {

    /**
     * The archive of the notes [items] (each with the path of its folder in the archive, see
     * [FolderDocumentItem.relativePath]); [progress] gets the note being written. The notes that
     * cannot be read any more are left out and told in [ArchiveResult.missing].
     */
    public suspend fun write(items: List<FolderDocumentItem>, progress: (index: Int, name: String) -> Unit = { _, _ -> }): ArchiveResult {
        val zip = ZipWriter()
        val manifest = ArrayList<JsonObject>()
        val missing = ArrayList<String>()
        var pages = 0
        for ((index, item) in items.withIndex()) {
            progress(index, item.summary.name)
            val open = documents.open(item.summary.id)
            if (open == null) {
                missing += item.summary.name
                continue
            }
            val (snapshot, resources) = try {
                open.edit { session -> session.document.snapshot() to session.document.resources.map { DocumentResource(it.id, it.type, it.path, it.hash) } }
            } finally {
                documents.close(open)
            }
            val entry = "documents/${manifest.size + 1}"
            zip.addEntry("$entry/document.json", ArchiveFormat.json.encodeToString(DocumentSnapshot.serializer(), snapshot).encodeToByteArray())
            val files = LinkedHashMap<String, String>()
            for (resource in resources) {
                val bytes = PlatformFiles.readBytes(resource.path) ?: continue
                val name = "$entry/files/${resource.id}.${extensionOf(resource)}"
                zip.addEntry(name, bytes)
                files[resource.id] = name
            }
            pages += snapshot.pages.size
            manifest += buildJsonObject {
                put("name", item.summary.name)
                put("folders", buildJsonArray { folderNames(item.relativePath).forEach { add(JsonPrimitive(it)) } })
                put("entry", "$entry/document.json")
                put("files", buildJsonObject { files.forEach { (id, name) -> put(id, name) } })
            }
        }
        val root = buildJsonObject {
            put("format", ArchiveFormat.FORMAT)
            put("version", ArchiveFormat.VERSION)
            put("documents", JsonArray(manifest))
        }
        zip.addEntry(ArchiveFormat.MANIFEST, root.toString().encodeToByteArray())
        return ArchiveResult(zip.toByteArray(), manifest.size, pages, missing)
    }

    private fun extensionOf(resource: DocumentResource): String =
        resource.path.substringAfterLast('/').substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 }
            ?: if (resource.type == DocumentResource.ResourceType.PDF) "pdf" else "bin"

    /** The folders of the path [relativePath] of a note in an archive of PDFs (its file name dropped). */
    private fun folderNames(relativePath: String): List<String> = relativePath.split('/').dropLast(1).filter { it.isNotEmpty() }
}

/** An archive written: its [bytes], the [documents] and [pages] in it, the notes that could not be read ([missing]). */
public class ArchiveResult(public val bytes: ByteArray, public val documents: Int, public val pages: Int, public val missing: List<String>)

/**
 * Brings the notes of an archive of the application ([ArchiveFormat]) into the library of
 * [documents]: always as new notes (new identifiers), in a new folder, their folders made again
 * under it; nothing of the library is changed or replaced. Their files are copied among the
 * resources of the application.
 *
 * An archive is a file from anywhere: it is read whole and checked before anything is added
 * ([ZipReader], the manifest, each note decoded); a note that cannot be read stops the import
 * before the first one is added.
 */
public class ArchiveImporter(private val documents: OpenDocuments) {

    /**
     * Imports the archive [bytes] into a new folder named [folderName] (made unique: "Name (2)")
     * in [parentId] (`null`: the root). The notes added. Throws an [ArchiveException] when the
     * archive cannot be read: nothing is added then.
     */
    public suspend fun import(bytes: ByteArray, folderName: String, parentId: String? = null): ImportResult {
        val entries = ZipReader.entries(bytes)
        val manifest = entries[ArchiveFormat.MANIFEST]?.let { parseManifest(it) }
            ?: throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "No manifest")
        val format = manifest["format"]?.jsonPrimitive?.contentOrNull
        val version = manifest["version"]?.jsonPrimitive?.intOrNull
        if (format != ArchiveFormat.FORMAT || version == null) throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "Not an archive: $format $version")
        if (version > ArchiveFormat.VERSION) throw ArchiveException(ArchiveException.Reason.TOO_NEW, "Version $version")

        // Everything read before anything is added
        class Read(val name: String, val folders: List<String>, val snapshot: DocumentSnapshot, val files: Map<String, ByteArray>)
        val read = (manifest["documents"]?.jsonArray ?: JsonArray(emptyList())).map { element ->
            val item = element.jsonObject
            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "A note without a name")
            val folders = item["folders"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
            val entry = item["entry"]?.jsonPrimitive?.contentOrNull?.let { entries[it] } ?: throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "The note $name is missing")
            val snapshot = try {
                ArchiveFormat.json.decodeFromString(DocumentSnapshot.serializer(), entry.decodeToString())
            } catch (e: Exception) {
                throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "The note $name cannot be read: ${e.message}")
            }
            val files = item["files"]?.jsonObject?.mapNotNull { (id, path) -> path.jsonPrimitive.contentOrNull?.let { entries[it] }?.let { id to it } }?.toMap().orEmpty()
            Read(name, folders, snapshot, files)
        }

        val store = documents.store
        val root = createFolder(uniqueFolderName(folderName, parentId), parentId)
        val folders = HashMap<List<String>, String?>().apply { put(emptyList(), root) }
        suspend fun folderOf(path: List<String>): String? = folders[path] ?: createFolder(path.last(), folderOf(path.dropLast(1))).also { folders[path] = it }

        var pages = 0
        for (note in read) {
            // New identifiers for the pages, elements and resources too: the note it came from may be here
            val (snapshot, files) = ArchiveIdentifiers.renew(note.snapshot, note.files)
            val document = snapshot.copy(id = newElementId(), title = note.name).toDocument(store.strokeModeller)
            for (resource in document.resources) {
                val content = files[resource.id] ?: continue
                val extension = if (resource.type == DocumentResource.ResourceType.PDF) "pdf" else "img"
                val path = newResourcePath("archive", extension)
                if (PlatformFiles.writeBytes(path, content)) resource.path = path
            }
            store.insertDocument(document, folderOf(note.folders))
            pages += document.pages.size
        }
        return ImportResult(read.size, pages, root)
    }

    private fun parseManifest(bytes: ByteArray): JsonObject = try {
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    } catch (e: Exception) {
        throw ArchiveException(ArchiveException.Reason.NOT_AN_ARCHIVE, "The manifest cannot be read: ${e.message}")
    }

    /** [name] made unique among the folders of [parentId]: "Name", "Name (2)"... */
    private suspend fun uniqueFolderName(name: String, parentId: String?): String {
        val taken = documents.store.folders.first().filter { it.parentId == parentId }.mapTo(HashSet()) { it.name }
        if (name !in taken) return name
        var n = 2
        while ("$name ($n)" in taken) n++
        return "$name ($n)"
    }

    /** Makes the folder [name] in [parentId] and gives its identifier (the one there, when it was there). */
    private suspend fun createFolder(name: String, parentId: String?): String? {
        val store = documents.store
        store.createFolder(name, parentId)
        return store.folders.first().firstOrNull { it.parentId == parentId && it.name == name }?.id
    }
}

/** An archive imported: the [documents] and [pages] added, in the folder [folderId]. */
public class ImportResult(public val documents: Int, public val pages: Int, public val folderId: String?)
