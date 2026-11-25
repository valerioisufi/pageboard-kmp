package io.github.valerioisufi.pageboard.model.shape

import io.github.valerioisufi.pageboard.model.CustomElement
import io.github.valerioisufi.pageboard.model.decode
import io.github.valerioisufi.pageboard.model.encode
import io.github.valerioisufi.pageboard.model.geometry.Point
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The stored format of the shapes (version 1 and version 2 of `pageboard.shape`): the payloads
 * recorded here are in documents and travel between devices; v1 payloads migrate cleanly to v2.
 */
@OptIn(ExperimentalStdlibApi::class)
class ShapeElementFormatTest {
    private val samples = listOf(
        ShapeElement(ShapeKind.LINE, 0xFF1E88E5.toInt(), 0.5f, endHead = ShapeHead.OPEN),
        ShapeElement(ShapeKind.LINE, 0xFF000000.toInt(), 1f, ShapeDash.DASHED, startHead = ShapeHead.FILLED, endHead = ShapeHead.FILLED),
        ShapeElement(ShapeKind.RECTANGLE, 0xFF000000.toInt(), 0.35f),
        ShapeElement(ShapeKind.ROUNDED_RECTANGLE, 0xFFE53935.toInt(), 0.7f, fill = 0x33E53935),
        ShapeElement(ShapeKind.ELLIPSE, 0xFF43A047.toInt(), 0.35f, ShapeDash.DOTTED),
        ShapeElement(ShapeKind.TRIANGLE, 0xFF000000.toInt(), 0.35f, fill = 0xFFFFEB3B.toInt()),
        ShapeElement(ShapeKind.RIGHT_TRIANGLE, 0xFF000000.toInt(), 0.35f),
        ShapeElement(ShapeKind.DIAMOND, 0xFF000000.toInt(), 0.35f),
        ShapeElement(ShapeKind.PENTAGON, 0xFF000000.toInt(), 0.35f),
        ShapeElement(ShapeKind.HEXAGON, 0xFF000000.toInt(), 0.35f),
    )

    private val recorded = listOf(
        RECORDED_0, RECORDED_1, RECORDED_2, RECORDED_3, RECORDED_4,
        RECORDED_5, RECORDED_6, RECORDED_7, RECORDED_8, RECORDED_9,
    )

    private val v2Samples = listOf(
        ShapeElement(ShapeKind.RECTANGLE, 0xFF1E88E5.toInt(), 0.5f, label = "Node A", labelColor = 0xFF000000.toInt(), labelFontSizePt = 14f),
        ShapeElement(ShapeKind.LINE, 0xFF000000.toInt(), 0.5f, endHead = ShapeHead.FILLED, startAnchor = ShapeAnchorRef("box-1", 2), endAnchor = ShapeAnchorRef("box-2", 0)),
        ShapeElement(ShapeKind.POLYLINE, 0xFF000000.toInt(), 0.5f, endHead = ShapeHead.OPEN, vertices = listOf(Point(0f, 1f), Point(0.5f, 0f), Point(1f, 1f))),
        ShapeElement(ShapeKind.FREE_POLYGON, 0xFF43A047.toInt(), 0.5f, fill = 0x3343A047, vertices = listOf(Point(0f, 0f), Point(1f, 0.2f), Point(0.8f, 1f), Point(0.1f, 0.9f))),
        ShapeElement(ShapeKind.AXES, 0xFF000000.toInt(), 0.5f, geometry = ShapeGeometryParams(ticksX = 4, ticksY = 3)),
        ShapeElement(ShapeKind.GRID, 0xFF000000.toInt(), 0.35f, geometry = ShapeGeometryParams(gridCols = 5, gridRows = 3)),
        ShapeElement(ShapeKind.ARC, 0xFFE53935.toInt(), 0.5f, geometry = ShapeGeometryParams(startAngleDeg = 15f, sweepAngleDeg = 135f)),
        ShapeElement(ShapeKind.ANGLE, 0xFF000000.toInt(), 0.5f, geometry = ShapeGeometryParams(startAngleDeg = 0f, sweepAngleDeg = 45f, showAngleLabel = true))
    )

    private fun element(payload: ByteArray, version: Int = ShapeElementType.version) = CustomElement(ShapeElementType.id).also {
        it.payload = payload
        it.version = version
    }

    @Test
    fun theFormatIsTheOneRecorded() {
        samples.forEachIndexed { i, sample ->
            assertEquals(recorded[i], ShapeElementType.encode(sample).toHexString(), "sample $i")
        }
    }

    @Test
    fun thePayloadsRecordedAreReadAndMigratedFromV1() {
        samples.forEachIndexed { i, sample ->
            assertEquals(sample, ShapeElementType.decode(element(recorded[i].hexToByteArray(), version = 1)), "v1 migrate $i")
            assertEquals(sample, ShapeElementType.decode(element(recorded[i].hexToByteArray(), version = 2)), "v2 decode $i")
        }
        v2Samples.forEachIndexed { i, sample ->
            val encoded = ShapeElementType.encode(sample)
            assertEquals(sample, ShapeElementType.decode(element(encoded, version = 2)), "v2 roundtrip $i")
        }
    }

