package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.TrashedItem
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The tools of the library: its folders, its trash, the changes proposed to it. */
internal class LibraryTools(
    documents: OpenDocuments,
    access: AgentAccess,
    engines: AgentEngines,
    planner: ProposalPlanner,
    private val limits: ProposalLimits,
    private val library: LibraryBook,
    private val submitLibrary: suspend (LibraryProposal, review: Boolean) -> Unit,
    private val trashLimiter: TrashLimiter
) : ToolGroup(documents, access, engines, planner) {
    fun listFolders() = AgentTool(
        name = "list_folders",
        description = "The folders of the library: id, name and the folder they are in (none: the root). Given when the user allowed all the documents.",
        inputSchema = schema()
    ) { _, _ ->
        requireAllDocuments()
        val folders = documents.store.folders.first().map { FolderEntry(it.id, it.name, it.parentId) }
        value(buildJsonObject { put("folders", json.encodeToJsonElement(folders)) })
    }

    fun listTrash() = AgentTool(
        name = "list_trash",
        description = "What is in the trash of the library, the most recent first: id (to restore it), kind (document or folder), name, the folder it was in, when it went there (milliseconds). Only items whose documents are all allowed. The user empties the trash; you cannot.",
        inputSchema = schema()
    ) { _, _ ->
        val grants = access.grants.value
        val items = documents.store.trash.first()
            .filter { item -> if (item.kind == TrashedItem.Kind.FOLDER) grants.allDocuments else item.documentIds.all { grants.allows(it) } }
            .map { TrashEntry(it.id, it.kind.name.lowercase(), it.name, it.folderId, it.trashedAt) }
        value(buildJsonObject { put("items", json.encodeToJsonElement(items)) })
    }

    fun proposeLibrary() = AgentTool(
        name = "propose_library",
        description = "Proposes changes to the library: documents and folders created, moved, sent to the trash or restored. Answers the id of the proposal, to follow with proposal_status, and mode: review (the user reviews it) or applied (the user chose to apply changes at once: it is done already). With require_review true it waits for the user in any case. Nothing is deleted for good: the trash keeps it, and every change can be undone by the user. Once decided, created gives the ids of the documents made.",
        inputSchema = schema(required = listOf("title", "items")) {
            string("title", "What the proposal does, in a few words, for the user.")
            boolean("require_review", REQUIRE_REVIEW)
            putJsonObject("items") {
                put("type", "array")
                put("description", "What to do, in order.")
                putJsonObject("items") {
                    put("type", "object")
                    put("description", "kind \"create_document\" (title, folder?, width_mm?, height_mm?, background?: plain, ruled, grid or dotted, spacing_mm?, background_color?: #RRGGBB). kind \"move_document\" (document, folder?: none for the root). kind \"trash_document\" (document). kind \"create_folder\" (name, parent?). kind \"rename_folder\" (folder, name). kind \"move_folder\" (folder, parent?). kind \"trash_folder\" (folder): with all it holds. kind \"restore\" (item): an item of list_trash, back where it was. kind \"duplicate_document\" (document, title?, folder?): a copy of a document with all its pages, in its folder by default. kind \"import_archive\" (path, folder?): the notes of an archive of the application (.drawview) of this computer, in a new folder. Folders need the user to have allowed all the documents.")
                    putJsonObject("properties") {
                        putJsonObject("kind") {
                            put("type", "string")
                            putJsonArray("enum") {
                                listOf("create_document", "move_document", "trash_document", "create_folder", "rename_folder", "move_folder", "trash_folder", "restore", "duplicate_document", "import_archive")
                                    .forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("kind")) }
                }
            }
        }
    ) { arguments, connection ->
        val title = arguments.requiredString("title")
        val items = try {
            proposalJson.decodeFromJsonElement(ListOfLibraryItems, arguments["items"] ?: throw SerializationException("no items"))
        } catch (e: SerializationException) {
            throw AgentToolException(AgentErrorCode.BAD_REQUEST, "The items cannot be read: ${e.message?.take(200)}")
        } catch (e: IllegalArgumentException) {
            throw AgentToolException(AgentErrorCode.BAD_REQUEST, "The items cannot be read: ${e.message?.take(200)}")
        }
        limits.checkLibrary(title, items)
        checkLibraryAccess(items)
        trashLimiter.take(items.count { it.trashes })
        val name = access.connections.value.firstOrNull { it.id == connection }?.name ?: "A client"
        val proposal = library.add(name, title.trim(), items)
        val review = arguments.boolean("require_review") ?: false
        submitLibrary(proposal, review)
        value(proposalJson.encodeToJsonElement(library.find(proposal.id)!!.toStatus().copy(mode = modeOf(review))))
    }

    /** Refuses an item on a document, a folder or an item of the trash the client may not reach. */
    private suspend fun checkLibraryAccess(items: List<LibraryItem>) {
        fun document(id: String) {
            if (!access.allows(id)) throw AgentToolException(AgentErrorCode.NOT_GRANTED, "The user has not allowed the document \"${id.take(64)}\".")
        }
        for (item in items) {
            when (item) {
                is LibraryItem.CreateDocument -> if (item.folder != null) requireAllDocuments()
                is LibraryItem.MoveDocument -> {
                    document(item.document)
                    if (item.folder != null) requireAllDocuments()
                }
                is LibraryItem.TrashDocument -> document(item.document)
                is LibraryItem.DuplicateDocument -> {
                    document(item.document)
                    if (item.folder != null) requireAllDocuments()
                }
                // Its notes come in a folder made for them
                is LibraryItem.ImportArchive -> {
                    if (engines.files == null) throw AgentToolException(AgentErrorCode.UNAVAILABLE, "This application does not import archives.")
                    requireAllDocuments()
                }
                is LibraryItem.CreateFolder, is LibraryItem.RenameFolder, is LibraryItem.MoveFolder, is LibraryItem.TrashFolder -> requireAllDocuments()
                is LibraryItem.Restore -> {
                    val trashed = documents.store.trash.first().firstOrNull { it.id == item.item }
                        ?: throw AgentToolException(AgentErrorCode.NOT_FOUND, "There is no such item in the trash: list it again.")
                    if (trashed.kind == TrashedItem.Kind.FOLDER) requireAllDocuments() else trashed.documentIds.forEach(::document)
                }
            }
        }
    }
}
