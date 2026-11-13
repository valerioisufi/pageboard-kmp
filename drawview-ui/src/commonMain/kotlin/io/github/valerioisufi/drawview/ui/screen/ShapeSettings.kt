package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.ShapeToolStyle
import io.github.valerioisufi.drawview.canvas.render.drawVectors
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.geometry.mm
import io.github.valerioisufi.drawview.model.geometry.pt
import io.github.valerioisufi.drawview.model.shape.ShapeDash
import io.github.valerioisufi.drawview.model.shape.ShapeElement
import io.github.valerioisufi.drawview.model.shape.ShapeGeometry
import io.github.valerioisufi.drawview.model.shape.ShapeHead
import io.github.valerioisufi.drawview.model.shape.ShapeKind
import io.github.valerioisufi.drawview.ui.components.ColorSwatches
import io.github.valerioisufi.drawview.ui.components.HybridScale
import io.github.valerioisufi.drawview.ui.components.PopoverTitle
import io.github.valerioisufi.drawview.ui.components.SettingHint
import io.github.valerioisufi.drawview.ui.components.SettingLabel
import io.github.valerioisufi.drawview.ui.components.SizeSetting
import io.github.valerioisufi.drawview.ui.components.TextColorPickerDialog
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.shape_arrow
import io.github.valerioisufi.drawview.ui.generated.resources.shape_diamond
import io.github.valerioisufi.drawview.ui.generated.resources.shape_double_arrow
import io.github.valerioisufi.drawview.ui.generated.resources.shape_ellipse
import io.github.valerioisufi.drawview.ui.generated.resources.shape_fill
import io.github.valerioisufi.drawview.ui.generated.resources.shape_fill_none
import io.github.valerioisufi.drawview.ui.generated.resources.shape_fill_solid
import io.github.valerioisufi.drawview.ui.generated.resources.shape_fill_tinted
import io.github.valerioisufi.drawview.ui.generated.resources.shape_head_filled
import io.github.valerioisufi.drawview.ui.generated.resources.shape_head_open
import io.github.valerioisufi.drawview.ui.generated.resources.shape_heads
import io.github.valerioisufi.drawview.ui.generated.resources.shape_hexagon
import io.github.valerioisufi.drawview.ui.generated.resources.shape_kind
import io.github.valerioisufi.drawview.ui.generated.resources.shape_line
import io.github.valerioisufi.drawview.ui.generated.resources.shape_outline
import io.github.valerioisufi.drawview.ui.generated.resources.shape_outline_dashed
import io.github.valerioisufi.drawview.ui.generated.resources.shape_outline_dotted
import io.github.valerioisufi.drawview.ui.generated.resources.shape_outline_solid
import io.github.valerioisufi.drawview.ui.generated.resources.shape_pentagon
import io.github.valerioisufi.drawview.ui.generated.resources.shape_rectangle
import io.github.valerioisufi.drawview.ui.generated.resources.shape_right_triangle
import io.github.valerioisufi.drawview.ui.generated.resources.shape_rounded_rectangle
import io.github.valerioisufi.drawview.ui.generated.resources.shape_triangle
import io.github.valerioisufi.drawview.ui.generated.resources.tool_settings_ink
import io.github.valerioisufi.drawview.ui.generated.resources.tool_settings_shapes_tool_hint
import io.github.valerioisufi.drawview.ui.generated.resources.tool_settings_size
import io.github.valerioisufi.drawview.ui.generated.resources.tool_shapes
import io.github.valerioisufi.drawview.ui.theme.InkPalette
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The shapes of the grid of the shapes tool: a kind, and the heads of a line (an arrow is a line with a head). */
internal enum class ShapeChoice(val kind: ShapeKind, val heads: Int, val label: StringResource) {
    LINE(ShapeKind.LINE, 0, Res.string.shape_line),
    ARROW(ShapeKind.LINE, 1, Res.string.shape_arrow),
    DOUBLE_ARROW(ShapeKind.LINE, 2, Res.string.shape_double_arrow),
    RECTANGLE(ShapeKind.RECTANGLE, 0, Res.string.shape_rectangle),
    ROUNDED_RECTANGLE(ShapeKind.ROUNDED_RECTANGLE, 0, Res.string.shape_rounded_rectangle),
    ELLIPSE(ShapeKind.ELLIPSE, 0, Res.string.shape_ellipse),
    TRIANGLE(ShapeKind.TRIANGLE, 0, Res.string.shape_triangle),
    RIGHT_TRIANGLE(ShapeKind.RIGHT_TRIANGLE, 0, Res.string.shape_right_triangle),
    DIAMOND(ShapeKind.DIAMOND, 0, Res.string.shape_diamond),
    PENTAGON(ShapeKind.PENTAGON, 0, Res.string.shape_pentagon),
    HEXAGON(ShapeKind.HEXAGON, 0, Res.string.shape_hexagon);

