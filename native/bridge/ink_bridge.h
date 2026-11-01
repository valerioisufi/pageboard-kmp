#ifndef INK_BRIDGE_H
#define INK_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>
#include <stdbool.h>

typedef void* InkBrushHandle;
typedef void* InkStrokeBuilderHandle;

// Brush family types matching Ink stock brushes
typedef enum {
    INK_BRUSH_PRESSURE_PEN = 0,
    INK_BRUSH_MARKER = 1,
    INK_BRUSH_HIGHLIGHTER = 2,
    INK_BRUSH_DASHED_LINE = 3
} InkBrushFamily;

// Input tool types (mirrors ink::StrokeInput::ToolType)
typedef enum {
    INK_TOOL_UNKNOWN = 0,
    INK_TOOL_MOUSE = 1,
    INK_TOOL_TOUCH = 2,
    INK_TOOL_STYLUS = 3
} InkToolType;

/**
 * Creates a new Brush instance.
 * @param family_type 0 = PressurePen, 1 = Marker, 2 = Highlighter, 3 = DashedLine
 * @param size Brush tip size / stroke thickness in stroke units
 * @param r Red component (0.0 to 1.0)
 * @param g Green component (0.0 to 1.0)
 * @param b Blue component (0.0 to 1.0)
 * @param a Alpha component (0.0 to 1.0)
 * @param epsilon Visual fidelity / simplification threshold in stroke units
 * @return Handle to the brush or NULL on error.
 */
InkBrushHandle ink_create_brush(
    int family_type,
    float size,
    float r, float g, float b, float a,
    float epsilon
);

/**
 * Destroys a brush handle previously created with ink_create_brush.
 */
void ink_destroy_brush(InkBrushHandle handle);

/**
 * Creates a new InProgressStroke builder handle.
 */
InkStrokeBuilderHandle ink_stroke_builder_create(void);

/**
 * Starts a new stroke with the specified brush.
 * @param stroke_unit_length_cm Physical length (in centimeters) of one stroke unit, or a value
 *        <= 0 if the relationship between stroke space and physical space is unknown.
 *        DrawView feeds strokes in page millimeters, so it passes 0.1.
 */
void ink_stroke_builder_start(
    InkStrokeBuilderHandle handle,
    InkBrushHandle brush_handle,
    float stroke_unit_length_cm
);

/**
 * Adds an input point to the current stroke.
 * @param handle The builder handle.
 * @param x X coordinate in stroke space.
 * @param y Y coordinate in stroke space.
 * @param pressure Stylus pressure (0.0 to 1.0) or a negative value if not available.
 * @param time_ms Timestamp in milliseconds (any monotonic origin).
 * @param tool_type One of InkToolType.
 */
void ink_stroke_builder_add_point(
    InkStrokeBuilderHandle handle,
    float x, float y,
    float pressure,
    int64_t time_ms,
    int32_t tool_type
);

/**
 * Adds the inputs of a stroke at once: they are modeled as ink_stroke_builder_add_point would
 * model them one by one, with the shape updated once after the last one.
 * @param handle The builder handle.
 * @param xs X coordinates in stroke space, one per input.
 * @param ys Y coordinates in stroke space, one per input.
 * @param pressures Stylus pressures (0.0 to 1.0), or negative values if not available.
 * @param times_ms Timestamps in milliseconds (any monotonic origin).
 * @param count Number of inputs in every array.
 * @param tool_type One of InkToolType.
 */
void ink_stroke_builder_add_points(
    InkStrokeBuilderHandle handle,
    const float* xs, const float* ys,
    const float* pressures,
    const int64_t* times_ms,
    int32_t count,
    int32_t tool_type
);

/**
 * Predicts the stroke beyond its last input with predicted inputs, modeled with the brush of the
 * stroke and replaced by the next call to this function or by the next real inputs (which take the
 * prediction away). They never become inputs of the stroke, and the geometry of the stroke once its
 * inputs are finished has none of them. Inputs not after the last real one are left out; no input
 * takes the prediction away.
 *
 * @param handle The builder handle.
 * @param xs X coordinates in stroke space, one per predicted input.
 * @param ys Y coordinates in stroke space, one per predicted input.
 * @param pressures Stylus pressures (0.0 to 1.0), or negative values if not available.
 * @param times_ms Timestamps in milliseconds, in the origin of the real inputs.
 * @param count Number of inputs in every array (0: no prediction).
 */
