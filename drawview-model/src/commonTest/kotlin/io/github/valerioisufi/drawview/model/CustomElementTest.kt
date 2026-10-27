package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.BoxPlacement
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.model.change.CustomElementData
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.DocumentSnapshot
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.model.change.UpdateElementContent
import io.github.valerioisufi.drawview.model.change.snapshot
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The elements of the kinds added by the applications: their box is the model's, their value
 * the type's, kept as bytes by the devices that do not know it.
 */
class CustomElementTest {

    @Serializable
    data class Shape(val sides: Int, val color: Int)

    /** A type of the application, its value in its second format. */
    object ShapeType : ElementType<Shape> {
        override val id = "com.example.shape"
        override val version = 2
        override val serializer = Shape.serializer()

        @Serializable
        data class ShapeV1(val sides: Int)

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        override fun migrate(payload: ByteArray, fromVersion: Int): Shape? =
            if (fromVersion == 1) kotlinx.serialization.cbor.Cbor.decodeFromByteArray(ShapeV1.serializer(), payload).let { Shape(it.sides, BLACK) }
            else null
    }

    private fun shape(value: Shape, x: Float = 10f): DrawCustomElement = DrawCustomElement(ShapeType.id).apply {
        version = ShapeType.version
        payload = ShapeType.encode(value)
        this.x = x
        y = 20f
        width = 30f
        height = 30f
    }

    @Test
    fun anElementOfAnApplicationIsAddedChangedAndUndone() {
        val document = DrawDocument()
        val session = DocumentSession(document)
        val page = document.pages[0]
        val element = shape(Shape(3, RED))

        session.submit(AddElements.of(page, listOf(element)))
        assertSame(element, page.find(element.id))
        assertEquals(Rect(10f, 20f, 40f, 50f), page.boundsOf(element))

        val undoChange = assertNotNull(
            session.submit(UpdateElementContent(page.id, element.id, ShapeType.version, ShapeType.encode(Shape(5, RED))))
        )
        assertEquals(Shape(5, RED), ShapeType.decode(element))

        // Moved by its box, like an image
        session.submit(PlaceElements(page.id, listOf(BoxPlacement(element.id, 50f, 60f, 30f, 30f, 45f))))
        assertEquals(50f, element.x)
        assertEquals(45f, element.rotation)

        session.submit(undoChange, ChangeOrigin.UNDO)
        assertEquals(Shape(3, RED), ShapeType.decode(element))
    }

    @Test
    fun theMostRecentValueWinsOnEveryDevice() {
        val first = DrawDocument()
        val second = DrawDocument(id = first.id, addInitialPage = false).apply { appendPage(first.pages[0].toData().toPage(this)) }
        val a = DocumentSession(first, replica = "a")
        val b = DocumentSession(second, replica = "b")
        val operations = mutableListOf<Operation>()
        a.addObserver { operations.add(it.operation) }
        b.addObserver { if (it.origin != ChangeOrigin.REMOTE) operations.add(it.operation) }

        val element = shape(Shape(3, RED))
        a.submit(AddElements.of(first.pages[0], listOf(element)))
        b.receive(operations.removeAt(0))

        // Changed on both devices without seeing each other's change
        a.submit(UpdateElementContent(first.pages[0].id, element.id, ShapeType.version, ShapeType.encode(Shape(4, RED))))
        b.submit(UpdateElementContent(second.pages[0].id, element.id, ShapeType.version, ShapeType.encode(Shape(6, BLUE))))
        val (fromA, fromB) = operations.partition { it.id.replica == "a" }
        fromB.forEach { a.receive(it) }
        fromA.forEach { b.receive(it) }

        val onA = first.pages[0].customElements.single()
        val onB = second.pages[0].customElements.single()
        assertContentEquals(onA.payload, onB.payload)
        assertEquals(ShapeType.decode(onA), ShapeType.decode(onB))
    }

    @Test
    fun anElementOfAnUnknownTypeIsKeptAsItIs() {
        val document = DrawDocument()
        val session = DocumentSession(document)
        val payload = byteArrayOf(1, 2, 3, 4)
        val element = DrawCustomElement("org.other.unknown").apply {
            version = 7
            this.payload = payload
            width = 10f
            height = 10f
        }
        session.submit(AddElements.of(document.pages[0], listOf(element)))

        // Sent to another device (as the snapshot of the document) and back
        val json = Json.encodeToString(DocumentSnapshot.serializer(), document.snapshot())
        val received = Json.decodeFromString(DocumentSnapshot.serializer(), json).toDocument()
        val kept = received.pages[0].customElements.single()
        assertEquals("org.other.unknown", kept.type)
        assertEquals(7, kept.version)
        assertContentEquals(payload, kept.payload)
        // Not readable as a type it is not
        assertNull(ShapeType.decode(kept))
    }

    @Test
    fun theChangesOfTheElementsTravelWithTheirBytes() {
        val element = shape(Shape(8, BLUE))
        val change: DocumentChange = AddElements("page", listOf(element.toData()))
        val json = Json.encodeToString(DocumentChange.serializer(), change)
        val read = Json.decodeFromString(DocumentChange.serializer(), json) as AddElements
        val data = read.elements.single() as CustomElementData
        assertEquals(element.toData(), data)
        assertEquals(Shape(8, BLUE), ShapeType.decode(data.toElement()))
    }

    @Test
    fun aValueOfAnOlderFormatIsMigratedAndANewerOneIsNotRead() {
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val older = DrawCustomElement(ShapeType.id).apply {
            version = 1
            payload = kotlinx.serialization.cbor.Cbor.encodeToByteArray(ShapeType.ShapeV1.serializer(), ShapeType.ShapeV1(4))
        }
        assertEquals(Shape(4, BLACK), ShapeType.decode(older))

        val newer = shape(Shape(4, RED)).apply { version = 3 }
        assertNull(ShapeType.decode(newer))
    }

    @Test
    fun aTypeMayGiveTheTextOfItsElementsForTheSearch() {
        val withoutText = shape(Shape(5, RED))
        assertNull(ShapeType.searchTextOf(withoutText))

        val namedType = object : ElementType<Shape> {
            override val id = ShapeType.id
            override val version = ShapeType.version
            override val serializer = Shape.serializer()
            override fun searchText(value: Shape): String = "polygon of ${value.sides} sides"
        }
        assertEquals("polygon of 5 sides", namedType.searchTextOf(withoutText))
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
