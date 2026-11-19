package io.github.valerioisufi.pageboard.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Who works on a shared document, read by a model (`docs/plans/agent-coverage.md`, phase 8). */
class CoverageSharingTest {

    private val members = AgentSharing(
        inSharedFolder = false,
        members = listOf(
            AgentMember("Mac of Valerio", "owner", isThisDevice = true, isOnline = true),
            AgentMember("Tablet", "editor", isThisDevice = false, isOnline = false)
        )
    )

    @Test
    fun theMembersOfASharedDocumentAreReadWithTheirRoles() = runTest {
        val asked = mutableListOf<String>()
        val harness = AgentHarness.of(this, backgroundScope, AgentEngines(sharing = { id -> asked += id; members }))
        harness.store.setSharingSecret(harness.documentId, "a secret")
        val read = harness.call("read_document", buildJsonObject { put("document", harness.documentId) })
        assertTrue(read.getValue("is_shared").jsonPrimitive.boolean)
        val listed = read.getValue("sharing").jsonObject.getValue("members").jsonArray.map { it.jsonObject }
        assertEquals(listOf("owner", "editor"), listed.map { it.getValue("role").jsonPrimitive.content })
        assertTrue(listed[0].getValue("this_device").jsonPrimitive.boolean)
        assertFalse("online" in listed[1])
        // Nothing of the keys of the sharing
        assertFalse(read.toString().contains("a secret"))
        assertEquals(listOf(harness.documentId), asked)
    }

    @Test
    fun aDocumentNotSharedHasNoMembers() = runTest {
        val asked = mutableListOf<String>()
        val harness = AgentHarness.of(this, backgroundScope, AgentEngines(sharing = { id -> asked += id; members }))
        val read = harness.call("read_document", buildJsonObject { put("document", harness.documentId) })
        assertTrue("sharing" !in read && "is_shared" !in read)
        assertTrue(asked.isEmpty())
    }
}
