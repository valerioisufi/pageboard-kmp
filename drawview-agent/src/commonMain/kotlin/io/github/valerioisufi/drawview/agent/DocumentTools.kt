package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
/** What a tool answers. */
public sealed interface ToolResult {
    /** A value, as JSON. */
    public class Value(public val json: JsonElement) : ToolResult

    /** An image, with its media type. */
    public class Image(public val bytes: ByteArray, public val mediaType: String) : ToolResult
}


/** A tool of a model: its name, what it does and the schema of its arguments. */
public class AgentTool internal constructor(
    public val name: String,
    public val description: String,
    public val inputSchema: JsonObject,
    internal val run: suspend (arguments: JsonObject, connection: String) -> ToolResult
)

/**
 * What a model can read of the documents and propose for them, whoever asks (Information Expert):
 * the command line, a client of the Model Context Protocol, later the panel of the application.
 * It is the registry of the tools, each one made by its group: [ReadingTools], [ProposalTools],
 * [LibraryTools] (`docs/plans/document-tools.md`).
 *
 * Every tool checks the access by itself; nothing here can reach the trash, the sharing, the keys or
 * the network. The content of a document is marked as data in what is answered: it may hold
 * instructions addressed to the model (see "Threats" in `docs/plans/agent-tools.md`).
 *
 * @param submit gives a proposal to the application, which may apply it at once unless its client asked
 *   for a review (see [ExternalAgents]).
 * @param ready called before a tool runs: what was kept of the proposals and the activity is back.
 */
public class DocumentTools internal constructor(
    private val documents: OpenDocuments,
    private val access: AgentAccess,
    private val proposals: ProposalBook,
    private val engines: AgentEngines,
    private val submit: suspend (Proposal, review: Boolean) -> Unit,
    private val limits: ProposalLimits = ProposalLimits(),
    private val library: LibraryBook = LibraryBook(),
    private val submitLibrary: suspend (LibraryProposal, review: Boolean) -> Unit = { _, _ -> },
    private val trashLimiter: TrashLimiter = TrashLimiter(),
    private val activity: AgentActivity = AgentActivity(),
    private val undo: suspend (batchId: String) -> UndoOutcome = { UndoOutcome.GONE },
    private val ready: suspend () -> Unit = {}
) {
    private val planner = ProposalPlanner(engines)
    private val reading = ReadingTools(documents, access, engines, planner)
    private val proposing = ProposalTools(documents, access, engines, planner, proposals, submit, limits, library, activity, undo)
    private val libraryTools = LibraryTools(documents, access, engines, planner, limits, library, submitLibrary, trashLimiter)
    private val exportTools = ExportTools(documents, access, engines, planner)

    /** The tools, those the application gives what they need for, in the order the models see them. */
    public val tools: List<AgentTool> = buildList {
        add(reading.listDocuments())
        add(reading.readDocument())
        add(reading.readPage())
        add(reading.readPages())
        add(reading.readStroke())
        add(reading.listRemovedPages())
        if (engines.images != null) add(reading.readImage())
        if (engines.renderPage != null || engines.pages != null) add(reading.renderPage())
        if (engines.search != null) add(reading.search())
        add(proposing.previewProposal())
        add(proposing.propose())
        add(proposing.proposalStatus())
        add(libraryTools.listFolders())
        add(libraryTools.listTrash())
        add(libraryTools.proposeLibrary())
        add(proposing.listActivity())
        add(proposing.undoBatch())
        if (engines.exports != null) add(exportTools.export())
        if (engines.show != null) add(exportTools.showPage())
        add(reading.readGuide())
    }

    /** Calls the tool [name] for the client [connection]; fails with an [AgentToolException]. */
    public suspend fun call(name: String, arguments: JsonObject, connection: String): ToolResult {
        val tool = tools.firstOrNull { it.name == name }
            ?: throw AgentToolException(AgentErrorCode.BAD_REQUEST, "There is no tool named \"${name.take(64)}\".")
        if (!access.grants.value.enabled) throw AgentToolException(AgentErrorCode.ACCESS_OFF, "The access of external models is turned off in the application.")
        ready()
        return tool.run(arguments, connection)
    }
}
