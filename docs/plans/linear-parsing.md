# Linear parsing of the notation

The texts of the pages are parsed by `RichTextParser` (`drawview-model`, `model.text`) on the
threads of the tiles and, in the editor, at every key on the main thread. The texts come from the
other devices too, and the members are not trusted (`docs/plans/untrusted-members.md`): a text written
to that end must not freeze the application. This document starts from the parser as it is
(2026-10-22), then the design and the plan that make its work linear in the length of the text,
with the same rules and the same results.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

`parse` measured on the JVM, one text repeating a pattern, after a warm-up:

| Text (the pattern repeated) | 16 000 characters | 32 000 | 64 000 | Where the time goes |
|---|---|---|---|---|
| `**a ` (a bold never closed) | 313 ms | 1 211 ms | 4 836 ms | emphasis |
| `*a ` | 483 ms | 1 880 ms | 7 470 ms | emphasis |
| `~~a `, `__a ` | 305 ms | 1 217 ms | 4 864 ms | emphasis |
| `_a ` | 471 ms | 1 887 ms | 7 536 ms | emphasis |
| `$a ` on one line | 120 ms | 205 ms | 818 ms | inline formulas |
| `\*` | 17 ms | 59 ms | 226 ms | nesting |
| `- a⏎` | 8 ms | 26 ms | 103 ms | nesting |
| `# a b⏎` | 4 ms | 13 ms | 56 ms | nesting |
| `$$a$$` on one line | 12 ms | 43 ms | 130 ms | display formulas, nesting |

Twice the text takes four times as long: at 1 MiB the first rows take 20 to 30 minutes, the last
ones from seconds to minutes.

1. **Emphasis.** Five regular expressions (`EMPHASIS`), each with a lazy `[\s\S]+?` between its
   delimiters, run with `findAll` on the text with the code and the formulas masked. From a
   delimiter that never closes, an expression reads to the end of the text, and `findAll` tries
   again from the next character.
2. **Inline formulas.** `closingInline` walks from each `$` to the end of its line when no `$`
   closes it: a line of `$a ` is read again for every `$`.
3. **Display formulas.** `isAloneOnItsLines` reads the whole line of each display formula.
4. **Nesting.** `nest` compares each span with all the spans kept before it: quadratic in the
   number of spans, which grows with the text (each heading, list item, escape, formula or closed
   emphasis is one).
5. **Linear already.** The scan of the code, the escapes and the display formulas (a `$$` reads up
   to the next one), the mask, and the expressions of the headings and the lists (`HEADING`,
   `BULLET_LIST`, `NUMBERED_LIST`): their backtracking stays within a line. Measured at 1 MiB
   without a match, in 26–48 ms: lines of `####### ⇥`, of `    -`, of digits after spaces,
   `1234567` repeated, words. Their spans go through the nesting (4).

## Requirements

1. The work of `parse` linear in the length of the text; the spans sorted in n log n of their
   number.
2. The same rules and the same spans for every text: `RichTextParserTest`, `MarkdownTextUtilTest`
   and `RichTextEditingTest` pass unchanged.
3. The public API unchanged (the ABI of `drawview-model`).
4. Texts of 1 MiB written to that end parsed within a bound that quadratic work cannot meet, on
   the JVM and on the iOS simulator; random texts never make the parser throw.

## Design

### Emphasis

A rule of emphasis is its delimiter (`*`, `_` or `~`, once or twice), the kind of its spans and
whether it is bound to words. What its expression said, said of a position of the masked text:

* a delimiter **opens** at `i` when a character other than a space follows it; a single one also
  neither follows its character nor is followed by it (`(?<!\*)\*(?![\s*])`);
* a delimiter **closes** at `j` when it follows a character other than a space; a single one also
  neither follows its character nor is followed by it (`(?<![\s*])\*(?!\*)`);
* a space is what `\s` matched: space, tab, line feed, vertical tab, form feed, carriage return.

The lazy expression matched from a delimiter that opens to the first one closing after at least one
character of content; `findAll` went on after the match or, when nothing closed, from the next
character. Whether a delimiter closes does not depend on where its span opened: when nothing closes
after a delimiter that opens, nothing closes after the later ones either, and the rule has found all
its spans. The scan of a rule:

1. moves to the next delimiter that opens;
2. looks for the first one closing after it, and stops when there is none;
3. takes the span, unless the rule is bound to words and a letter or a digit touches it, and goes
   on after it.

Step 1 and step 2 read each character at most once each (step 3 jumps over what step 2 read):
linear, five times for the five rules. A span dropped because it touches a word still takes its
text, as with `findAll` (`a__b __c__` has no bold).

### The rest of the parsing (phase 2)

* **Nesting.** The spans sorted as now (by start, the outer one first) go through a stack of the
  spans kept that are still open: the ones ending before the start of a span are popped; the span
  is kept when nothing is open, or when it lies in the content of the top one and that is no code,
  formula or escape. The open spans nest in each other (each in the content of the one under it), so
  a span in the content of the top one is in the content of all of them: the spans kept are the
  same as comparing with every one, in linear time after the sort.
* **Inline formulas.** The walk looking for the `$` closing a formula moves by one character, or by
  two after a backslash: it never jumps over the character after a `$` (that would need a backslash
  in the place of the `$`). So the walk from a `$` passes through the start of the walk from every
  later `$`, and from there on they are the same walk; whether a `$` closes depends on its
  neighbours only. When the walk from a `$` ends without a closing `$` (at a line break), the `$` it
  passed have none either: the parser keeps where the walk ended and gives no formula to the `$`
  before it, without walking again.
* **Display formulas.** A formula alone on its lines: the spaces before and after it are walked up
  to the line break or to another character, rather than reading its whole line.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| A rule of emphasis: its delimiter, where it opens and closes, its spans in a text | `RichTextParser.Emphasis` (private, `drawview-model`) | Information Expert |
| The closing `$` of the inline formulas, the walks done once (phase 2) | `RichTextParser.InlineClosings` (private) | Pure Fabrication |
| The spans kept, nested (phase 2) | `RichTextParser.nest`, with a stack | Information Expert |
| The API the applications see: `parse` and `Span`, unchanged | `RichTextParser` | Protected Variations |

### Tests

`RichTextParserBoundsTest` (`drawview-model`, common), on the JVM and on the iOS simulator:

* texts of 1 MiB parsed within 10 s each (tens of milliseconds when linear on the JVM, a few
  hundred on the iOS simulator, minutes when quadratic):
  in phase 1 the delimiters of every rule never closed (`**`, `*`, `__`, `_`, `~~`) and all of them
  mixed; in phase 2 the closed emphasis, escapes, headings, list items, `$a ` on one line,
  `$$a$$` on one line, and every kind mixed;
* random texts made of the characters of the notation, letters, spaces, line breaks, an emoji and
  a lone surrogate (a fixed seed): the parser never throws, and its spans lie in the text, sorted,
  nested or apart;
* the emphasis against the expressions it replaces, kept in the test as the reference: the same
  spans for every random text; in phase 2, the nesting against the comparison with every span.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. Emphasis | `Emphasis` opening, closing and scanning; the expressions removed; the tests of phase 1 | done |
| 2. The rest of the parsing | `nest` with a stack, the inline formulas walked once, the lines of the display formulas; the tests of phase 2 | done |

Every phase ends with the checks of `CLAUDE.md` and `:drawview-model:iosSimulatorArm64Test`.

### Phase 1 results

* `RichTextParser.Emphasis` holds its delimiter (`**`, `__`, `~~`, `*`, `_`), says where it opens
  (`opensAt`) and closes (`closesAt`), and scans a text (`scan`), jumping from one of its
  characters to the next (`indexOf`); `RichTextParser.emphasis` (internal) runs the five rules. The
  regular expressions of the emphasis are gone.
* A text of 1 MiB of delimiters never closed (`**a `, `*a `, `__a `, `_a `, `~~a `, `**a⏎`
  repeated), where each took 20 to 30 minutes, is parsed in 11–26 ms on the JVM and in 120–430 ms
  on the iOS simulator (the emphasis 4–9 ms and 37–89 ms of it; the rest is the masking and the
  expressions of the headings and the lists, `**a⏎` the slowest).
