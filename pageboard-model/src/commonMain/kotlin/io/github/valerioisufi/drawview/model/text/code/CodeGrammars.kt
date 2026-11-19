package io.github.valerioisufi.drawview.model.text.code

/** The grammars of [BuiltInCodeHighlighter], by the names and aliases of their languages. */
internal object CodeGrammars {

    private fun words(text: String): Set<String> = text.split(' ', '\n').filter { it.isNotEmpty() }.toSet()

    private val C_COMMENTS = listOf("//")
    private val C_BLOCK = "/*" to "*/"
    private val DOUBLE = Quote("\"")

    private val C_KEYWORDS = words(
        "auto break case const continue default do else enum extern for goto if inline register restrict return " +
            "sizeof static struct switch typedef union volatile while _Alignas _Alignof _Atomic _Generic _Noreturn " +
            "_Static_assert _Thread_local"
    )
    private val C_TYPES = words(
        "char double float int long short signed unsigned void bool _Bool _Complex size_t ssize_t ptrdiff_t " +
            "int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t intptr_t uintptr_t FILE"
    )

    val kotlin = CodeGrammar(
        keywords = words(
            "as break class continue do else for fun if in interface is object package return super this throw try " +
                "typealias typeof val var when while by catch constructor delegate dynamic field file finally get import " +
                "init param property receiver set setparam where actual abstract annotation companion const crossinline " +
                "data enum expect external final infix inline inner internal lateinit noinline open operator out override " +
                "private protected public reified sealed suspend tailrec vararg value"
        ),
        literals = words("true false null"),
        capitalizedTypes = true,
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        nestedComments = true,
        quotes = listOf(Quote("\"\"\"", escapes = false, multiline = true), DOUBLE),
        charLiterals = true,
        annotations = true
    )

    val java = CodeGrammar(
        keywords = words(
            "abstract assert break case catch class const continue default do else enum extends final finally for goto " +
                "if implements import instanceof interface native new package private protected public return static " +
                "strictfp super switch synchronized this throw throws transient try volatile while var record sealed " +
                "permits yield non-sealed"
        ),
        types = words("boolean byte char double float int long short void"),
        literals = words("true false null"),
        capitalizedTypes = true,
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        quotes = listOf(Quote("\"\"\"", multiline = true), DOUBLE),
        charLiterals = true,
        annotations = true
    )

    val c = CodeGrammar(
        keywords = C_KEYWORDS,
        types = C_TYPES,
        literals = words("NULL true false"),
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        quotes = listOf(DOUBLE),
        stringPrefixes = words("u8 u l"),
        charLiterals = true,
        preprocessor = true
    )

    val cpp = CodeGrammar(
        keywords = C_KEYWORDS + words(
            "alignas alignof and and_eq asm bitand bitor catch class compl concept consteval constexpr constinit " +
                "const_cast co_await co_return co_yield decltype delete dynamic_cast explicit export final friend mutable " +
                "namespace new noexcept not not_eq operator or or_eq override private protected public reinterpret_cast " +
                "requires static_assert static_cast template this thread_local throw try typeid typename using virtual " +
                "xor xor_eq"
        ),
        types = C_TYPES + words("wchar_t char8_t char16_t char32_t"),
        literals = words("true false nullptr NULL"),
        capitalizedTypes = true,
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        quotes = listOf(DOUBLE),
        stringPrefixes = words("u8 u l"),
        charLiterals = true,
        preprocessor = true,
        cppRawStrings = true
    )

    val csharp = CodeGrammar(
        keywords = words(
            "abstract as base break case catch checked class const continue default delegate do else enum event " +
                "explicit extern finally fixed for foreach goto if implicit in interface internal is lock namespace new " +
                "operator out override params private protected public readonly ref return sealed sizeof stackalloc " +
                "static struct switch this throw try typeof unchecked unsafe using virtual volatile while async await var " +
                "get set init record yield where when nameof"
        ),
        types = words("bool byte char decimal double float int long object sbyte short string uint ulong ushort void dynamic"),
        literals = words("true false null"),
        capitalizedTypes = true,
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        quotes = listOf(
            Quote("\$\"\"\"", "\"\"\"", escapes = false, multiline = true),
            Quote("\"\"\"", escapes = false, multiline = true),
            Quote("\$@\"", "\"", escapes = false, doubled = true, multiline = true),
            Quote("@\$\"", "\"", escapes = false, doubled = true, multiline = true),
            Quote("@\"", "\"", escapes = false, doubled = true, multiline = true),
            Quote("\$\"", "\""),
            DOUBLE
        ),
        charLiterals = true,
        preprocessor = true
    )

    private val JS_KEYWORDS = words(
        "break case catch class const continue debugger default delete do else export extends finally for function " +
            "if import in instanceof let new return super switch this throw try typeof var void while with yield async " +
            "await of static get set from as"
    )
    private val JS_QUOTES = listOf(DOUBLE, Quote("'"), Quote("`", multiline = true))

    val javascript = CodeGrammar(
        keywords = JS_KEYWORDS,
        literals = words("true false null undefined NaN Infinity"),
        capitalizedTypes = true,
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        quotes = JS_QUOTES,
        annotations = true,
        dollarInNames = true
    )

