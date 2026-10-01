package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.core.geometry.Measure
import io.github.valerioisufi.drawview.core.geometry.pt
import io.github.valerioisufi.drawview.ui.util.formatDecimal
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round

/**
 * A hybrid linear-exponential slider for intuitive brush and text sizing in points (pt).
 */
@Composable
fun SizeSlider(
    modifier: Modifier = Modifier,
    onSizeChanged: (Measure) -> Unit = {},
    size: Measure = 6.pt,
    valueRange: ClosedFloatingPointRange<Float> = 0.1f..15f,
    linearThreshold: Float = 3f,
    linearProportion: Float = 0.4f
) {
    val minVal = valueRange.start.coerceAtLeast(0.001f)
    val maxVal = valueRange.endInclusive.coerceAtLeast(minVal + 0.001f)
    val actualThreshold = linearThreshold.coerceIn(minVal + 0.001f, maxVal)

    val sliderPosition = if (maxVal <= actualThreshold) {
        ((size.pt - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
    } else if (size.pt <= actualThreshold) {
        val t = (size.pt - minVal) / (actualThreshold - minVal)
        (t * linearProportion).coerceIn(0f, 1f)
    } else {
        val expT = ln(size.pt / actualThreshold) / ln(maxVal / actualThreshold)
        (linearProportion + expT * (1f - linearProportion)).coerceIn(0f, 1f)
    }

    val displayValue = formatDecimal(size.pt, 1)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$displayValue pt",
            modifier = Modifier.width(55.dp)
        )

        Slider(
            value = sliderPosition,
            valueRange = 0f..1f,
            onValueChange = { t ->
                val rawValue = if (maxVal <= actualThreshold) {
                    minVal + t * (maxVal - minVal)
                } else if (t <= linearProportion) {
                    val normalizedT = t / linearProportion
                    minVal + normalizedT * (actualThreshold - minVal)
                } else {
                    val normalizedT = (t - linearProportion) / (1f - linearProportion)
                    actualThreshold * (maxVal / actualThreshold).pow(normalizedT)
                }

                val roundedValue = round(rawValue * 10f) / 10f
                onSizeChanged(roundedValue.pt)
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.secondary
            )
        )
    }
}