    /** Whether [style] draws this shape. */
    fun isChosen(style: ShapeToolStyle): Boolean = style.kind == kind && (!kind.isLine || style.heads == heads)

    companion object {
        /** The choice drawn by [style]. */
        fun of(style: ShapeToolStyle): ShapeChoice = entries.firstOrNull { it.isChosen(style) } ?: RECTANGLE
    }
}

/** How a shape is filled: from its fill and the color of its outline. */
internal enum class ShapeFill(val label: StringResource) {
    NONE(Res.string.shape_fill_none),
    TINTED(Res.string.shape_fill_tinted),
    SOLID(Res.string.shape_fill_solid);

    /** The fill of a shape whose outline is [stroke]. */
    fun of(stroke: Int): Int? = when (this) {
        NONE -> null
        TINTED -> (stroke and 0x00FFFFFF) or (TINT_ALPHA shl 24)
        SOLID -> stroke or (0xFF shl 24)
    }

    companion object {
        /** The tint of a tinted fill: a fifth of the color. */
        const val TINT_ALPHA = 0x33

        fun of(fill: Int?): ShapeFill = when {
            fill == null -> NONE
            (fill ushr 24) < 0xFF -> TINTED
            else -> SOLID
        }
    }
}

/**
 * What the settings of the shapes change: the style of the tool, or the shapes selected (see
 * [ShapeSettings]).
 */
internal interface ShapeStyleTarget {
    /** The style shown. */
    val style: ShapeToolStyle

    /** Chooses the shape [choice]. */
    fun choose(choice: ShapeChoice)

    /** Changes the style by [transform] (its kind kept). */
    fun change(transform: (ShapeToolStyle) -> ShapeToolStyle)
}

/** The style of the shapes tool: what it draws next. */
internal class ShapeToolTarget(private val controller: DrawViewController) : ShapeStyleTarget {
    override val style: ShapeToolStyle get() = controller.shapeInserter.style

    override fun choose(choice: ShapeChoice) = controller.shapeInserter.updateStyle(style.copy(kind = choice.kind, heads = choice.heads))

    override fun change(transform: (ShapeToolStyle) -> ShapeToolStyle) = controller.shapeInserter.updateStyle(transform(style))
}

/** The shapes selected: each keeps its kind, takes the change of style. */
internal class SelectedShapesTarget(private val controller: DrawViewController) : ShapeStyleTarget {
    override val style: ShapeToolStyle
        get() {
            val first = controller.shapeEditing.selectedShapes.firstOrNull()?.second
            val tool = controller.shapeInserter.style
            return if (first != null) tool.takingStyleOf(first) else tool
        }

    override fun choose(choice: ShapeChoice) = controller.shapeEditing.changeKind(choice.kind, choice.heads)

    override fun change(transform: (ShapeToolStyle) -> ShapeToolStyle) {
        val tool = controller.shapeInserter.style
        controller.shapeEditing.restyle { value -> transform(tool.takingStyleOf(value)).element() }
    }
}

/**
 * The settings of the shapes: the grid of the shapes (each drawn in the style chosen), the
 * color, the width, the outline, the fill (a shape of a box) or the heads (an arrow), applied to
 * [target]; the hint of the tool when [withHint].
 */
