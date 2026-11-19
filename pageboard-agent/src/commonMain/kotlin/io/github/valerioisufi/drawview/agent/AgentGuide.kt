package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.text.RichTextParser

/**
 * What a model is told about writing in the notebook, when it asks (`read_guide`): the Markdown its
 * text boxes understand, with an example of each thing, what they do not, the units and how a
 * proposal goes. The examples are parsed by a test, so that the guide says what the notebook does.
 */
internal object AgentGuide {
    /**
     * Something a text box understands.
     *
     * @param markdown how it is written.
     * @param kind what the notebook reads in [markdown].
     */
    class Example(val name: String, val markdown: String, val kind: RichTextParser.Kind, val note: String = "")

    val examples: List<Example> = listOf(
        Example("Headings", "# Title\n## Section\n### Subsection", RichTextParser.Kind.HEADING, "Levels 1 to 6. A heading stays with the text after it."),
        Example("Bold", "**bold**", RichTextParser.Kind.BOLD),
        Example("Italic", "*italic*", RichTextParser.Kind.ITALIC),
        Example("Struck through", "~~struck~~", RichTextParser.Kind.STRIKE),
        Example("Code in a line", "`x = 1`", RichTextParser.Kind.CODE),
        Example("A link", "[the paper](https://example.org)", RichTextParser.Kind.LINK),
        Example("A list", "- one\n- two\n  - under two", RichTextParser.Kind.LIST_ITEM, "Two spaces indent a level."),
        Example("A numbered list", "1. first\n2. second", RichTextParser.Kind.LIST_ITEM, "The numbers follow the items when they are edited."),
        Example(
            "A numbered list in a style of its own", "a.  first\nb.  second", RichTextParser.Kind.LIST_ITEM,
            "Two spaces after the marker: `a.  text`, `A.  text`, `i.  text`, `I.  text`, `1)  text`."
        ),
        Example("A quote", "> what was said", RichTextParser.Kind.QUOTE),
        Example("A formula in a line", "The energy is \$E = mc^2\$.", RichTextParser.Kind.FORMULA, "LaTeX between single dollar signs."),
        Example(
            "A displayed formula", "\$\$\n\\int_0^1 x^2 \\, dx = \\frac{1}{3}\n\$\$", RichTextParser.Kind.FORMULA,
            "LaTeX between double dollar signs, on lines of its own. It is never split between two boxes."
        ),
        Example("A block of code", "```python\nprint(\"hello\")\n```", RichTextParser.Kind.CODE_BLOCK, "Fenced, with its language for the colors."),
        Example("A table", "| Quantity | Unit |\n|---|---|\n| Force | N |", RichTextParser.Kind.TABLE, "A header row, a row of dashes, then the rows.")
    )

    val text: String = buildString {
        appendLine("# Writing in the notebook")
        appendLine()
        appendLine("The text of a text box is Markdown. Blank lines part its paragraphs. This is what it understands:")
        for (example in examples) {
            appendLine()
            appendLine("## ${example.name}")
            if (example.note.isNotEmpty()) appendLine(example.note)
            appendLine()
            example.markdown.lines().forEach { appendLine("    $it") }
        }
        appendLine()
        appendLine("## What it does not understand")
        appendLine("- Diagrams (Mermaid, PlantUML): a fenced block is shown as code, never drawn. Draw with add_stroke, or describe in words.")
        appendLine("- HTML: shown as it is written.")
        appendLine("- Images in the text (`![]()`): add an image file with add_image.")
        appendLine("- Footnotes and task lists.")
        appendLine()
        appendLine("## Sizes and places")
        appendLine("- Lengths and places are millimeters from the top-left corner of the page; font sizes are points.")
        appendLine("- read_page gives text_sizes: the sizes of the characters typed on the page, of the body of its slide and of its handwriting, and note, the size a text you add takes when you give none.")
        appendLine("- free_areas of read_page are where there is room, what the PDF page draws counted. render_page with overlay shows them on the page.")
        appendLine("- A text given a width is never made narrower; without one it takes the width its place allows.")
        appendLine()
        appendLine("## How a proposal goes")
        appendLine("- preview_proposal tells where each item would go and what is wrong with it, and changes nothing.")
        appendLine("- propose answers mode: review (the user decides) or applied (done already). require_review asks for the review in any case.")
        appendLine("- proposal_status gives, once decided, the id and the base of every element an item made or changed: use them for the next proposal without reading the page again.")
        appendLine("- An element is changed by one item of a proposal: update_text changes the words, the style, the place and the width of a text box together.")
        appendLine("- An item made on something that changed since you read it is stale and changes nothing: read it again.")
    }
}
