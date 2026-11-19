package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.DrawElement
import io.github.valerioisufi.pageboard.model.DrawPage
import io.github.valerioisufi.pageboard.model.DrawText

/**
 * What a model read of this element, to tell later whether it changed: the stamps of its presence,
 * place, order and content (see [DrawElement]). Opaque to the client.
 *
 * A text answers the base of its content instead ([DrawText.base]): a text is changed by what is
 * written in it, and a proposal on its words should not come out stale because its box was moved.
 */
internal fun DrawElement.elementBase(): String = when (this) {
    is DrawText -> base()
    else -> "e" + fnv1a("$added|$removed|$placed|$ordered|$updated").toString(16)
}

/**
 * What a model read of this page, to tell later whether it changed: the stamps of its presence,
 * position and format, and the versions of what is on it. Opaque to the client.
 */
internal fun DrawPage.pageBase(): String =
    "p" + fnv1a("$added|$removed|$orderVersion|$formatVersion|${contentVersions.stamps.entries.sortedBy { it.key }}").toString(16)
