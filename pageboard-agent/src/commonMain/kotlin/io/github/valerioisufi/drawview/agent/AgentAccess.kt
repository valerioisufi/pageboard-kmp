package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.PreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the user allows.
 *
 * @param enabled whether clients are let in at all.
 * @param documents the documents they may reach one by one, kept while [allDocuments] is on.
 * @param allDocuments whether they reach every document, the ones made later too.
 * @param applyDirectly whether what they propose is applied when it arrives, without a review.
 */
@Serializable
public data class AgentGrants(
    val enabled: Boolean = false,
    val documents: Set<String> = emptySet(),
    val allDocuments: Boolean = false,
    val applyDirectly: Boolean = false
) {
    /** Whether a client may reach the document [documentId]. */
    public fun allows(documentId: String): Boolean = enabled && (allDocuments || documentId in documents)
}

/**
 * A client connected to the application.
 *
 * @param name the name it gave itself: for the user to read, not proof of anything.
 */
public data class AgentConnection(
    val id: String,
    val name: String
)

/**
 * Who may reach which document (Information Expert): the grants of the user, kept in [preferences]
 * under a key of their own, and the clients connected, kept in memory. Without the key (or with a
 * value that cannot be read) nothing is allowed: the access starts off.
 */
public class AgentAccess(private val preferences: PreferenceStore? = null) {
    private val mutex = Mutex()
    private val grantsState = MutableStateFlow(AgentGrants())
    private val connectionsState = MutableStateFlow<List<AgentConnection>>(emptyList())

    public val grants: StateFlow<AgentGrants> = grantsState.asStateFlow()
    public val connections: StateFlow<List<AgentConnection>> = connectionsState.asStateFlow()

    /** Reads the grants kept. */
    public suspend fun load() {
        val kept = preferences?.preference(PREFERENCE_KEY) ?: return
        grantsState.value = try {
            json.decodeFromString(AgentGrants.serializer(), kept)
        } catch (_: SerializationException) {
            AgentGrants()
        } catch (_: IllegalArgumentException) {
            AgentGrants()
        }
    }

    public suspend fun setEnabled(enabled: Boolean): Unit = change { it.copy(enabled = enabled) }

    public suspend fun grant(documentId: String): Unit = change { it.copy(documents = it.documents + documentId) }

    public suspend fun revoke(documentId: String): Unit = change { it.copy(documents = it.documents - documentId) }

    /** Lets the clients reach every document, or only the ones granted one by one. */
    public suspend fun setAllDocuments(all: Boolean): Unit = change { it.copy(allDocuments = all) }

    /** Whether what the clients propose is applied when it arrives, or waits for the review of the user. */
    public suspend fun setApplyDirectly(direct: Boolean): Unit = change { it.copy(applyDirectly = direct) }

    /** Whether a client may reach the document [documentId] now. */
    public fun allows(documentId: String): Boolean = grantsState.value.allows(documentId)

    /** A new client named [name]; followed by a [disconnect]. */
    public fun connect(name: String): AgentConnection {
        val connection = AgentConnection(newElementId(), name.take(MAX_NAME_LENGTH))
        connectionsState.update { it + connection }
        return connection
    }

    public fun disconnect(id: String) {
        connectionsState.update { current -> current.filterNot { it.id == id } }
    }

    /** Names the client [id] (it says its name after connecting). */
    public fun rename(id: String, name: String) {
        connectionsState.update { current -> current.map { if (it.id == id) it.copy(name = name.take(MAX_NAME_LENGTH)) else it } }
    }

    private suspend fun change(transform: (AgentGrants) -> AgentGrants): Unit = mutex.withLock {
        val changed = transform(grantsState.value)
        grantsState.value = changed
        preferences?.setPreference(PREFERENCE_KEY, json.encodeToString(AgentGrants.serializer(), changed))
    }

    public companion object {
        /** The preference holding the grants. */
        public const val PREFERENCE_KEY: String = "agent.grants"

        private const val MAX_NAME_LENGTH = 64
        private val json = Json { ignoreUnknownKeys = true }
    }
}
