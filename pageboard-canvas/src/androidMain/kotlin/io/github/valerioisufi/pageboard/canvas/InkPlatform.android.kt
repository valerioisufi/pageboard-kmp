package io.github.valerioisufi.pageboard.canvas

import android.os.Build
import android.view.MotionEvent
import android.view.MotionPredictor
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController

@Composable
internal actual fun rememberInkPlatform(controller: PageboardController): InkPlatform {
    val view = LocalView.current
    return remember(controller, view) { AndroidInkPlatform(controller, view) }
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.inkInput(platform: InkPlatform, fingerDraws: () -> Boolean): Modifier {
    val android = platform as? AndroidInkPlatform ?: return this
    return composed {
        val view = LocalView.current
        onGloballyPositioned { android.origin = it.positionInRoot() }
            .motionEventSpy { event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // Every event as soon as the digitizer sends it, not batched to the frames
                    val tool = event.getToolType(0)
                    val drawing = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER ||
                        (tool == MotionEvent.TOOL_TYPE_FINGER && fingerDraws())
                    if (drawing) view.requestUnbufferedDispatch(event)
                }
                android.eventSeen(event)
            }
    }
}

/**
 * The ink on Android (see `docs/plans/ink-rendering-camera.md`): the pressures of the samples of the
 * `MotionEvent`s, which Compose leaves out of its historical changes; the predictor of the system
 * (Android 14) for the pens it knows, the Kalman filter otherwise; a frame of the display of [view].
 * The events come from the spy of the canvas, before the gesture reads them.
 */
internal class AndroidInkPlatform(controller: PageboardController, private val view: View) : InkPlatform {
    override val pressures: SamplePressures = SamplePressures()

    /** Where the canvas is in the view of the composition, whose coordinates the events have. */
    var origin: Offset = Offset.Zero

    private val system: SystemPrediction? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching { SystemPrediction(MotionPredictor(view.context)) { origin } }.getOrNull() else null

    override val predictor: InkPredictor = KalmanInkPredictor { controller.viewport.density }.let { kalman ->
        if (system != null) PreferredInkPredictor(system, kalman) else kalman
    }

    override val frameMillis: Long get() = frameMillisOf(view.display?.refreshRate ?: 0f)

    /** A `MotionEvent` reaching the canvas: its samples kept with their pressures, told to the system predictor. */
    fun eventSeen(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            for (pointer in 0 until event.pointerCount) {
                for (sample in 0 until event.historySize) {
                    pressures.record(
                        event.getHistoricalEventTime(sample),
                        event.getHistoricalX(pointer, sample) - origin.x,
                        event.getHistoricalY(pointer, sample) - origin.y,
                        event.getHistoricalPressure(pointer, sample)
                    )
                }
            }
        }
        system?.eventSeen(event)
    }
}

/**
 * The predictor of Android 14 (`MotionPredictor`), for a stroke of a device and a source that the
 * system predicts (a stylus it has a model for); `null` otherwise, and the Kalman filter predicts.
 * It reads the `MotionEvent`s themselves, so [record] has nothing to do.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class SystemPrediction(private val predictor: MotionPredictor, private val origin: () -> Offset) : PlatformPrediction {
    /** Whether the system predicts the pointer of the stroke in progress. */
    private var available = false
    private var lastEventNanos = 0L

    fun eventSeen(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            available = event.pointerCount == 1 && predictor.isPredictionAvailable(event.deviceId, event.source)
        }
        if (!available) return
        // Once a second pointer comes, the stroke is a pinch or a palm: nothing predicted
        if (event.pointerCount > 1) {
            available = false
            return
        }
        predictor.record(event)
        lastEventNanos = event.eventTimeNanos
    }

    override fun start() = Unit

    override fun record(position: Offset, timeMs: Long) = Unit

    override fun predict(horizonMs: Long): List<PredictedPoint>? {
        if (!available) return null
        val predicted = predictor.predict(lastEventNanos + horizonMs * 1_000_000L) ?: return emptyList()
        try {
            val offset = origin()
            val points = ArrayList<PredictedPoint>(predicted.historySize + 1)
            for (sample in 0 until predicted.historySize) {
                points += PredictedPoint(Offset(predicted.getHistoricalX(0, sample) - offset.x, predicted.getHistoricalY(0, sample) - offset.y), predicted.getHistoricalEventTime(sample))
            }
            points += PredictedPoint(Offset(predicted.x - offset.x, predicted.y - offset.y), predicted.eventTime)
            return points
        } finally {
            predicted.recycle()
        }
    }
}