    val typescript = CodeGrammar(
        keywords = JS_KEYWORDS + words(
            "abstract asserts declare enum implements infer interface is keyof module namespace private protected " +
                "public readonly require satisfies type unique override"
        ),
        types = words("any bigint boolean never number object string symbol unknown void"),
        literals = words("true false null undefined NaN Infinity"),
        capitalizedTypes = true,
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        quotes = JS_QUOTES,
        annotations = true,
        dollarInNames = true
    )

    val swift = CodeGrammar(
        keywords = words(
            "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator " +
                "private precedencegroup protocol public rethrows static struct subscript typealias var break case catch " +
                "continue default defer do else fallthrough for guard if in repeat return throw switch where while as " +
                "await async is throws try super self Self some any convenience dynamic final lazy mutating nonmutating " +
                "optional override required unowned weak willSet didSet get set"
        ),
        literals = words("true false nil"),
        capitalizedTypes = true,
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        nestedComments = true,
        quotes = listOf(Quote("\"\"\"", multiline = true), DOUBLE),
        annotations = true,
        swiftRawStrings = true
    )

    val go = CodeGrammar(
        keywords = words(
            "break case chan const continue default defer else fallthrough for func go goto if import interface map " +
                "package range return select struct switch type var"
        ),
        types = words(
            "any bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 " +
                "uint16 uint32 uint64 uintptr"
        ),
        literals = words("true false nil iota"),
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        quotes = listOf(DOUBLE, Quote("`", escapes = false, multiline = true)),
        charLiterals = true
    )

    val rust = CodeGrammar(
        keywords = words(
            "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move " +
                "mut pub ref return self Self static struct super trait type union unsafe use where while yield macro_rules"
        ),
        types = words("bool char str u8 u16 u32 u64 u128 usize i8 i16 i32 i64 i128 isize f32 f64"),
        literals = words("true false"),
        capitalizedTypes = true,
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        nestedComments = true,
        quotes = listOf(Quote("\"", multiline = true)),
        stringPrefixes = words("b"),
        charLiterals = true,
        lifetimes = true,
        attributes = true,
        rustRawStrings = true,
        macros = true
    )

    val python = CodeGrammar(
        keywords = words(
            "and as assert async await break class continue def del elif else except finally for from global if import " +
                "in is lambda nonlocal not or pass raise return try while with yield match case"
        ),
        types = words("int float complex str bytes bytearray bool list dict set frozenset tuple object type"),
        literals = words("True False None"),
        capitalizedTypes = true,
        lineComments = listOf("#"),
        quotes = listOf(Quote("\"\"\"", multiline = true), Quote("'''", multiline = true), DOUBLE, Quote("'")),
        stringPrefixes = words("r u b f br rb fr rf"),
        annotations = true
    )

    val shell = CodeGrammar(
        keywords = words(
            "if then else elif fi case esac for select while until do done in function time coproc return exit break " +
                "continue export local readonly declare typeset unset shift source alias set"
        ),
        functions = words("echo printf cd pwd read test eval exec trap wait kill"),
        literals = words("true false"),
        hashComments = true,
        quotes = listOf(Quote("\"", multiline = true), Quote("'", escapes = false, multiline = true)),
        variables = true
    )

    val sql = CodeGrammar(
        keywords = words(
            "select from where and or not insert into values update set delete create table drop alter add column " +
                "primary key foreign references index view join inner left right full outer cross on group by order " +
                "having limit offset as distinct union all exists in is like between case when then else end begin " +
                "commit rollback transaction default unique check constraint with returning if replace asc desc cascade " +
                "grant revoke"
        ),
        types = words(
            "int integer bigint smallint decimal numeric real float double precision varchar char text boolean date time " +
                "timestamp blob serial uuid json jsonb"
        ),
        literals = words("true false null"),
        caseSensitive = false,
        lineComments = listOf("--"),
        blockComment = C_BLOCK,
        quotes = listOf(Quote("'", escapes = false, doubled = true, multiline = true), Quote("\"", escapes = false, doubled = true))
    )

    val json = CodeGrammar(
        keywords = emptySet(),
        literals = words("true false null"),
        calls = false,
        lineComments = C_COMMENTS,
        blockComment = C_BLOCK,
        quotes = listOf(DOUBLE),
        keys = true
    )

    val yaml = CodeGrammar(
        keywords = emptySet(),
        literals = words("true false null yes no on off"),
        caseSensitive = false,
        calls = false,
        hashComments = true,
        quotes = listOf(DOUBLE, Quote("'", escapes = false, doubled = true)),
        keys = true,
        plainKeys = true,
        nodes = true
    )

    /** The grammars by the names and aliases of their languages, in lowercase. */
    val byName: Map<String, CodeGrammar> = buildMap {
        fun name(grammar: CodeGrammar, names: String) = words(names).forEach { put(it, grammar) }
        name(kotlin, "kotlin kt kts")
        name(java, "java")
        name(c, "c h")
        name(cpp, "cpp c++ cc cxx hpp hh hxx")
        name(csharp, "csharp cs c#")
        name(javascript, "javascript js jsx mjs cjs")
        name(typescript, "typescript ts tsx mts cts")
        name(swift, "swift")
        name(go, "go golang")
        name(rust, "rust rs")
        name(python, "python py py3 python3")
        name(shell, "shell sh bash zsh ksh")
        name(sql, "sql postgresql postgres mysql sqlite")
        name(json, "json jsonc json5")
        name(yaml, "yaml yml")
    }
}
