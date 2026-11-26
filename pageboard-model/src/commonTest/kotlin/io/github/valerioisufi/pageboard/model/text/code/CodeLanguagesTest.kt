package io.github.valerioisufi.pageboard.model.text.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The names of the languages completed after the opening fence of a block of code. */
class CodeLanguagesTest {

    private fun match(text: String) = CodeLanguages.match(text.replace("|", ""), text.indexOf('|'))

    @Test
    fun aNameTypedAfterTheFenceIsCompleted() {
        val match = match("Text\n```ko|\ncode\n```")!!
        assertEquals("ko", match.prefix)
        assertEquals(listOf("kotlin"), match.candidates)
        assertEquals("Text\n```kotlin\ncode\n```" to 14, CodeLanguages.apply("Text\n```ko\ncode\n```", match, "kotlin"))
        assertEquals(listOf("java", "javascript", "json"), match("```j|")!!.candidates.take(3), "the usual names first")
        assertEquals("C", match("- a\n  ```C|\n  x\n  ```")!!.prefix, "in a list item, in any case")
    }

    @Test
    fun nothingIsCompletedElsewhere() {
        assertNull(match("```|\ncode\n```"), "nothing typed")
        assertNull(match("```kotlin|\ncode\n```"), "a name complete with nothing longer")
        assertNull(match("```\nko|\n```"), "in the code")
        assertNull(match("ko|"), "out of a block")
        assertNull(match("```kotlin show|\nx\n```"), "a word after the name")
        assertNull(match("```zz|\nx\n```"), "no language starting so")
    }
}
