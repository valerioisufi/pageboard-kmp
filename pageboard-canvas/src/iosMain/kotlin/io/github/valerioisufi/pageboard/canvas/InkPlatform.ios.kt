package io.github.valerioisufi.pageboard.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.uikit.LocalUIViewController
import io.github.valerioisufi.pageboard.canvas.cinterop.PBTouchObserver
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import kotlin.math.hypot
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIEvent
import platform.UIKit.UIScreen
import platform.UIKit.UITouch
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberInkPlatform(controller: PageboardController): InkPlatform {
    val viewController = LocalUIViewController.current
    val platform = remember(controller) { IosInkPlatform(controller) }
    DisposableEffect(viewController, platform) {
        val view = viewController.view
        // Sees the touches without taking any: the gestures of Compose get every touch as before
        val observer = PBTouchObserver()
        observer.onTouchesMoved = { touches, event ->
            if (touches != null && event != null) platform.touchesMoved(touches, event, view)
        }
        view.addGestureRecognizer(observer)
        onDispose {
            view.removeGestureRecognizer(observer)
            observer.onTouchesMoved = null
        }
    }
    return platform
}

internal actual fun Modifier.inkInput(platform: InkPlatform, fingerDraws: () -> Boolean): Modifier {
    val ios = platform as? IosInkPlatform ?: return this
    return onGloballyPositioned { ios.origin = it.positionInWindow() }
}

/**
 * The ink on iOS (see `docs/plans/ink-rendering-camera.md`): the pressures of the coalesced touches
 * (the 240 Hz of the Apple Pencil), which Compose gives as historical changes without their force;
 * the predicted touches of UIKit, the Kalman filter when there are none; a frame of the fastest
 * refresh of the screen (ProMotion).
 *
 * The touches come from a gesture recognizer of their own, which UIKit may give an event before or
 * after the one of Compose: a prediction that comes after the gesture asks it to predict again
 * ([predictionArrived]), and a pressure that comes after it is not found (the sample takes the
 * pressure of its event, as before).
 */
internal class IosInkPlatform(private val controller: PageboardController) : InkPlatform {
    override val pressures: SamplePressures = SamplePressures()

    /** Where the canvas is in the window of the composition, in pixels. */
    var origin: Offset = Offset.Zero

    private val touches = PredictedTouches()

    override val predictor: InkPredictor = PreferredInkPredictor(touches, KalmanInkPredictor { controller.viewport.density })

    override val frameMillis: Long = frameMillisOf(UIScreen.mainScreen.maximumFramesPerSecond.toFloat())

    /** Called when the predicted touches of an event come after the gesture has read it: to predict the stroke again. */
    var predictionArrived: (() -> Unit)? = null

    override fun setPredictionListener(listener: (() -> Unit)?) {
        predictionArrived = listener
    }

    /** A point of the view of the composition (points, as UIKit gives them) in the pixels of the canvas. */
    private fun canvasPoint(x: Double, y: Double): Offset {
        val density = controller.viewport.density
        return Offset((x * density).toFloat() - origin.x, (y * density).toFloat() - origin.y)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun UITouch.canvasPosition(view: UIView): Offset = locationInView(view).useContents { canvasPoint(x, y) }

    /** The touches moved in [event]: their coalesced samples kept with their force, their predicted ones. */
    fun touchesMoved(moved: Set<*>, event: UIEvent, view: UIView) {
        for (touch in moved) {
            touch as UITouch
            event.coalescedTouchesForTouch(touch)?.forEach { sample ->
                sample as UITouch
                val position = sample.canvasPosition(view)
                // As Compose does: the force of the touch, its timestamp in milliseconds of uptime
                pressures.record((sample.timestamp * 1e3).toLong(), position.x, position.y, sample.force.toFloat())
            }
            val predicted = event.predictedTouchesForTouch(touch)?.map { next ->
                next as UITouch
                PredictedPoint(next.canvasPosition(view), (next.timestamp * 1e3).toLong())
            }.orEmpty()
            if (touches.predicted(touch.canvasPosition(view), (touch.timestamp * 1e3).toLong(), predicted)) {
                predictionArrived?.invoke()
            }
        }
    }
}

/**
 * The predicted touches of UIKit for the pointer drawing the stroke: the touch whose last place is
 * the last sample of the stroke. `null` when UIKit predicted nothing for it, and the Kalman filter
 * predicts.
 */
private class PredictedTouches : PlatformPrediction {
    private var lastSample: Offset? = null
    private var lastSampleTime = Long.MIN_VALUE
    private var anchor: Offset? = null
    private var anchorTime = Long.MIN_VALUE
    private var points: List<PredictedPoint> = emptyList()

    override fun start() {
        lastSample = null
        lastSampleTime = Long.MIN_VALUE
        anchor = null
        points = emptyList()
    }

    override fun record(position: Offset, timeMs: Long) {
        lastSample = position
        lastSampleTime = timeMs
    }

    /**
     * UIKit predicted [predicted] after a touch at [position] at [timeMs]: whether the gesture has
     * already read that sample, so that it predicts again.
     */
    fun predicted(position: Offset, timeMs: Long, predicted: List<PredictedPoint>): Boolean {
        anchor = position
        anchorTime = timeMs
        points = predicted
        val sample = lastSample ?: return false
        return predicted.isNotEmpty() && lastSampleTime >= timeMs && hypot(sample.x - position.x, sample.y - position.y) <= MATCH_PX
    }

    override fun predict(horizonMs: Long): List<PredictedPoint>? {
        val sample = lastSample ?: return null
        val anchor = anchor ?: return null
        // Of another touch, or of an event before the last sample: the filter knows better
        if (hypot(sample.x - anchor.x, sample.y - anchor.y) > MATCH_PX || anchorTime < lastSampleTime || points.isEmpty()) return null
        return points.filter { it.timeMs > lastSampleTime && it.timeMs <= lastSampleTime + horizonMs }
    }

    private companion object {
        /** The rounding of the positions between UIKit and Compose, in pixels. */
        const val MATCH_PX = 2f
    }
}
