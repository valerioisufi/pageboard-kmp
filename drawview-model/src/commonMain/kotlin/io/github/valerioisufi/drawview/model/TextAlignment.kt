package io.github.valerioisufi.drawview.model

import kotlinx.serialization.Serializable

/** Where the lines of a text go in the width of its box. */
@Serializable
public enum class TextAlignment {
    START,
    CENTER,
    END;

    public companion object {
        public fun fromName(name: String?): TextAlignment? = entries.firstOrNull { it.name == name }
    }
}
