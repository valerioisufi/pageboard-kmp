package io.nano.tex

/**
 * Records the drawing operations emitted by the native engine (through JNI) and replays them
 * on a [Graphics2D].
 */
class ActionRecorder {

    private class Action(val action: Int, val arg: Any?, val args: FloatArray?)

    private val actions = mutableListOf<Action>()
    private val origin = Action(ACT_TRANSLATE, null, floatArrayOf(0f, 0f))

    init {
        actions.add(origin)
    }

    fun setPosition(x: Float, y: Float) {
        origin.args!![0] = x
        origin.args[1] = y
    }

    /** Called by the native engine. */
    fun record(action: Int, arg: Any?, args: FloatArray?) {
        actions.add(Action(action, arg, args))
    }

    fun play(g2: Graphics2D) {
        for (act in actions) play(g2, act)
    }

    private fun play(g2: Graphics2D, act: Action) {
        val a = act.args
        when (act.action) {
            ACT_SET_FONT -> g2.setFont(act.arg as? Font)
            ACT_SET_COLOR -> if (a != null && a.isNotEmpty()) g2.setColor(a[0].toInt())
            ACT_SET_STROKE_WIDTH -> if (a != null && a.isNotEmpty()) g2.setStrokeWidth(a[0])
            ACT_SET_STROKE -> if (a != null && a.size >= 4) g2.setStroke(a[0], a[1], a[2].toInt(), a[3].toInt())
            ACT_TRANSLATE -> if (a != null && a.size >= 2) g2.translate(a[0], a[1])
            ACT_SCALE -> if (a != null && a.size >= 2) g2.scale(a[0], a[1])
            ACT_ROTATE -> if (a != null && a.size >= 3) g2.rotate(a[0], a[1], a[2])
            ACT_RESET -> g2.reset()
            ACT_DRAW_CHAR -> if (a != null && a.size >= 3) g2.drawChar(a[0].toInt().toChar(), a[1], a[2])
            ACT_DRAW_TEXT -> if (a != null && a.size >= 2) g2.drawText(act.arg as? String ?: "", a[0], a[1])
            ACT_DRAW_LINE -> if (a != null && a.size >= 4) g2.drawLine(a[0], a[1], a[2], a[3])
            ACT_DRAW_RECT -> if (a != null && a.size >= 4) g2.drawRect(a[0], a[1], a[2], a[3])
            ACT_FILL_RECT -> if (a != null && a.size >= 4) g2.fillRect(a[0], a[1], a[2], a[3])
            ACT_DRAW_ROUND_RECT -> if (a != null && a.size >= 6) g2.drawRoundRect(a[0], a[1], a[2], a[3], a[4], a[5])
            ACT_FILL_ROUND_RECT -> if (a != null && a.size >= 6) g2.fillRoundRect(a[0], a[1], a[2], a[3], a[4], a[5])
        }
    }

    private companion object {
        const val ACT_SET_FONT = 0
        const val ACT_SET_COLOR = 1
        const val ACT_SET_STROKE_WIDTH = 2
        const val ACT_SET_STROKE = 3
        const val ACT_TRANSLATE = 4
        const val ACT_SCALE = 5
        const val ACT_ROTATE = 6
        const val ACT_RESET = 7
        const val ACT_DRAW_CHAR = 8
        const val ACT_DRAW_TEXT = 9
        const val ACT_DRAW_LINE = 10
        const val ACT_DRAW_RECT = 11
        const val ACT_FILL_RECT = 12
        const val ACT_DRAW_ROUND_RECT = 13
        const val ACT_FILL_ROUND_RECT = 14
    }
}