* `RichTextParserBoundsTest` (`drawview-model`): those texts, and the five delimiters mixed on a
  line and on lines of their own, within 10 s each; 2 000 random texts parsed without failing, their
  spans in the text, sorted, nested or apart; 10 000 random texts
  (an emoji, a lone surrogate, a no-break space among their characters, some characters masked)
  with the same emphasis as the regular expressions, kept in the test as the reference. Without
  one of the conditions of a single delimiter, the reference test fails. On the JVM and on the iOS
  simulator.
* Checked once more, outside the tests: the whole `parse` of before and of now give the same spans
  for 400 000 random texts (786 082 spans).

### Phase 2 results

* `RichTextParser.nest` (internal) keeps the spans with a stack of the ones still open, and sorts
  them with a comparator of its own: `compareBy` boxed the offsets, and took half of the time of
  the nesting on the iOS simulator. `RichTextParser.InlineClosings` (internal) walks for the
  inline formulas, keeping where a walk found no closing `$`; `isAloneOnItsLines` (internal) walks
  the spaces around a display formula only.
* Texts of 1 MiB with 150 000 to 525 000 spans (`*a* `, `**a** `, `~~a~~ `, `\*`, `` `a` ``,
  `# a b⏎`, `- a⏎`, `  1. a⏎`, `$a$ `, `$$a$$`, `$$a$$⏎`, every kind mixed) and `$a ` on one line
  are parsed in 10–42 ms on the JVM, where they would take from 14 s to minutes (from their times
  at 64 000 characters), and in 0.1–1.2 s on the iOS simulator. The slowest there is `- a⏎`: half of its time is the expression of the bulleted
  lists, 545 ms for its 262 144 matches.
* `RichTextParserBoundsTest`: those texts within 10 s each; against the code they replace, kept in
  the test as the references, 10 000 random texts with a walk for each of their `$`, 10 000 with a
  formula placed at random, 10 000 sets of up to 12 random spans nested. Each reference test fails
  with a broken change: the walks taken as done past the line break, or for a `$` followed by a
  space; the line break crossed walking back from a formula; the nesting checked against the
  outermost open span.
* Checked once more, outside the tests: the whole `parse` of before (the code of `main`) and of now
  give the same spans for 400 000 random texts (786 082 spans).

## Decisions

### Taken by the user (2026-10-22)

* The plan as it is, with phase 2 on the same branch.

### Taken by me (revertible)

* The same results as the expressions, their quirks included: `***a***` is a bold of `*a`, followed
  by a `*`; `a__b __c__` has no bold; the spaces of the emphasis are those of ASCII.
* One scan per rule rather than the single pass over the runs of delimiters of CommonMark: it keeps
  every result as it is, and five linear scans are linear.
* The new tests in `drawview-model`, next to the parser and able to reach its internals (the scan of
  the emphasis, the nesting) for the references; the existing ones stay in `drawview-canvas`.
* 10 s for 1 MiB as the bound: far above the linear time on a busy machine (2 s on the iOS
  simulator while Gradle built the native libraries, 0.3 s alone), far below the quadratic one.

## Left for later

* The presentation of a text with very many spans: `MarkdownTextUtil.toEditorText` looks for each
  span in the list of the formulas that failed (`span in errors`), and every formula is rendered.
  A text of 1 MiB of formulas is slow there whatever the parser does: to measure in a plan of its
  own.
* The rules of CommonMark for the emphasis (runs of delimiters, flanking, the rule of 3) would show
  some texts differently (`***a***`, `**a *b** c*`): a change of the notation, not of its cost.
* The emphasis takes the spaces of ASCII, the formulas all the spaces of Unicode (`isWhitespace`),
  as before.
* The expressions of the headings and the lists cost about 2 µs per match on the iOS simulator:
  linear, but a scan of the lines would be faster.
* A kind of span written as it is, added later (the code blocks of `docs/plans/code-blocks.md`), joins
  the code, the formulas and the escapes in the check of the nesting.
