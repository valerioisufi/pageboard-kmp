package io.github.valerioisufi.pageboard.model.text.code

import io.github.valerioisufi.pageboard.model.text.code.CodeTokenKind.ANNOTATION
import io.github.valerioisufi.pageboard.model.text.code.CodeTokenKind.COMMENT
import io.github.valerioisufi.pageboard.model.text.code.CodeTokenKind.FUNCTION
import io.github.valerioisufi.pageboard.model.text.code.CodeTokenKind.KEYWORD
import io.github.valerioisufi.pageboard.model.text.code.CodeTokenKind.LITERAL
import io.github.valerioisufi.pageboard.model.text.code.CodeTokenKind.NUMBER
import io.github.valerioisufi.pageboard.model.text.code.CodeTokenKind.PROPERTY
import io.github.valerioisufi.pageboard.model.text.code.CodeTokenKind.STRING
import io.github.valerioisufi.pageboard.model.text.code.CodeTokenKind.TYPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tokens of the languages of [BuiltInCodeHighlighter], on code as it is written.
 */
class CodeHighlighterTest {

    /** The tokens of [code] in [language], with their text. */
    private fun tokens(code: String, language: String) =
        BuiltInCodeHighlighter.tokens(code, language).map { it.kind to code.substring(it.start, it.end) }

    @Test
    fun kotlin() {
        val code = "@JvmStatic\nfun main(args: Array<String>) {\n    val name = \"World\" // greet\n    println('!' + 0x1F)\n    return null\n}"
        assertEquals(
            listOf(
                ANNOTATION to "@JvmStatic", KEYWORD to "fun", FUNCTION to "main", TYPE to "Array", TYPE to "String",
                KEYWORD to "val", STRING to "\"World\"", COMMENT to "// greet", FUNCTION to "println", STRING to "'!'",
                NUMBER to "0x1F", KEYWORD to "return", LITERAL to "null"
            ),
            tokens(code, "kotlin")
        )
        val raw = "/* a /* b */ c */ val s = \"\"\"x\\n\"\"\""
        assertEquals(listOf(COMMENT to "/* a /* b */ c */", KEYWORD to "val", STRING to "\"\"\"x\\n\"\"\""), tokens(raw, "kt"), "comments nest; raw strings")
    }

    @Test
    fun java() {
        val code = "public static void main(String[] args) { char c = '\\''; long n = 1_000L; String t = \"\"\"\n  text\n  \"\"\"; }"
        assertEquals(
            listOf(
                KEYWORD to "public", KEYWORD to "static", TYPE to "void", FUNCTION to "main", TYPE to "String",
                TYPE to "char", STRING to "'\\''", TYPE to "long", NUMBER to "1_000L", TYPE to "String",
                STRING to "\"\"\"\n  text\n  \"\"\""
            ),
            tokens(code, "java")
        )
    }

    @Test
    fun c() {
        val code = "#include <stdio.h>\nint main(void) {\n    printf(\"%d\\n\", NULL); /* done */\n}"
        assertEquals(
            listOf(
                ANNOTATION to "#include", STRING to "<stdio.h>", TYPE to "int", FUNCTION to "main", TYPE to "void",
                FUNCTION to "printf", STRING to "\"%d\\n\"", LITERAL to "NULL", COMMENT to "/* done */"
            ),
            tokens(code, "c")
        )
    }

    @Test
    fun cpp() {
        val code = "auto s = R\"x(a)\" b)x\"; std::vector<int> v = nullptr; auto w = u8\"é\";"
        assertEquals(
            listOf(
                KEYWORD to "auto", STRING to "R\"x(a)\" b)x\"", TYPE to "int", LITERAL to "nullptr", KEYWORD to "auto",
                STRING to "u8\"é\""
            ),
            tokens(code, "c++")
        )
    }

    @Test
    fun csharp() {
        val code = "#region Main\nvar path = @\"C:\\dir\\\"\"file\"\"\"; // x\nConsole.WriteLine(\$\"{path}\");"
        assertEquals(
            listOf(
                ANNOTATION to "#region", TYPE to "Main", KEYWORD to "var", STRING to "@\"C:\\dir\\\"\"file\"\"\"", COMMENT to "// x",
                TYPE to "Console", FUNCTION to "WriteLine", STRING to "\$\"{path}\""
            ),
            tokens(code, "cs")
        )
    }

    @Test
    fun javascriptAndTypescript() {
        val js = "const re = `a \${b}`; export default function f() { return \$el.on(undefined) }"
        assertEquals(
            listOf(
                KEYWORD to "const", STRING to "`a \${b}`", KEYWORD to "export", KEYWORD to "default", KEYWORD to "function",
                FUNCTION to "f", KEYWORD to "return", FUNCTION to "on", LITERAL to "undefined"
            ),
            tokens(js, "js")
        )
        val ts = "interface Point { x: number; label?: string }"
        assertEquals(listOf(KEYWORD to "interface", TYPE to "Point", TYPE to "number", TYPE to "string"), tokens(ts, "typescript"))
    }

    @Test
    fun swift() {
        val code = "let s = #\"raw \"quote\"\"#\n#if DEBUG\n@MainActor func f() -> Int { nil }"
        assertEquals(
            listOf(
                KEYWORD to "let", STRING to "#\"raw \"quote\"\"#", ANNOTATION to "#if", ANNOTATION to "@MainActor",
                KEYWORD to "func", FUNCTION to "f", TYPE to "Int", LITERAL to "nil"
            ),
            tokens(code, "swift")
        )
    }

