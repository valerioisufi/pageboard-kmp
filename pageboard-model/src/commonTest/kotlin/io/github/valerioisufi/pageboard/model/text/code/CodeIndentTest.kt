package io.github.valerioisufi.pageboard.model.text.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The openers, the closers and the step of indentation of the code of a block in its language. */
class CodeIndentTest {

    @Test
    fun aLineEndingWithABracketOpensABlockInTheLanguagesOfBraces() {
        assertEquals('{', CodeIndent.openerAtEnd("fun main() {", "kotlin"))
        assertEquals('{', CodeIndent.openerAtEnd("if (a) {  ", "java"), "spaces after it")
        assertEquals('(', CodeIndent.openerAtEnd("val x = listOf(", "kt"), "an alias of the language")
        assertEquals('[', CodeIndent.openerAtEnd("const a = [", "JavaScript"), "the name in any case")
        assertNull(CodeIndent.openerAtEnd("val x = 1", "kotlin"))
        assertNull(CodeIndent.openerAtEnd("x {", ""), "no language, no openers")
        assertNull(CodeIndent.openerAtEnd("x {", "brainfuck"), "a language not known")
    }

    @Test
    fun anOpenerInACommentOrAStringOpensNothing() {
        assertNull(CodeIndent.openerAtEnd("val a = 1 // {", "kotlin"))
        assertNull(CodeIndent.openerAtEnd("val a = \"(", "kotlin"), "a string not closed")
        assertNull(CodeIndent.openerAtEnd("/* a\n b {", "c"), "a comment from a line before")
        assertEquals('{', CodeIndent.openerAtEnd("/* a */\nint f() {", "c"), "after a comment closed")
    }

    @Test
    fun aColonOpensABlockInPythonAndYaml() {
        assertEquals(':', CodeIndent.openerAtEnd("def f(x):", "python"))
        assertEquals(':', CodeIndent.openerAtEnd("server:", "yaml"))
        assertNull(CodeIndent.openerAtEnd("x = 1  # why:", "python"), "a colon in a comment")
        assertNull(CodeIndent.openerAtEnd("val a: Int", "kotlin"))
        assertNull(CodeIndent.openerAtEnd("label:", "kotlin"), "not in Kotlin")
        assertNull(CodeIndent.closerOf(':'), "closed by the indentation alone")
    }

    @Test
    fun theClosersAreThoseOfTheOpenersOfTheLanguage() {
        assertTrue(CodeIndent.closes('}', "kotlin"))
        assertTrue(CodeIndent.closes(')', "python"))
        assertFalse(CodeIndent.closes('}', "sql"), "SQL opens with parentheses only")
        assertFalse(CodeIndent.closes('}', ""))
    }

    @Test
    fun theStepIsTheLeastIndentationOfTheBlock() {
        assertEquals("  ", CodeIndent.stepOf("fun f() {\n  val a = 1\n  if (a) {\n    b()\n  }\n}", "kotlin"))
        assertEquals("    ", CodeIndent.stepOf("fun f() {\n}", "kotlin"), "none shown: four spaces")
        assertEquals("  ", CodeIndent.stepOf("a:", "yaml"), "two in YAML")
        assertEquals("\t", CodeIndent.stepOf("f() {\n\tg()\n}", "c"), "a tab")
        assertEquals("  ", CodeIndent.stepOf("   f() {\n     g()\n   }", "c", base = 3), "under a fence indented in a list item")
        assertEquals("    ", CodeIndent.stepOf("   f() {\n   }", "c", base = 3), "the spaces of the fence are no step")
    }
}
