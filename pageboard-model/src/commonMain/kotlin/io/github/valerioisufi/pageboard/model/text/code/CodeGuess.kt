package io.github.valerioisufi.pageboard.model.text.code

/**
 * The language a code with none written looks like (see `docs/plans/code-blocks-more.md`): each
 * language of [CodeGrammars] has the marks it is known by (`fun name(` in Kotlin, `#include <…h>`
 * in C, `def name(` in Python…), each worth some points; the language with the most wins, when it
 * has enough and clearly more than the next. Only the first [READ] characters are read, by
 * patterns with no nested repetition: the time is bounded whatever the code.
 *
 * A guess is only shown (the colors of a block): what was written stays as it is.
 */
internal object CodeGuess {

    /** A mark of the languages of [points]: each time [pattern] is found (up to [MOST] times), the points of each language. */
    private class Mark(pattern: String, val points: Map<String, Float>) {
        val regex = Regex(pattern, RegexOption.MULTILINE)
    }

    private fun mark(pattern: String, vararg points: Pair<String, Float>) = Mark(pattern, points.toMap())

    private val MARKS = listOf(
        // Kotlin
        mark("""\bfun\s+(<[^>\n]*>\s*)?[\w.]+\s*\(""", "kotlin" to 4f),
        mark("""\b(val|var)\s+\w+\s*[:=]""", "kotlin" to 2f, "swift" to 0.5f),
        mark("""\bdata\s+class\b|\bcompanion\s+object\b|\bsealed\s+(class|interface)\b""", "kotlin" to 4f),
        mark("""!!|\?:""", "kotlin" to 1f),
        mark("""\bwhen\s*(\(|\{)""", "kotlin" to 2f),
        // Java
        mark("""\bpublic\s+(static\s+)?(final\s+)?(class|void|interface|enum)\b""", "java" to 3f, "csharp" to 1.5f),
        mark("""System\.(out|err)\.print""", "java" to 5f),
        mark("""^\s*import\s+java[x]?\.""", "java" to 5f),
        mark("""@Override\b""", "java" to 3f),
        // C and C++
        mark("""^\s*#include\s*<\w+\.h>""", "c" to 5f, "cpp" to 2f),
        mark("""^\s*#include\s*<\w+>""", "cpp" to 5f),
        mark("""^\s*#include\s*"[\w./]+\.h"""", "c" to 3f, "cpp" to 2f),
        mark("""\bprintf\s*\(|\bmalloc\s*\(|\bsizeof\s*\(""", "c" to 2f, "cpp" to 1f),
        mark("""\bint\s+main\s*\(""", "c" to 2f, "cpp" to 2f),
        mark("""\bstd::|\bcout\s*<<|\btemplate\s*<""", "cpp" to 5f),
        // C#
        mark("""^\s*using\s+System\b""", "csharp" to 6f),
        mark("""\bConsole\.Write""", "csharp" to 5f),
        mark("""\bnamespace\s+[\w.]+""", "csharp" to 1.5f, "cpp" to 1.5f),
        mark("""\basync\s+Task\b|\{\s*get;\s*set;\s*\}""", "csharp" to 4f),
        // JavaScript and TypeScript
        mark("""\bfunction\s*\w*\s*\(""", "javascript" to 3f),
        mark("""\b(const|let)\s+\w+\s*=""", "javascript" to 2f, "swift" to 0.5f),
        mark("""=>""", "javascript" to 1f),
        mark("""\bconsole\.(log|error|warn)\s*\(""", "javascript" to 4f),
        mark("""\brequire\s*\(\s*['"]|\bmodule\.exports\b|\bdocument\.\w+""", "javascript" to 4f),
        mark("""^\s*import\s+[^\n]*\bfrom\s+['"]|^\s*export\s+(default|const|function|class)\b""", "javascript" to 2f),
        mark(""":\s*(string|number|boolean|any|unknown|void)\b""", "typescript" to 4f),
        mark("""^\s*(export\s+)?(interface|type)\s+\w+(<[^>\n]*>)?\s*(=|\{)""", "typescript" to 3f),
        // Swift
        mark("""\bfunc\s+\w+\s*(<[^>\n]*>)?\s*\(""", "swift" to 3f, "go" to 2f),
        mark("""^\s*import\s+(UIKit|Foundation|SwiftUI|Combine)\b""", "swift" to 6f),
        mark("""\bguard\s+let\b|\bif\s+let\b|@State\b|\bstruct\s+\w+\s*:\s*View\b""", "swift" to 4f),
        // Go
        mark("""^\s*package\s+\w+\s*$""", "go" to 3f, "kotlin" to 1f),
        mark("""\bfmt\.\w+\s*\(""", "go" to 5f),
        mark(""":=""", "go" to 2f),
        mark("""\bfunc\s*\(\s*\w+\s+\*?\w+\s*\)""", "go" to 4f),
        mark("""^\s*import\s+\(""", "go" to 3f),
        // Rust
        mark("""\bfn\s+\w+\s*[<(]""", "rust" to 4f),
        mark("""\blet\s+mut\b|\bimpl\b|\bpub\s+(fn|struct|enum)\b""", "rust" to 3f),
        mark("""\b(println|vec|format)!\s*[(\[]|^\s*use\s+(std|crate)::""", "rust" to 5f),
        // Python
        mark("""^\s*def\s+\w+\s*\(""", "python" to 5f),
        mark("""^\s*from\s+[\w.]+\s+import\b""", "python" to 5f),
        mark("""^\s*import\s+[\w.]+(\s+as\s+\w+)?\s*$""", "python" to 2f),
        mark("""\bself\.\w+|\belif\b|__\w+__""", "python" to 4f),
        mark("""^\s*(if|for|while|with|class|try|else|except|finally)\b[^{};\n]*:\s*$""", "python" to 3f),
        mark("""\bprint\s*\(""", "python" to 1f),
        // The shell
        mark("""^#!\S*\b(ba|z|k|da)?sh\b""", "shell" to 12f),
        mark("""^\s*(\$\s+)?(sudo|apt|apt-get|brew|npm|npx|yarn|pnpm|pip|pip3|git|cd|ls|mkdir|rm|cp|mv|curl|wget|export|echo|chmod|docker|kubectl|cargo|ssh|source|cat|grep)\s""", "shell" to 2.5f),
        mark("""^\s*(fi|done|esac)\s*$|\bthen\s*$""", "shell" to 4f),
        mark("""^\s*\$\s+\w""", "shell" to 3f),
        // SQL
        mark("""(?i)^\s*(select\s|insert\s+into\b|update\s+\w+\s+set\b|delete\s+from\b|create\s+(table|index|view)\b|alter\s+table\b|drop\s+table\b)""", "sql" to 6f),
        mark("""(?i)\b(inner\s+join|left\s+join|group\s+by|order\s+by|primary\s+key)\b""", "sql" to 3f),
        // YAML
        mark("""^---\s*$""", "yaml" to 3f),
        mark("""^\s*[\w.-]+:\s+[^\s{(\[][^;{}\n]*$""", "yaml" to 1.5f),
        mark("""^\s*[\w.-]+:\s*$""", "yaml" to 1f),
        mark("""^\s*-\s+[\w.-]+:\s""", "yaml" to 2f)
    )

    /**
     * The language [code] looks like, by its name in [CodeGrammars] (`kotlin`, `python`…), or `null`
     * when it looks like none clearly enough.
     */
    fun guess(code: String): String? {
        val read = if (code.length > READ) code.substring(0, READ) else code
        if (read.isBlank()) return null
        val points = HashMap<String, Float>()
        for (mark in MARKS) {
            val found = mark.regex.findAll(read).take(MOST).count()
            if (found == 0) continue
            for ((language, worth) in mark.points) points[language] = (points[language] ?: 0f) + found * worth
        }
        json(read)?.let { points["json"] = it }
        // TypeScript is JavaScript with types: its own marks, then all of those of JavaScript
        points["typescript"]?.let { points["typescript"] = it + (points["javascript"] ?: 0f) }
        val ranked = points.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val next = ranked.getOrNull(1)?.value ?: 0f
        return best.key.takeIf { best.value >= ENOUGH && best.value >= next * LEAD }
    }

    /** The points of JSON for [code]: an object or an array, with a quoted key, and nothing of a program. */
    private fun json(code: String): Float? {
        val trimmed = code.trim()
        val opens = trimmed.firstOrNull()
        if (opens != '{' && opens != '[') return null
        if (!QUOTED_KEY.containsMatchIn(trimmed) && opens == '{') return null
        if (';' in trimmed || "=>" in trimmed || "function" in trimmed) return null
        return JSON_POINTS
    }

    private val QUOTED_KEY = Regex(""""[^"\n]*"\s*:""")

    /** The characters read: what a block shows of its language is in its first lines. */
    private const val READ = 4096

    /** The times a mark counts at most: one mark repeated is not a language. */
    private const val MOST = 5

    /** The points a language needs, and how many times those of the next it needs. */
    private const val ENOUGH = 4f
    private const val LEAD = 1.5f
    private const val JSON_POINTS = 10f
}