    @Test
    fun go() {
        val code = "func main() {\n\ts := `raw\\n`\n\tvar r rune = 'x'\n\treturn nil\n}"
        assertEquals(
            listOf(
                KEYWORD to "func", FUNCTION to "main", STRING to "`raw\\n`", KEYWORD to "var", TYPE to "rune", STRING to "'x'",
                KEYWORD to "return", LITERAL to "nil"
            ),
            tokens(code, "go")
        )
    }

    @Test
    fun rust() {
        val code = "#[derive(Debug)]\nfn longest<'a>(x: &'a str) -> String {\n    println!(\"{}\", r#\"raw \"x\"\"#);\n    let c = 'c';\n}"
        assertEquals(
            listOf(
                ANNOTATION to "#[derive(Debug)]", KEYWORD to "fn", ANNOTATION to "'a", ANNOTATION to "'a", TYPE to "str",
                TYPE to "String", FUNCTION to "println!", STRING to "\"{}\"", STRING to "r#\"raw \"x\"\"#", KEYWORD to "let",
                STRING to "'c'"
            ),
            tokens(code, "rs")
        )
    }

    @Test
    fun python() {
        val code = "@app.route(\"/\")\ndef index(name: str = None) -> bool:\n    \"\"\"Doc.\"\"\"\n    return f\"hi {name}\" if True else rb'\\d' # done"
        assertEquals(
            listOf(
                ANNOTATION to "@app.route", STRING to "\"/\"", KEYWORD to "def", FUNCTION to "index", TYPE to "str",
                LITERAL to "None", TYPE to "bool", STRING to "\"\"\"Doc.\"\"\"", KEYWORD to "return", STRING to "f\"hi {name}\"",
                KEYWORD to "if", LITERAL to "True", KEYWORD to "else", STRING to "rb'\\d'", COMMENT to "# done"
            ),
            tokens(code, "py")
        )
    }

    @Test
    fun shell() {
        val code = "#!/bin/bash\nfor f in *.txt; do echo \"\$f\" 'raw \$x' \$HOME \$1 \${x:-y}; done # end\nx=a#b"
        assertEquals(
            listOf(
                COMMENT to "#!/bin/bash", KEYWORD to "for", KEYWORD to "in", KEYWORD to "do", FUNCTION to "echo",
                STRING to "\"\$f\"", STRING to "'raw \$x'", PROPERTY to "\$HOME", PROPERTY to "\$1", PROPERTY to "\${x:-y}",
                KEYWORD to "done", COMMENT to "# end"
            ),
            tokens(code, "bash"),
            "a # inside a word is no comment"
        )
        assertEquals(listOf(STRING to "\$'a\\n'"), tokens("\$'a\\n'", "sh"))
    }

    @Test
    fun sql() {
        val code = "SELECT name, COUNT(*) FROM users WHERE note = 'it''s' -- c\n/* b */ AND id IS NOT NULL"
        assertEquals(
            listOf(
                KEYWORD to "SELECT", FUNCTION to "COUNT", KEYWORD to "FROM", KEYWORD to "WHERE", STRING to "'it''s'",
                COMMENT to "-- c", COMMENT to "/* b */", KEYWORD to "AND", KEYWORD to "IS", KEYWORD to "NOT", LITERAL to "NULL"
            ),
            tokens(code, "sql")
        )
    }

    @Test
    fun json() {
        val code = "{\"name\": \"x\", \"n\": -1.5e+3, \"ok\": true, \"list\": [null]}"
        assertEquals(
            listOf(
                PROPERTY to "\"name\"", STRING to "\"x\"", PROPERTY to "\"n\"", NUMBER to "1.5e+3", PROPERTY to "\"ok\"",
                LITERAL to "true", PROPERTY to "\"list\"", LITERAL to "null"
            ),
            tokens(code, "json")
        )
    }

    @Test
    fun yaml() {
        val code = "# config\nname: DrawView\nitems:\n  - id: 1\n    tag: !custom value\n  - &anchor \"quoted\": yes\nurl: http://x.y"
        assertEquals(
            listOf(
                COMMENT to "# config", PROPERTY to "name", PROPERTY to "items", PROPERTY to "id", NUMBER to "1",
                PROPERTY to "tag", ANNOTATION to "!custom", ANNOTATION to "&anchor", PROPERTY to "\"quoted\"",
                LITERAL to "yes", PROPERTY to "url"
            ),
            tokens(code, "yml")
        )
    }

    @Test
    fun aStringNotClosedEndsWithItsLine() {
        assertEquals(listOf(STRING to "\"open", KEYWORD to "val"), tokens("\"open\nval", "kotlin"))
        assertEquals(listOf(STRING to "\"\"\"open\nval"), tokens("\"\"\"open\nval", "kotlin"), "a raw string goes on")
    }

    @Test
    fun theLanguagesAreKnownByTheirNamesAndAliasesInAnyCase() {
        for (name in listOf("Kotlin", "KT", "Py", "c++", "C#", "yml", "golang", "Bash")) {
            assertTrue(tokens("if", name).isNotEmpty() || tokens("1", name).isNotEmpty(), name)
        }
        assertTrue(BuiltInCodeHighlighter.tokens("if (a) return 1", "brainfuck").isEmpty(), "a language not known has no tokens")
        assertTrue(BuiltInCodeHighlighter.tokens("if (a) return 1", "").isEmpty())
    }

    @Test
    fun aLongCodeIsNotHighlighted() {
        val code = "val a = 1\n".repeat(CodeHighlighter.MAX_HIGHLIGHTED / 10 + 1)
        assertTrue(code.length > CodeHighlighter.MAX_HIGHLIGHTED)
        assertTrue(BuiltInCodeHighlighter.tokens(code, "kotlin").isEmpty())
    }
}
