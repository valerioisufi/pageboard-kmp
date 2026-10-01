#include "ink_bridge.h"

#include <algorithm>
#include <cstdint>
#include <memory>
#include <vector>

#include "ink/brush/brush.h"
#include "ink/brush/brush_family.h"
#include "ink/brush/stock_brushes.h"
#include "ink/color/color.h"
#include "ink/geometry/point.h"
#include "ink/strokes/in_progress_stroke.h"
#include "ink/strokes/input/stroke_input.h"
#include "ink/strokes/input/stroke_input_batch.h"
#include "ink/types/duration.h"
#include "ink/types/physical_distance.h"

struct InkStrokeBuilderWrapper {
    ink::InProgressStroke stroke;
    int64_t start_time_ms = -1;
    int64_t last_time_ms = 0;
    bool has_points = false;
    float last_x = 0.0f;
    float last_y = 0.0f;
    bool started = false;
    float stroke_unit_length_cm = 0.0f;
    ink::StrokeInput::ToolType tool_type = ink::StrokeInput::ToolType::kUnknown;
    bool has_pressure = false;
};

static ink::StrokeInput::ToolType ToInkToolType(int32_t tool_type) {
    switch (tool_type) {
        case INK_TOOL_MOUSE:
            return ink::StrokeInput::ToolType::kMouse;
        case INK_TOOL_TOUCH:
            return ink::StrokeInput::ToolType::kTouch;
        case INK_TOOL_STYLUS:
            return ink::StrokeInput::ToolType::kStylus;
        default:
            return ink::StrokeInput::ToolType::kUnknown;
    }
}