void ink_stroke_builder_predict(
    InkStrokeBuilderHandle handle,
    const float* xs, const float* ys,
    const float* pressures,
    const int64_t* times_ms,
    int32_t count
);

/**
 * Gives the rectangle of the geometry added, changed or removed since the last call to this function
 * (or the start of the stroke), and starts counting again.
 *
 * @param handle The builder handle.
 * @param out_bounds Receives min x, min y, max x, max y in stroke space.
 * @return 1 when the rectangle was written, 0 when nothing changed.
 */
int32_t ink_stroke_builder_take_updated_region(InkStrokeBuilderHandle handle, float* out_bounds);

/**
 * Notifies the builder that pointer inputs have completed (e.g. pointer up).
 */
void ink_stroke_builder_finish_inputs(InkStrokeBuilderHandle handle);

/**
 * Resets the builder state so it can be reused for a new stroke without reallocating.
 */
void ink_stroke_builder_clear(InkStrokeBuilderHandle handle);

/**
 * Destroys the stroke builder handle.
 */
void ink_stroke_builder_destroy(InkStrokeBuilderHandle handle);

/**
 * Returns the current number of vertices in the modeled stroke mesh (all brush coats).
 */
int32_t ink_stroke_builder_get_vertex_count(InkStrokeBuilderHandle handle);

/**
 * Returns the current number of triangles in the modeled stroke mesh (all brush coats).
 */
int32_t ink_stroke_builder_get_triangle_count(InkStrokeBuilderHandle handle);

/**
 * Extracts the modeled mesh data (all brush coats concatenated).
 * @param handle The builder handle.
 * @param out_positions Output buffer for vertex coordinates (x, y pairs -> 2 floats per vertex).
 * @param max_positions Maximum floats the out_positions buffer can hold (must be >= 2 * vertex_count).
 * @param out_indices Output buffer for triangle indices (3 indices per triangle). Indices are
 *        32-bit: meshes with more than 65535 vertices are partitioned on the Kotlin side.
 * @param max_indices Maximum indices the out_indices buffer can hold (must be >= 3 * triangle_count).
 * @return 0 on success, or -1 on error/buffer too small.
 */
int32_t ink_stroke_builder_get_mesh(
    InkStrokeBuilderHandle handle,
    float* out_positions,
    int32_t max_positions,
    uint32_t* out_indices,
    int32_t max_indices
);

/**
 * Returns the number of outlines of the modeled stroke (all brush coats): closed polygons whose
 * union, filled with the non-zero winding rule, is the shape of the stroke.
 */
int32_t ink_stroke_builder_get_outline_count(InkStrokeBuilderHandle handle);

/**
 * Returns the number of vertices of all the outlines of the modeled stroke.
 */
int32_t ink_stroke_builder_get_outline_vertex_count(InkStrokeBuilderHandle handle);

/**
 * Extracts the outlines of the modeled stroke (all brush coats).
 * @param handle The builder handle.
 * @param out_positions Output buffer for the vertices of the outlines, one outline after the
 *        other (x, y pairs -> 2 floats per vertex).
 * @param max_positions Maximum floats the out_positions buffer can hold (must be >= 2 * outline
 *        vertex count).
 * @param out_lengths Output buffer for the number of vertices of every outline.
 * @param max_outlines Maximum values the out_lengths buffer can hold (must be >= outline count).
 * @return 0 on success, or -1 on error/buffer too small.
 */
int32_t ink_stroke_builder_get_outlines(
    InkStrokeBuilderHandle handle,
    float* out_positions,
    int32_t max_positions,
    int32_t* out_lengths,
    int32_t max_outlines
);

#ifdef __cplusplus
}
#endif

#endif // INK_BRIDGE_H