@Composable
internal fun ColumnScope.ShapeSettings(target: ShapeStyleTarget, title: String, withHint: Boolean) {
    val style = target.style
    val color = Color(style.stroke)
    var wheelOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(title) { ShapePreview(style.element(), Modifier.width(52.dp).height(28.dp)) }

        // The shapes, in rows of six, drawn as they would be
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingLabel(stringResource(Res.string.shape_kind))
            for (row in ShapeChoice.entries.chunked(GRID_COLUMNS)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    for (choice in row) {
                        val shown = style.copy(kind = choice.kind, heads = choice.heads)
                        ChoiceTile(
                            label = stringResource(choice.label),
                            selected = choice.isChosen(style),
                            onClick = { target.choose(choice) },
                            modifier = Modifier.weight(1f)
                        ) { ShapePreview(shown.element(), Modifier.fillMaxSize().padding(6.dp), maxStroke = 2.5.dp) }
                    }
                    repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingLabel(stringResource(Res.string.tool_settings_ink))
            ColorSwatches(
                colors = InkPalette.inks,
                selected = color,
                onSelect = { swatch -> target.change { it.recolored(swatch.toArgb()) } },
                onCustom = { wheelOpen = true }
            )
        }

        val sizePt = style.widthMm.mm.pt
        SizeSetting(
            label = stringResource(Res.string.tool_settings_size),
            value = sizePt,
            unit = "pt",
            scale = WIDTH_SCALE,
            onValueChange = { newPt ->
                val mm = newPt.pt.mm.coerceIn(ShapeElement.MIN_WIDTH_MM, ShapeElement.MAX_WIDTH_MM)
                target.change { it.copy(widthMm = mm) }
            }
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingLabel(stringResource(Res.string.shape_outline))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((dash, label) in listOf(
                    ShapeDash.SOLID to Res.string.shape_outline_solid,
                    ShapeDash.DASHED to Res.string.shape_outline_dashed,
                    ShapeDash.DOTTED to Res.string.shape_outline_dotted
                )) {
                    val shown = style.copy(kind = ShapeKind.LINE, heads = 0, dash = dash)
                    ChoiceTile(stringResource(label), style.dash == dash, { target.change { it.copy(dash = dash) } }, Modifier.weight(1f), wide = true) {
                        ShapePreview(shown.element(), Modifier.fillMaxSize().padding(horizontal = 8.dp), maxStroke = 2.5.dp, level = true)
                    }
                }
            }
        }

        if (style.kind.isLine) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingLabel(stringResource(Res.string.shape_heads))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((head, label) in listOf(ShapeHead.OPEN to Res.string.shape_head_open, ShapeHead.FILLED to Res.string.shape_head_filled)) {
                        val shown = style.copy(heads = 1, head = head, dash = ShapeDash.SOLID)
                        ChoiceTile(stringResource(label), style.head == head, { target.change { it.copy(head = head, heads = it.heads.coerceAtLeast(1)) } }, Modifier.weight(1f), wide = true) {
                            ShapePreview(shown.element(), Modifier.fillMaxSize().padding(horizontal = 8.dp), maxStroke = 2.5.dp, level = true)
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingLabel(stringResource(Res.string.shape_fill))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val current = ShapeFill.of(style.fill)
                    for (fill in ShapeFill.entries) {
                        val shown = style.copy(fill = fill.of(style.stroke), dash = ShapeDash.SOLID)
                        ChoiceTile(stringResource(fill.label), current == fill, { target.change { it.copy(fill = fill.of(it.stroke)) } }, Modifier.weight(1f), wide = true) {
                            ShapePreview(shown.element(), Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), maxStroke = 2.dp)
                        }
                    }
                }
            }
        }

        if (withHint) SettingHint(stringResource(Res.string.tool_settings_shapes_tool_hint))
    }
    if (wheelOpen) {
        TextColorPickerDialog(
            initialColor = color,
            onDismissRequest = { wheelOpen = false },
            onColorConfirmed = { picked -> target.change { it.recolored(picked.toArgb()) } }
        )
    }
}