extern "C" {

InkBrushHandle ink_create_brush(
    int family_type,
    float size,
    float r, float g, float b, float a,
    float epsilon
) {
    ink::BrushFamily family;
    switch (family_type) {
        case INK_BRUSH_MARKER:
            family = ink::stock_brushes::Marker();
            break;
        case INK_BRUSH_HIGHLIGHTER:
            family = ink::stock_brushes::Highlighter();
            break;
        case INK_BRUSH_DASHED_LINE:
            family = ink::stock_brushes::DashedLine();
            break;
        case INK_BRUSH_PRESSURE_PEN:
        default:
            family = ink::stock_brushes::PressurePen();
            break;
    }

    ink::Color color = ink::Color::FromFloat(r, g, b, a);
    if (!(epsilon > 0.0f)) {
        epsilon = 0.1f;
    }
    if (size <= epsilon) {
        size = epsilon * 1.01f;
    }

    auto brush_status = ink::Brush::Create(family, color, size, epsilon);
    if (!brush_status.ok()) {
        return nullptr;
    }

    return new ink::Brush(std::move(*brush_status));
}

void ink_destroy_brush(InkBrushHandle handle) {
    if (handle != nullptr) {
        delete static_cast<ink::Brush*>(handle);
    }
}

InkStrokeBuilderHandle ink_stroke_builder_create(void) {
    return new InkStrokeBuilderWrapper();
}

void ink_stroke_builder_start(
    InkStrokeBuilderHandle handle,
    InkBrushHandle brush_handle,
    float stroke_unit_length_cm
) {
    if (handle == nullptr || brush_handle == nullptr) {
        return;
    }
    auto* builder = static_cast<InkStrokeBuilderWrapper*>(handle);
    auto* brush = static_cast<ink::Brush*>(brush_handle);

    builder->stroke.Clear();
    builder->stroke.Start(*brush, /*noise_seed=*/0, /*base_animation_phase=*/0.0f);
    builder->start_time_ms = -1;
    builder->last_time_ms = 0;
    builder->has_points = false;
    builder->started = true;
    builder->stroke_unit_length_cm = stroke_unit_length_cm;
}

void ink_stroke_builder_add_point(
    InkStrokeBuilderHandle handle,
    float x, float y,
    float pressure,
    int64_t time_ms,
    int32_t tool_type
) {
    if (handle == nullptr) return;
    auto* builder = static_cast<InkStrokeBuilderWrapper*>(handle);
    if (!builder->started) return;

    if (builder->start_time_ms < 0) {
        builder->start_time_ms = time_ms;
    }

    int64_t elapsed_ms = time_ms - builder->start_time_ms;
    if (elapsed_ms < 0) elapsed_ms = 0;

    if (builder->has_points) {
        if (elapsed_ms < builder->last_time_ms) {
            elapsed_ms = builder->last_time_ms;
        }
        // Avoid duplicate identical points with exact same timestamp
        if (elapsed_ms == builder->last_time_ms && x == builder->last_x && y == builder->last_y) {
            return;
        }
        if (elapsed_ms == builder->last_time_ms) {
            elapsed_ms = builder->last_time_ms + 1;
        }
    } else {
        // Every input of a batch must share tool type and pressure availability:
        // lock them on the first point of the stroke.
        builder->tool_type = ToInkToolType(tool_type);
        builder->has_pressure = pressure >= 0.0f && pressure <= 1.0f;
    }

    ink::StrokeInput input;
    input.tool_type = builder->tool_type;
    input.position = ink::Point{x, y};
    input.elapsed_time = ink::Duration32::Millis(static_cast<float>(elapsed_ms));
    if (builder->stroke_unit_length_cm > 0.0f) {
        input.stroke_unit_length = ink::PhysicalDistance::Centimeters(builder->stroke_unit_length_cm);
    }
    if (builder->has_pressure) {
        input.pressure = std::clamp(pressure, 0.0f, 1.0f);
    } else {
        input.pressure = ink::StrokeInput::kNoPressure;
    }

    ink::StrokeInputBatch real_batch;
    auto append_status = real_batch.Append(input);
    if (!append_status.ok()) {
        return;
    }

    ink::StrokeInputBatch predicted_batch;
    auto enqueue_status = builder->stroke.EnqueueInputs(real_batch, predicted_batch);
    if (!enqueue_status.ok()) {
        return;
    }

    builder->last_time_ms = elapsed_ms;
    builder->last_x = x;
    builder->last_y = y;
    builder->has_points = true;

    builder->stroke.UpdateShape(input.elapsed_time);
}

void ink_stroke_builder_finish_inputs(InkStrokeBuilderHandle handle) {
    if (handle == nullptr) return;
    auto* builder = static_cast<InkStrokeBuilderWrapper*>(handle);
    if (!builder->started) return;

    builder->stroke.FinishInputs();
    builder->stroke.UpdateShape(ink::Duration32::Millis(static_cast<float>(builder->last_time_ms)));
}

void ink_stroke_builder_clear(InkStrokeBuilderHandle handle) {
    if (handle == nullptr) return;
    auto* builder = static_cast<InkStrokeBuilderWrapper*>(handle);
    builder->stroke.Clear();
    builder->start_time_ms = -1;
    builder->last_time_ms = 0;
    builder->has_points = false;
    builder->started = false;
}

void ink_stroke_builder_destroy(InkStrokeBuilderHandle handle) {
    if (handle != nullptr) {
        delete static_cast<InkStrokeBuilderWrapper*>(handle);
    }
}

int32_t ink_stroke_builder_get_vertex_count(InkStrokeBuilderHandle handle) {
    if (handle == nullptr) return 0;
    auto* builder = static_cast<InkStrokeBuilderWrapper*>(handle);
    if (!builder->started) return 0;

    uint64_t total = 0;
    for (uint32_t coat = 0; coat < builder->stroke.BrushCoatCount(); ++coat) {
        total += builder->stroke.GetMesh(coat).VertexCount();
    }
    return static_cast<int32_t>(total);
}

int32_t ink_stroke_builder_get_triangle_count(InkStrokeBuilderHandle handle) {
    if (handle == nullptr) return 0;
    auto* builder = static_cast<InkStrokeBuilderWrapper*>(handle);
    if (!builder->started) return 0;

    uint64_t total = 0;
    for (uint32_t coat = 0; coat < builder->stroke.BrushCoatCount(); ++coat) {
        total += builder->stroke.GetMesh(coat).TriangleCount();
    }
    return static_cast<int32_t>(total);
}

int32_t ink_stroke_builder_get_mesh(
    InkStrokeBuilderHandle handle,
    float* out_positions,
    int32_t max_positions,
    uint32_t* out_indices,
    int32_t max_indices
) {
    if (handle == nullptr || out_positions == nullptr || out_indices == nullptr) {
        return -1;
    }
    auto* builder = static_cast<InkStrokeBuilderWrapper*>(handle);
    if (!builder->started) {
        return 0;
    }

    uint64_t total_vertices = 0;
    uint64_t total_triangles = 0;
    for (uint32_t coat = 0; coat < builder->stroke.BrushCoatCount(); ++coat) {
        const auto& mesh = builder->stroke.GetMesh(coat);
        total_vertices += mesh.VertexCount();
        total_triangles += mesh.TriangleCount();
    }

    if (static_cast<uint64_t>(max_positions) < total_vertices * 2 ||
        static_cast<uint64_t>(max_indices) < total_triangles * 3) {
        return -1;
    }

    uint32_t vertex_offset = 0;
    uint64_t position_cursor = 0;
    uint64_t index_cursor = 0;
    for (uint32_t coat = 0; coat < builder->stroke.BrushCoatCount(); ++coat) {
        const auto& mesh = builder->stroke.GetMesh(coat);
        const uint32_t v_count = mesh.VertexCount();
        const uint32_t t_count = mesh.TriangleCount();

        for (uint32_t i = 0; i < v_count; ++i) {
            ink::Point pt = mesh.VertexPosition(i);
            out_positions[position_cursor++] = pt.x;
            out_positions[position_cursor++] = pt.y;
        }

        for (uint32_t i = 0; i < t_count; ++i) {
            auto tri = mesh.TriangleIndices(i);
            out_indices[index_cursor++] = vertex_offset + static_cast<uint32_t>(tri[0]);
            out_indices[index_cursor++] = vertex_offset + static_cast<uint32_t>(tri[1]);
            out_indices[index_cursor++] = vertex_offset + static_cast<uint32_t>(tri[2]);
        }
        vertex_offset += v_count;
    }

    return 0;
}

} // extern "C"
