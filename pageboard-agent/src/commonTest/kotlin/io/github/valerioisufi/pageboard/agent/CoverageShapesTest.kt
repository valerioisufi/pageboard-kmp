package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.agent.AgentHarness.Companion.item
import io.github.valerioisufi.pageboard.model.decode
import io.github.valerioisufi.pageboard.model.shape.ShapeDash
import io.github.valerioisufi.pageboard.model.shape.ShapeElementType
import io.github.valerioisufi.pageboard.model.shape.ShapeHead
import io.github.valerioisufi.pageboard.model.shape.ShapeKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shapes of the shapes tool drawn and changed by a model (`docs/plans/agent-coverage.md`, phase 3). */
class CoverageShapesTest {

    private fun JsonObjectBuilder.point(name: String, x: Float, y: Float) = put(name, JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))))

    private fun AgentHarness.shapes() = page.customElements.filter { it.removed == null }

    private suspend fun AgentHarness.readShape(id: String): JsonObject =
        readPage().getValue("custom_elements").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }.getValue("shape").jsonObject

    private fun JsonObject.point(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.float }

    @Test
    fun everyKindOfABoxIsDrawn() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val kinds = ShapeItems.KINDS.filterValues { !it.isLine }.keys
        val answer = harness.propose(*kinds.mapIndexed { index, kind ->
            item("add_shape") {
                put("page", harness.page.id); put("shape_kind", kind)
                put("x", 10f + (index % 3) * 60f); put("y", 10f + (index / 3) * 40f); put("width", 40f); put("height", 30f)
            }
        }.toTypedArray())
        assertEquals(kinds.map { "accepted" }, harness.outcomes(answer))
        val drawn = harness.shapes().map { assertNotNull(ShapeElementType.decode(it)).kind }
        assertEquals(kinds.map { ShapeItems.KINDS.getValue(it) }.toSet(), drawn.toSet())
        assertTrue(drawn.all { !it.isLine })
    }

    @Test
    fun aShapeOfABoxIsDrawnWithItsStyleWhereAsked() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        harness.propose(
            item("add_shape") {
                put("page", harness.page.id); put("shape_kind", "ellipse"); put("x", 20f); put("y", 30f); put("width", 60f); put("height", 40f)
                put("rotation", 15f); put("color", "#C0392B"); put("fill", "#FFEB3B80"); put("width_mm", 1.2f); put("outline", "dashed")
            }
        )
        val element = harness.shapes().single()
        val value = assertNotNull(ShapeElementType.decode(element))
        assertEquals(ShapeKind.ELLIPSE, value.kind)
        assertEquals(0xFFC0392B.toInt(), value.stroke)
        assertEquals(0x80FFEB3B.toInt(), value.fill)
        assertEquals(ShapeDash.DASHED, value.dash)
        assertEquals(1.2f, value.widthMm)
        assertEquals(20f, element.x)
        assertEquals(60f, element.width)
        assertEquals(15f, element.rotation)
        val read = harness.readShape(element.id)
        assertEquals("ellipse", read.getValue("kind").jsonPrimitive.content)
        assertEquals("dashed", read.getValue("outline").jsonPrimitive.content)
    }

    @Test
    fun anArrowIsDrawnFromItsTailToItsHeadAndReadSo() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        harness.propose(item("add_shape") { put("page", harness.page.id); put("shape_kind", "arrow"); point("from", 100f, 100f); point("to", 40f, 60f) })
        val element = harness.shapes().single()
        val value = assertNotNull(ShapeElementType.decode(element))
        assertEquals(ShapeKind.LINE, value.kind)
        assertEquals(ShapeHead.FILLED, value.endHead)
        val read = harness.readShape(element.id)
        assertEquals("arrow", read.getValue("kind").jsonPrimitive.content)
        assertEquals(listOf(100f, 100f), read.point("from"))
        assertEquals(listOf(40f, 60f), read.point("to"))
        assertEquals("filled", read.getValue("end_head").jsonPrimitive.content)
        assertNull(read["start_head"])
    }

    @Test
    fun theEndsOfALineAreMovedAndItsHeadsChanged() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        harness.propose(item("add_shape") { put("page", harness.page.id); put("shape_kind", "line"); point("from", 20f, 20f); point("to", 80f, 20f) })
        val element = harness.shapes().single()
        val other = harness.converging {
            harness.propose(
                item("update_shape") {
                    put("shape", element.id); put("base", harness.bases().getValue(element.id))
                    put("shape_kind", "double_arrow"); point("to", 80f, 90f); put("color", "#2E7D32")
                }
            )
        }
        val read = harness.readShape(element.id)
        assertEquals("double_arrow", read.getValue("kind").jsonPrimitive.content)
        assertEquals(listOf(20f, 20f), read.point("from"))
        assertEquals(listOf(80f, 90f), read.point("to"))
        assertEquals("#2E7D32", read.getValue("color").jsonPrimitive.content)
        val there = other.document.pages[0].customElements.single()
        assertEquals(ShapeElementType.decode(element), ShapeElementType.decode(there))
        assertEquals(element.rotation, there.rotation)

        assertEquals(UndoOutcome.UNDONE, harness.undoLast())
        val back = harness.readShape(element.id)
        assertEquals("line", back.getValue("kind").jsonPrimitive.content)
        assertEquals(listOf(80f, 20f), back.point("to"))
    }

    @Test
    fun theStyleOfAShapeOfABoxChangesItsKindStaysOfItsFamily() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        harness.propose(item("add_shape") { put("page", harness.page.id); put("shape_kind", "rectangle"); put("x", 20f); put("y", 30f); put("width", 60f); put("height", 40f); put("fill", "#FFFFFF") })
        val element = harness.shapes().single()
        val base = harness.bases().getValue(element.id)
        harness.propose(item("update_shape") { put("shape", element.id); put("base", base); put("shape_kind", "diamond"); put("fill", "none"); put("outline", "dotted") })
        val value = assertNotNull(ShapeElementType.decode(element))
        assertEquals(ShapeKind.DIAMOND, value.kind)
        assertNull(value.fill)
        assertEquals(ShapeDash.DOTTED, value.dash)
        assertEquals(20f, element.x)

        val toLine = harness.refused(item("update_shape") { put("shape", element.id); put("base", harness.bases().getValue(element.id)); put("shape_kind", "arrow") })
        assertEquals(AgentErrorCode.REFUSED, toLine.code)
        val ends = harness.refused(item("update_shape") { put("shape", element.id); put("base", harness.bases().getValue(element.id)); point("to", 10f, 10f) })
        assertEquals(AgentErrorCode.REFUSED, ends.code)
    }

    @Test
    fun aShapeChangedSinceItWasReadIsStale() = runTest {
        val harness = AgentHarness.of(this, backgroundScope, direct = false)
        harness.agents.access.setApplyDirectly(true)
        harness.propose(item("add_shape") { put("page", harness.page.id); put("shape_kind", "rectangle"); put("x", 20f); put("y", 30f); put("width", 60f); put("height", 40f) })
        val element = harness.shapes().single()
        val base = harness.bases().getValue(element.id)
        harness.propose(item("place_elements") { put("page", harness.page.id); put("elements", harness.refs(element.id)); put("dx", 10f) })
        val answer = harness.propose(item("update_shape") { put("shape", element.id); put("base", base); put("color", "#000000") })
        assertEquals(listOf("stale"), harness.outcomes(answer))
        assertEquals(AGENT_TEXT_COLOR, assertNotNull(ShapeElementType.decode(element)).stroke)
    }

    @Test
    fun wrongShapesAreRefused() = runTest {
        val harness = AgentHarness.of(this, backgroundScope)
        val page = harness.page.id
        val cases = listOf(
            item("add_shape") { put("page", page); put("shape_kind", "star"); put("x", 1f); put("y", 1f); put("width", 10f); put("height", 10f) },
            item("add_shape") { put("page", page); put("shape_kind", "rectangle"); put("x", 1f); put("y", 1f) },
            item("add_shape") { put("page", page); put("shape_kind", "line"); point("from", 10f, 10f) },
            item("add_shape") { put("page", page); put("shape_kind", "line"); point("from", 10f, 10f); point("to", 10.2f, 10.2f) },
            item("add_shape") { put("page", page); put("shape_kind", "line"); point("from", 10f, 10f); point("to", 300f, 10f) },
            item("add_shape") { put("page", page); put("shape_kind", "line"); point("from", 10f, 10f); point("to", 50f, 10f); put("fill", "#FFFFFF") },
            item("add_shape") { put("page", page); put("shape_kind", "ellipse"); put("x", 190f); put("y", 10f); put("width", 40f); put("height", 10f) },
            item("add_shape") { put("page", page); put("shape_kind", "ellipse"); put("x", 10f); put("y", 10f); put("width", 40f); put("height", 10f); put("width_mm", 50f) },
            item("add_shape") { put("page", page); put("shape_kind", "ellipse"); put("x", 10f); put("y", 10f); put("width", 40f); put("height", 10f); put("start_head", "open") }
        )
        for (case in cases) assertEquals(AgentErrorCode.REFUSED, harness.refused(case).code, "$case")
        assertTrue(harness.shapes().isEmpty())
    }
}
