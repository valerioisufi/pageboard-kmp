package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.store.OpenDocuments
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The tools that reach beyond the notes (High Cohesion): the documents exported into files, and a
 * page shown in the editor. Both go through the application (`AgentEngines.exports`, `AgentEngines.show`);
 * nothing of the notes changes (see `docs/plans/agent-coverage.md`).
 */
internal class ExportTools(
    documents: OpenDocuments,
    access: AgentAccess,
    engines: AgentEngines,
    planner: ProposalPlanner
) : ToolGroup(documents, access, engines, planner) {

    fun export() = AgentTool(
        name = "export",
        description = "Exports notes into a new file of the folder of the exports of the application, never over another file, and answers its path and size. One document (document, and pages: ids of some of its pages): format pdf, png (one image, or a ZIP of one image a page) or markdown. Several documents (documents) or a folder with the folders under it (folder, when the user allowed all the documents): pdf_per_document (a ZIP keeping their folders) or combined_pdf. Any of them: drawview, an archive of the application that import_archive brings back.",
        inputSchema = schema(required = listOf("format")) {
            string("format", "pdf, png, markdown, pdf_per_document, combined_pdf or drawview.")
            string("document", "The id of one document.")
            putJsonObject("documents") {
                put("type", "array")
                put("description", "The ids of several documents.")
                putJsonObject("items") { put("type", "string") }
            }
            string("folder", "The id of a folder, with what it holds.")
            putJsonObject("pages") {
                put("type", "array")
                put("description", "The ids of the pages of the document to export, all of them by default.")
                putJsonObject("items") { put("type", "string") }
            }
        }
    ) { arguments, _ ->
        val exports = engines.exports ?: throw AgentToolException(AgentErrorCode.UNAVAILABLE, "This application does not export from here.")
        val request = requestOf(arguments)
        val exported = exports.export(request)
        value(buildJsonObject {
            put("path", exported.path)
            put("bytes", exported.bytes)
            put("documents", exported.documents)
            put("pages", exported.pages)
        })
    }

    /** The request of [arguments], checked against the access and the documents. */
    private suspend fun requestOf(arguments: JsonObject): AgentExportRequest {
        fun ids(name: String): List<String>? = (arguments[name] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { id -> id.isString }?.content ?: throw AgentToolException(AgentErrorCode.BAD_REQUEST, "$name are ids.")
        }
        val format = arguments.requiredString("format")
        val one = arguments.string("document")
        val several = ids("documents")
        val folder = arguments.string("folder")
        val pages = ids("pages")
        if (listOfNotNull(one, several, folder).size != 1) throw AgentToolException(AgentErrorCode.BAD_REQUEST, "export takes document, documents or folder.")
        if (pages != null && one == null) throw AgentToolException(AgentErrorCode.BAD_REQUEST, "pages go with one document.")
        val single = one != null || several?.size == 1
        val allowed = if (single) SINGLE_FORMATS + ANY_FORMATS else SEVERAL_FORMATS + ANY_FORMATS
        if (format !in allowed) throw AgentToolException(AgentErrorCode.REFUSED, "format is ${allowed.joinToString(", ")} here.")
        val documentIds = listOfNotNull(one) + several.orEmpty()
        if (documentIds.size > MAX_EXPORTED) throw AgentToolException(AgentErrorCode.REFUSED, "At most $MAX_EXPORTED documents are exported at once.")
        val known = documents.store.documents.first().mapTo(HashSet()) { it.id }
        for (id in documentIds) {
            if (!access.allows(id)) throw AgentToolException(AgentErrorCode.NOT_GRANTED, "The user has not allowed the document \"${id.take(64)}\".")
            if (id !in known) throw AgentToolException(AgentErrorCode.NOT_FOUND, "There is no document \"${id.take(64)}\".")
        }
        if (folder != null) {
            requireAllDocuments()
            if (documents.store.folders.first().none { it.id == folder }) throw AgentToolException(AgentErrorCode.NOT_FOUND, "There is no such folder.")
        }
        if (pages != null) {
            withDocument(one!!) { open ->
                open.edit { session ->
                    for (id in pages) session.document.pageById(id) ?: throw AgentToolException(AgentErrorCode.NOT_FOUND, "There is no page \"${id.take(64)}\" in this document.")
                }
            }
        }
        return AgentExportRequest(documentIds, folder, pages?.toSet(), format)
    }

    fun showPage() = AgentTool(
        name = "show_page",
        description = "Shows a page of a document in the editor of the application, opening the document when it is not: to show the user what you are talking about. Nothing of the document changes.",
        inputSchema = schema(required = listOf("document")) {
            string("document", "The id of the document.")
            string("page", "The id of the page; where the editor was by default.")
        }
    ) { arguments, _ ->
        val show = engines.show ?: throw AgentToolException(AgentErrorCode.UNAVAILABLE, "This application does not show pages from here.")
        val documentId = arguments.requiredString("document")
        val pageId = arguments.string("page")
        withDocument(documentId) { open ->
            if (pageId != null) open.edit { session -> session.document.pageById(pageId) } ?: throw AgentToolException(AgentErrorCode.NOT_FOUND, "There is no such page in this document.")
        }
        if (!show(documentId, pageId)) throw AgentToolException(AgentErrorCode.UNAVAILABLE, "The application cannot show it now.")
        value(buildJsonObject { put("shown", true) })
    }

    companion object {
        val SINGLE_FORMATS: List<String> = listOf("pdf", "png", "markdown")
        val SEVERAL_FORMATS: List<String> = listOf("pdf_per_document", "combined_pdf")
        val ANY_FORMATS: List<String> = listOf("drawview")
        const val MAX_EXPORTED: Int = 500
    }
}
