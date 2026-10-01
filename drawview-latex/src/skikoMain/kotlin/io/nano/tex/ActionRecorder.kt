package io.nano.tex

class ActionRecorder {
    companion object {
        const val ACT_setFont: Byte = 0
        const val ACT_setColor: Byte = 1
        const val ACT_setStrokeWidth: Byte = 2
        const val ACT_setStroke: Byte = 3
        const val ACT_translate: Byte = 4
        const val ACT_scale: Byte = 5
        const val ACT_rotate: Byte = 6
        const val ACT_reset: Byte = 7
        const val ACT_drawChar: Byte = 8
        const val ACT_drawText: Byte = 9
        const val ACT_drawLine: Byte = 10
        const val ACT_drawRect: Byte = 11
        const val ACT_fillRect: Byte = 12
        const val ACT_drawRoundRect: Byte = 13
        const val ACT_fillRoundRect: Byte = 14
    }

    internal class Action(
        val action: Byte,
        val arg: Any?,
        val args: FloatArray?
    )

    private val actions = mutableListOf<Action>()
    private val origin = Action(ACT_translate, null, floatArrayOf(0f, 0f))

    init {
        actions.add(origin)
    }

    fun setPosition(x: Float, y: Float) {
        origin.args?.let {
            it[0] = x
            it[1] = y
        }
    }

    fun record(action: Int, arg: Any?, args: FloatArray?) {
        actions.add(Action(action.toByte(), arg, args))
    }

    fun play(g2: Graphics2D) {
        for (act in actions) {
            play(g2, act)
        }
    }

    private fun play(g2: Graphics2D, act: Action) {
        when (act.action) {
            ACT_setFont -> g2.setFont(act.arg as? Font)
            ACT_setColor -> act.args?.get(0)?.let { g2.setColor(it.toInt()) }
            ACT_setStrokeWidth -> act.args?.get(0)?.let { g2.setStrokeWidth(it) }
            ACT_setStroke -> act.args?.let {
                if (it.size >= 4) {
                    g2.setStroke(it[0], it[1], it[2].toInt(), it[3].toInt())
                }
            }
            ACT_translate -> act.args?.let {
                if (it.size >= 2) g2.translate(it[0], it[1])
            }
            ACT_scale -> act.args?.let {
                if (it.size >= 2) g2.scale(it[0], it[1])
            }
            ACT_rotate -> act.args?.let {
                if (it.size >= 3) g2.rotate(it[0], it[1], it[2])
            }
            ACT_reset -> g2.reset()
            ACT_drawChar -> act.args?.let {
                if (it.size >= 3) g2.drawChar(it[0].toInt().toChar(), it[1], it[2])
            }
            ACT_drawText -> {
                val txt = act.arg as? String ?: ""
                act.args?.let {
                    if (it.size >= 2) g2.drawText(txt, it[0], it[1])
                }
            }
            ACT_drawLine -> act.args?.let {
                if (it.size >= 4) g2.drawLine(it[0], it[1], it[2], it[3])
            }
            ACT_drawRect -> act.args?.let {
                if (it.size >= 4) g2.drawRect(it[0], it[1], it[2], it[3])
            }
            ACT_fillRect -> act.args?.let {
                if (it.size >= 4) g2.fillRect(it[0], it[1], it[2], it[3])
            }
            ACT_drawRoundRect -> act.args?.let {
                if (it.size >= 6) g2.drawRoundRect(it[0], it[1], it[2], it[3], it[4], it[5])
            }
            ACT_fillRoundRect -> act.args?.let {
                if (it.size >= 6) g2.fillRoundRect(it[0], it[1], it[2], it[3], it[4], it[5])
            }
        }
    }
}
