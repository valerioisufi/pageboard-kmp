package io.github.valerioisufi.drawview.search

import kotlin.test.Test
import kotlin.test.assertEquals

/** The text of the notes as it is searched: without the marks of Markdown. */
class SearchTextTest {

    @Test
    fun aTableKeepsTheWordsOfItsCells() {
        val text = "| Limit | Value |\n|---|:-:|\n| **sin x / x** | 1 |"
        assertEquals("Limit Value\n\nsin x / x 1", cleanMarkdownForSearch(text).lines().joinToString("\n") { it.trim().replace(Regex(" +"), " ") })
    }

    @Test
    fun theMarkersOfTheListsGo() {
        assertEquals("one\n  more\ntwo", cleanMarkdownForSearch("- one\n  more\n- two"))
    }
}
