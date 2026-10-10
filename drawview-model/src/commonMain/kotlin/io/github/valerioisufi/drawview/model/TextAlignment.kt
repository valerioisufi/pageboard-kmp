package io.github.valerioisufi.drawview.model

import kotlinx.serialization.Serializable

/** Where the lines of a text go in the width of its box. */
@Serializable
enum class TextAlignment {
    START,
    CENTER,
    END;

    companion object {
        fun fromName(name: String?): TextAlignment? = entries.firstOrNull { it.name == name }
    }
}
