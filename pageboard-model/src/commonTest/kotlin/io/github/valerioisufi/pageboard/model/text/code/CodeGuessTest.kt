package io.github.valerioisufi.pageboard.model.text.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The language a code with none written looks like. */
class CodeGuessTest {

    @Test
    fun theLanguagesAreToldApartByTheirMarks() {
        assertEquals("kotlin", CodeGuess.guess("fun main() {\n    val name = \"x\"\n    println(name)\n}"))
        assertEquals("java", CodeGuess.guess("public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"hi\");\n    }\n}"))
        assertEquals("python", CodeGuess.guess("def area(r):\n    if r < 0:\n        return None\n    return 3.14 * r * r"))
        assertEquals("c", CodeGuess.guess("#include <stdio.h>\n\nint main(void) {\n    printf(\"hi\\n\");\n}"))
        assertEquals("cpp", CodeGuess.guess("#include <iostream>\n\nint main() {\n    std::cout << \"hi\";\n}"))
        assertEquals("csharp", CodeGuess.guess("using System;\n\nclass A {\n    static void Main() { Console.WriteLine(\"hi\"); }\n}"))
        assertEquals("javascript", CodeGuess.guess("const add = (a, b) => a + b;\nconsole.log(add(1, 2));"))
        assertEquals("typescript", CodeGuess.guess("interface User {\n  name: string;\n}\nconst add = (a: number, b: number): number => a + b;"))
        assertEquals("swift", CodeGuess.guess("import SwiftUI\n\nstruct ContentView: View {\n    var body: some View { Text(\"hi\") }\n}"))
        assertEquals("go", CodeGuess.guess("package main\n\nimport \"fmt\"\n\nfunc main() {\n\tx := 1\n\tfmt.Println(x)\n}"))
        assertEquals("rust", CodeGuess.guess("fn main() {\n    let mut v = vec![1, 2];\n    println!(\"{:?}\", v);\n}"))
        assertEquals("shell", CodeGuess.guess("#!/bin/bash\nfor f in *.txt; do\n  echo \$f\ndone"))
        assertEquals("shell", CodeGuess.guess("brew install git\ngit clone https://example.org/a.git\ncd a"))
        assertEquals("sql", CodeGuess.guess("SELECT name, age\nFROM people\nWHERE age > 18\nORDER BY name;"))
        assertEquals("json", CodeGuess.guess("{\n  \"name\": \"a\",\n  \"tags\": [1, 2]\n}"))
        assertEquals("yaml", CodeGuess.guess("server:\n  port: 8080\n  host: localhost\nnames:\n  - a\n  - b"))
    }

    @Test
    fun aCodeLikeNoneIsNotGuessed() {
        assertNull(CodeGuess.guess(""))
        assertNull(CodeGuess.guess("hello world"))
        assertNull(CodeGuess.guess("x = 1"), "too little to tell")
    }

    @Test
    fun aLongCodeIsReadOnlyFromItsStart() {
        val code = "def f(x):\n    return x\n" + "y".repeat(1_000_000)
        assertEquals("python", CodeGuess.guess(code))
    }
}
