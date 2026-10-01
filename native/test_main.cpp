#include <cstdio>
#include <vector>
#include "bridge/ink_bridge.h"

int main() {
    printf("Testing ink_bridge...\n");

    // 1. Create a pressure pen brush
    InkBrushHandle brush = ink_create_brush(
        INK_BRUSH_PRESSURE_PEN,
        15.0f, // size
        0.0f, 0.0f, 0.0f, 1.0f, // black color
        0.1f // epsilon
    );

    if (!brush) {
        printf("Failed to create brush!\n");
        return 1;
    }
    printf("Brush created successfully: %p\n", brush);

    // 2. Create stroke builder
    InkStrokeBuilderHandle builder = ink_stroke_builder_create();
    if (!builder) {
        printf("Failed to create stroke builder!\n");
        return 1;
    }
    printf("Stroke builder created: %p\n", builder);

    // 3. Start stroke
    ink_stroke_builder_start(builder, brush);

    // 4. Add points (simulating drawing a curved line)
    for (int i = 0; i <= 20; ++i) {
        float x = 100.0f + i * 10.0f;
        float y = 100.0f + (i * i) * 0.5f;
        float pressure = 0.3f + 0.02f * i;
        int64_t t = i * 16; // ~60fps timestamps
        ink_stroke_builder_add_point(builder, x, y, pressure, t);
    }

    ink_stroke_builder_finish_inputs(builder);

    // 5. Query mesh
    int32_t v_count = ink_stroke_builder_get_vertex_count(builder);
    int32_t t_count = ink_stroke_builder_get_triangle_count(builder);
    printf("Stroke modeled! Vertex count = %d, Triangle count = %d\n", v_count, t_count);

    if (v_count > 0 && t_count > 0) {
        std::vector<float> positions(v_count * 2);
        std::vector<uint16_t> indices(t_count * 3);

        int32_t res = ink_stroke_builder_get_mesh(
            builder,
            positions.data(), positions.size(),
            indices.data(), indices.size()
        );

        if (res == 0) {
            printf("Successfully extracted mesh!\n");
            printf("First vertex: (%.2f, %.2f)\n", positions[0], positions[1]);
            printf("Last vertex: (%.2f, %.2f)\n", positions[2 * (v_count - 1)], positions[2 * (v_count - 1) + 1]);
            printf("First triangle indices: (%u, %u, %u)\n", indices[0], indices[1], indices[2]);
        } else {
            printf("Failed to get mesh data! res = %d\n", res);
            return 1;
        }
    } else {
        printf("ERROR: Expected vertices and triangles, got 0!\n");
        return 1;
    }

    ink_stroke_builder_destroy(builder);
    ink_destroy_brush(brush);
    printf("All ink_bridge tests PASSED!\n");
    return 0;
}