    @Test
    fun aValueOutOfItsBoundsIsNotRead() {
        val wide = ShapeElementType.encode(ShapeElement(ShapeKind.RECTANGLE, 0, 20f))
        // The width 20 mm is the widest; 40 mm in its place is too wide
        assertEquals(20f, ShapeElementType.decode(element(wide))?.widthMm)
        val tooWide = ShapeElementType.encode(ShapeElement(ShapeKind.RECTANGLE, 0, 20f)).toHexString()
            .replace(20f.toBits().toUInt().toString(16), 40f.toBits().toUInt().toString(16))
        assertNull(ShapeElementType.decode(element(tooWide.hexToByteArray())))
        // An unknown kind (written by a later version) is not read
        val unknown = RECORDED_2.replace("RECTANGLE".encodeToByteArray().toHexString(), "RECTANGLX".encodeToByteArray().toHexString())
        assertNull(ShapeElementType.decode(element(unknown.hexToByteArray())))
        // A newer version is not read
        assertNull(ShapeElementType.decode(element(RECORDED_2.hexToByteArray(), version = 3)))
        // A v2 kind tagged as version 1 is rejected by v1 migration
        val v2Only = ShapeElementType.encode(ShapeElement(ShapeKind.AXES, 0, 0.5f))
        assertNotNull(ShapeElementType.decode(element(v2Only, version = 2)))
        assertNull(ShapeElementType.decode(element(v2Only, version = 1)))
    }

    @Test
    fun randomBytesAreNoShapeAndNeverFail() {
        val random = Random(7)
        repeat(5000) {
            val bytes = if (it % 2 == 0) {
                random.nextBytes(random.nextInt(0, 64))
            } else {
                // A recorded payload with a few bytes changed
                recorded[it % recorded.size].hexToByteArray().also { b -> repeat(3) { b[random.nextInt(b.size)] = random.nextInt().toByte() } }
            }
            val value = ShapeElementType.decode(element(bytes))
            if (value != null) {
                // What is read holds the checks
                ShapeElement(
                    value.kind, value.stroke, value.widthMm, value.dash, value.fill, value.startHead, value.endHead,
                    value.label, value.labelColor, value.labelFontSizePt, value.vertices, value.startAnchor, value.endAnchor, value.geometry
                )
            }
        }
    }

    @Test
    fun shapeDraftActivityValidatesBoundsAndPageId() {
        val shape = ShapeElement(ShapeKind.RECTANGLE, 0xFF000000.toInt(), 0.5f, label = "Draft")
        val valid = ShapeDraftActivity("page-1", shape, 10f, 20f, 30f, 40f, 0f)
        kotlin.test.assertTrue(valid.isValid())
        kotlin.test.assertFalse(valid.copy(pageId = " ").isValid())
        kotlin.test.assertFalse(valid.copy(width = 0f).isValid())
        kotlin.test.assertFalse(valid.copy(height = Float.NaN).isValid())
        kotlin.test.assertFalse(valid.copy(x = 200_000f).isValid())
    }

    private companion object {
        const val RECORDED_0 = "bf646b696e64644c494e45667374726f6b653a00e1771a6777696474684d6dfa3f00000067656e6448656164644f50454eff"
        const val RECORDED_1 = "bf646b696e64644c494e45667374726f6b653a00ffffff6777696474684d6dfa3f800000646461736866444153484544697374617274486561646646494c4c454467656e64486561646646494c4c4544ff"
        const val RECORDED_2 = "bf646b696e646952454354414e474c45667374726f6b653a00ffffff6777696474684d6dfa3eb33333ff"
        const val RECORDED_3 = "bf646b696e6471524f554e4445445f52454354414e474c45667374726f6b653a001ac6ca6777696474684d6dfa3f3333336466696c6c1a33e53935ff"
        const val RECORDED_4 = "bf646b696e6467454c4c49505345667374726f6b653a00bc5fb86777696474684d6dfa3eb33333646461736866444f54544544ff"
        const val RECORDED_5 = "bf646b696e6468545249414e474c45667374726f6b653a00ffffff6777696474684d6dfa3eb333336466696c6c3914c4ff"
        const val RECORDED_6 = "bf646b696e646e52494748545f545249414e474c45667374726f6b653a00ffffff6777696474684d6dfa3eb33333ff"
        const val RECORDED_7 = "bf646b696e64674449414d4f4e44667374726f6b653a00ffffff6777696474684d6dfa3eb33333ff"
        const val RECORDED_8 = "bf646b696e646850454e5441474f4e667374726f6b653a00ffffff6777696474684d6dfa3eb33333ff"
        const val RECORDED_9 = "bf646b696e646748455841474f4e667374726f6b653a00ffffff6777696474684d6dfa3eb33333ff"
    }
}
