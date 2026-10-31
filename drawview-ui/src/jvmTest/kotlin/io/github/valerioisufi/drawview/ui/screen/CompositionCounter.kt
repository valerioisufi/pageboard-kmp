package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokeTool

/**
 * Counts the scopes the composition enters again under [Observe], its sub-compositions included,
 * and the time the compositions take.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
internal class CompositionCounter {

    /**
     * Whether the scopes are counted and named by the function of this project that enters them
     * (read from the stack, which slows the compositions down).
     */
    var named: Boolean = false

    /**
     * The scopes entered again since [reset]: their functions called again, by a change they read
     * or by the scope calling them, whether they run or skip.
     */
    var entered: Int = 0
        private set

    /**
     * The scopes whose derived states were read again since [reset], unchanged: Compose checks
     * them without calling their functions.
     */
    var reread: Int = 0
        private set

    /** The time of the compositions since [reset], in nanoseconds. */
    var nanos: Long = 0L
        private set

    /** The scopes entered since [reset], by the function entering them. */
    val names: MutableMap<String, Int> = mutableMapOf()

    private var depth = 0
    private var started = 0L

    private val observer = object : CompositionObserver {
        override fun onBeginComposition(composition: ObservableComposition) {
            if (depth++ == 0) started = System.nanoTime()
        }

        override fun onEndComposition(composition: ObservableComposition) {
            if (--depth == 0) nanos += System.nanoTime() - started
        }

        override fun onScopeEnter(scope: RecomposeScope) {
            if (!named) return
            // A scope is entered by the composer as its function starts
            val caller = StackWalker.getInstance().walk { frames -> frames.skip(1).findFirst().map { it.methodName }.orElse(null) }
            if (caller != "enterRecomposeScope") {
                reread++
                return
            }
            entered++
            nameOf(scope).let { names[it] = (names[it] ?: 0) + 1 }
        }

        override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
        override fun onScopeExit(scope: RecomposeScope) = Unit
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) = Unit
    }

    /** Composes [content], counting the scopes it enters again. */
    @Composable
    fun Observe(content: @Composable () -> Unit) {
        val composition = currentComposer.composition
        DisposableEffect(composition) {
            val handle = composition.setObserver(observer)
            onDispose { handle?.dispose() }
        }
        content()
    }

    fun reset() {
        entered = 0
        reread = 0
        nanos = 0L
        names.clear()
    }

    /** The time of composition per [times]. */
    fun describeTime(times: Int): String = "%.3f ms of composition".format(nanos / 1e6 / times)

    /** The scopes entered again and read again per [times], and the functions entering them. */
    fun describeScopes(times: Int): String =
        "%.1f scopes entered again, %.1f read again".format(entered.toDouble() / times, reread.toDouble() / times) +
            names.entries.sortedByDescending { it.value }.joinToString(prefix = " [", postfix = "]") { "${it.key} %.1f".format(it.value.toDouble() / times) }

    /** The function of this project on the stack, else the lambda of the scope (entered again by itself). */
    private fun nameOf(scope: RecomposeScope): String {
        Throwable().stackTrace.firstOrNull { it.className.startsWith(PROJECT) && !it.className.startsWith(CompositionCounter::class.java.name) }
            ?.let { return "${it.className.substringAfterLast('.')}.${it.methodName}" }
        return runCatching {
            val update = scope.field("block") ?: return "?"
            val lambda = update.field("this$0")?.field("_block") ?: update
            lambda.javaClass.name.substringAfterLast('.')
        }.getOrDefault("?")
    }

    private fun Any.field(name: String): Any? =
        generateSequence<Class<*>>(javaClass) { it.superclass }.firstNotNullOfOrNull { type ->
            runCatching { type.getDeclaredField(name) }.getOrNull()
        }?.apply { isAccessible = true }?.get(this)

    companion object {
        private const val PROJECT = "io.github.valerioisufi"

        /** A stroke of one triangle: what is measured is the editor following it, not its mesh. */
        fun stroke(): DrawStroke = DrawStroke(
            mesh = StrokeMesh(
                positions = floatArrayOf(10f, 10f, 20f, 10f, 10f, 20f),
                indices = shortArrayOf(0, 1, 2),
                color = 0xFF000000.toInt(),
                tool = StrokeTool.PRESSURE_PEN,
                size = 1f
            ),
            brushConfig = BrushConfig(size = 1f)
        )
    }
}
