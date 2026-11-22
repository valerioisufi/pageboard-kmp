package io.github.valerioisufi.pageboard.ui.theme

import io.github.valerioisufi.pageboard.platform.InternalPageboardApi
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.about_dialog_title
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_close
import io.github.valerioisufi.pageboard.ui.generated.resources.date_months_short
import io.github.valerioisufi.pageboard.ui.generated.resources.name_error_empty
import io.github.valerioisufi.pageboard.ui.generated.resources.name_error_exists
import io.github.valerioisufi.pageboard.ui.generated.resources.name_error_invalid_chars
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource

/**
 * The resources of `pageboard-ui` that the other modules of Pageboard show too, so that their
 * translations stay in one place; the other resources of the module are its own.
 */
@InternalPageboardApi
public object PageboardStrings {
    /** "Close", on a dialog or a panel. */
    public val close: StringResource get() = Res.string.common_action_close

    /** The title of the dialog about the application. */
    public val aboutTitle: StringResource get() = Res.string.about_dialog_title

    /** A name taken by another item of its folder. */
    public val nameExists: StringResource get() = Res.string.name_error_exists

    /** A name left empty. */
    public val nameEmpty: StringResource get() = Res.string.name_error_empty

    /** A name holding a separator of paths. */
    public val nameInvalidChars: StringResource get() = Res.string.name_error_invalid_chars

    /** The short names of the months, January first. */
    public val monthsShort: StringArrayResource get() = Res.array.date_months_short
}
