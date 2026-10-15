package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.random.Random

/**
 * A renderable chunk of a stroke mesh whose vertices can be addressed with 16-bit indices
 * (Skia and Android `drawVertices` only accept `short` index buffers).
 */
public class MeshPartition(
    public val positions: FloatArray,
    public val indices: ShortArray
) {
    public val vertexCount: Int get() = positions.size / 2
    public val triangleCount: Int get() = indices.size / 3
}

/**
 * Triangulated geometry of a stroke, expressed in stroke units (page millimeters for committed
 * strokes), together with the inputs that generated it.
 *
 * Meshes with more than 65535 vertices are split in several [partitions]: [positions] and
 * [indices] always describe the first one.
 *
 * @property outline The shape of the stroke as closed polygons, from the same modeling as the
 * triangles: the strokes are drawn by filling it, antialiased. `null` when the modeller cannot
 * give it (the Kotlin one): the triangles are drawn instead.
 */
public class StrokeMesh(
    public val id: String = generateId(),
    public val positions: FloatArray,
    public val indices: ShortArray,
    public val color: Int,
    public val tool: StrokeTool,
    public val size: Float,
    public val points: List<StrokePoint> = emptyList(),
    additionalPartitions: List<MeshPartition> = emptyList(),
    public val inputToolType: InputToolType = InputToolType.UNKNOWN,
    public val outline: StrokeOutline? = null
) {
    public val partitions: List<MeshPartition> =
        if (additionalPartitions.isEmpty()) listOf(MeshPartition(positions, indices))
        else listOf(MeshPartition(positions, indices)) + additionalPartitions

    public val vertexCount: Int get() = partitions.sumOf { it.vertexCount }
    public val triangleCount: Int get() = partitions.sumOf { it.triangleCount }

    public val isEmpty: Boolean get() = partitions.all { it.positions.isEmpty() || it.indices.isEmpty() }

    /**
     * Axis-aligned bounds of every vertex of the mesh, or [Rect.Zero] for an empty mesh.
     */
    public fun computeBounds(): Rect {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (partition in partitions) {
            val p = partition.positions
            var i = 0
            while (i + 1 < p.size) {
                val x = p[i]
                val y = p[i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
        }
        return if (minX <= maxX && minY <= maxY) Rect(minX, minY, maxX, maxY) else Rect.Zero
    }

    /** This mesh with the recorded inputs [points]. */
    public fun withPoints(points: List<StrokePoint>): StrokeMesh = StrokeMesh(
        positions = positions,
        indices = indices,
        color = color,
        tool = tool,
        size = size,
        points = points,
        additionalPartitions = partitions.drop(1),
        inputToolType = inputToolType,
        outline = outline
    )

    /**
     * This mesh mapped by [transform]: the vertices are mapped directly (what is drawn is exactly
     * the transformed mesh) and so are the outline and the recorded inputs, the size scaled
     * accordingly.
     */
    public fun transformed(transform: Matrix2D): StrokeMesh {
        val scale = transform.scaleFactor
        val mapped = partitions.map { partition ->
            val positions = partition.positions.copyOf()
            transform.mapPoints(positions)
            MeshPartition(positions, partition.indices)
        }
        return StrokeMesh(
            positions = mapped.first().positions,
            indices = mapped.first().indices,
            color = color,
            tool = tool,
            size = size * scale,
            points = points.map { p ->
                val point = transform.mapPoint(p.x, p.y)
                StrokePoint(point.x, point.y, p.pressure, p.timestampMs)
            },
            additionalPartitions = mapped.drop(1),
            inputToolType = inputToolType,
            outline = outline?.transformed(transform)
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StrokeMesh) return false

        if (id != other.id) return false
        if (!positions.contentEquals(other.positions)) return false
        if (!indices.contentEquals(other.indices)) return false
        if (color != other.color) return false
        if (tool != other.tool) return false
        if (size != other.size) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + positions.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + tool.hashCode()
        result = 31 * result + size.hashCode()
        return result
    }

    public companion object {
        private const val MAX_PARTITION_VERTICES = 65535

        private fun generateId(): String =
            "stroke_${Random.nextLong(100_000_000, 999_999_999)}"

        /**
         * Builds a [StrokeMesh] from a 32-bit indexed triangle list, splitting it into
         * 16-bit addressable [MeshPartition]s when needed, with the [outline] of the stroke.
         */
        public fun fromIndexedMesh(
            positions: FloatArray,
            indices: IntArray,
            color: Int,
            tool: StrokeTool,
            size: Float,
            points: List<StrokePoint>,
            inputToolType: InputToolType,
            outline: StrokeOutline? = null
        ): StrokeMesh {
            val vertexCount = positions.size / 2
            if (vertexCount <= MAX_PARTITION_VERTICES) {
                val shortIndices = ShortArray(indices.size) { indices[it].toShort() }
                return StrokeMesh(
                    positions = positions,
                    indices = shortIndices,
                    color = color,
                    tool = tool,
                    size = size,
                    points = points,
                    inputToolType = inputToolType,
                    outline = outline
                )
            }

            val partitions = partition(positions, indices)
            val first = partitions.first()
            return StrokeMesh(
                positions = first.positions,
                indices = first.indices,
                color = color,
                tool = tool,
                size = size,
                points = points,
                additionalPartitions = partitions.drop(1),
                inputToolType = inputToolType,
                outline = outline
            )
        }

        /**
         * Greedily splits a triangle list in chunks referencing at most 65535 distinct vertices.
         * Ink emits triangles in stroke order, so consecutive triangles share vertices and the
         * duplication introduced at chunk borders is negligible.
         */
        private fun partition(positions: FloatArray, indices: IntArray): List<MeshPartition> {
            val result = mutableListOf<MeshPartition>()
            val remap = HashMap<Int, Int>()
            val chunkPositions = ArrayList<Float>()
            val chunkIndices = ArrayList<Short>()

            fun flush() {
                if (chunkIndices.isEmpty()) return
                result.add(
                    MeshPartition(
                        positions = chunkPositions.toFloatArray(),
                        indices = chunkIndices.toShortArray()
                    )
                )
                remap.clear()
                chunkPositions.clear()
                chunkIndices.clear()
            }

            var t = 0
            while (t + 2 < indices.size) {
                var newVertices = 0
                for (k in 0 until 3) {
                    if (!remap.containsKey(indices[t + k])) newVertices++
                }
                if (remap.size + newVertices > MAX_PARTITION_VERTICES) flush()

                for (k in 0 until 3) {
                    val original = indices[t + k]
                    val mapped = remap.getOrPut(original) {
                        chunkPositions.add(positions[original * 2])
                        chunkPositions.add(positions[original * 2 + 1])
                        remap.size
                    }
                    chunkIndices.add(mapped.toShort())
                }
                t += 3
            }
            flush()
            if (result.isEmpty()) result.add(MeshPartition(FloatArray(0), ShortArray(0)))
            return result
        }
    }
}