/** This style with the outline [argb], its fill following it (a tint of it stays a tint). */
private fun ShapeToolStyle.recolored(argb: Int): ShapeToolStyle {
    val opaque = argb or (0xFF shl 24)
    return copy(stroke = opaque, fill = ShapeFill.of(fill).of(opaque))
}

/** A choice of the settings of the shapes: [content] in a tile, ringed in the primary color when [selected]. */
@Composable
private fun ChoiceTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = modifier
            .then(if (wide) Modifier.height(36.dp) else Modifier.aspectRatio(1f))
            .semantics {
                contentDescription = label
                this.selected = selected
            }
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * [value] drawn to fit this composable: a line across it (level when [level], rising otherwise),
 * a shape of a box in a box of its usual proportions; its outline as thick as its width on the
 * page, between 1 dp and [maxStroke].
 */
@Composable
internal fun ShapePreview(value: ShapeElement, modifier: Modifier = Modifier, maxStroke: Dp = 3.dp, level: Boolean = false, color: Color? = null) {
    Canvas(modifier = modifier) {
        val stroke = (value.widthMm * PREVIEW_PX_PER_MM * density).coerceIn(1.dp.toPx(), maxStroke.toPx())
            .coerceIn(ShapeElement.MIN_WIDTH_MM, ShapeElement.MAX_WIDTH_MM)
        val argb = color?.toArgb()
        val shown = value.copy(
            widthMm = stroke,
            stroke = argb ?: value.stroke,
            fill = value.fill?.let { fill -> if (argb == null) fill else ShapeFill.of(fill).of(argb) }
        )
        drawShape(shown, level)
    }
}

/** [value] (in pixels) drawn to fit the canvas. */
private fun DrawScope.drawShape(value: ShapeElement, level: Boolean) {
    val w = size.width
    val h = size.height
    if (value.kind.isLine) {
        val start = if (level) Point(0f, h / 2f) else Point(w * 0.12f, h * 0.85f)
        val end = if (level) Point(w, h / 2f) else Point(w * 0.88f, h * 0.15f)
        val box = ShapeGeometry.lineBox(start, end, value)
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(box.center.x, box.center.y)
            canvas.rotate(box.rotation)
            canvas.translate(-box.width / 2f, -box.height / 2f)
            canvas.drawVectors(ShapeGeometry.drawing(value, box.width, box.height))
            canvas.restore()
        }
        return
    }
    // The usual proportions of the kind, as large as the canvas holds
    val ratio = when (value.kind) {
        ShapeKind.RECTANGLE, ShapeKind.ROUNDED_RECTANGLE -> 1.45f
        ShapeKind.ELLIPSE -> 1.3f
        ShapeKind.TRIANGLE, ShapeKind.PENTAGON -> 1.08f
        ShapeKind.HEXAGON -> 1.15f
        else -> 1f
    }
    val width = minOf(w, h * ratio)
    val height = width / ratio
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.translate((w - width) / 2f, (h - height) / 2f)
        canvas.drawVectors(ShapeGeometry.drawing(value, width, height))
        canvas.restore()
    }
}

/** The shape chosen in the shapes tool, as the icon of its button: in the color of the icons. */
@Composable
internal fun ShapeToolIcon(style: ShapeToolStyle, modifier: Modifier = Modifier) {
    val icon = style.copy(dash = ShapeDash.SOLID, fill = null, widthMm = ShapeToolStyle.DEFAULT_WIDTH_MM).element()
    ShapePreview(icon, modifier.size(20.dp), maxStroke = 1.8.dp, color = LocalContentColor.current)
}

/** The width of the outlines, in points: precise up to 3 pt, up to 15 pt. */
private val WIDTH_SCALE = HybridScale(0.2f..15f, linearThreshold = 3f, linearProportion = 0.5f)

private const val GRID_COLUMNS = 6

/** How many pixels a millimeter of the page is in the previews (per dp). */
private const val PREVIEW_PX_PER_MM = 3f

/** The title of the settings of the shapes tool. */
@Composable
internal fun shapesTitle(): String = stringResource(Res.string.tool_shapes)
