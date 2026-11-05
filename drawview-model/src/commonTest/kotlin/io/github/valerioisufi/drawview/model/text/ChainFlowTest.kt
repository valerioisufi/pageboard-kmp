package io.github.valerioisufi.drawview.model.text

import io.github.valerioisufi.drawview.model.text.ChainFlow.Piece
import io.github.valerioisufi.drawview.model.text.ChainFlow.Room
import kotlin.test.Test
import kotlin.test.assertEquals

/** The whole text of a chain split into the rooms of its boxes. */
class ChainFlowTest {

    /** Lines 5 mm high, wrapped at [charsPerLine] characters (a character is 1 mm wide). */
    private val flow = ChainFlow { text, width ->
        text.split('\n').sumOf { line -> maxOf(1, (line.length + width.toInt() - 1) / width.toInt()) } * 5f
    }

    private val room = Room(100f, 15f)

    @Test
    fun theLinesThatFitStayTheOthersGoOn() {
        assertEquals(listOf(Piece("a\nb\nc")), flow.flow("a\nb\nc", listOf(room), room))
        assertEquals(listOf(Piece("a\nb\nc"), Piece("d\ne")), flow.flow("a\nb\nc\nd\ne", listOf(room), room))
        assertEquals(
            listOf(Piece("a"), Piece("b\nc\nd"), Piece("e")),
            flow.flow("a\nb\nc\nd\ne", listOf(Room(100f, 5f)), room),
            "the first box near the bottom of its page, new boxes as needed"
        )
    }

    @Test
    fun aBlockIsNotCut() {
        val text = "a\n```\n1\n2\n3\n```\nb"
        assertEquals(listOf(Piece("a"), Piece("```\n1\n2\n3\n```"), Piece("b")), flow.flow(text, listOf(room), room), "the block whole, taller than a box")
        assertEquals(listOf(Piece("- one\n  more"), Piece("- two")), flow.flow("- one\n  more\n- two", listOf(Room(100f, 10f)), Room(100f, 10f)))
    }

    @Test
    fun aLineTallerThanABoxIsCutBetweenItsWords() {
        val line = "word ".repeat(30).trim()
        val pieces = flow.flow(line, listOf(Room(20f, 10f)), Room(20f, 10f))
        assertEquals(line, pieces.joinToString("") { it.text }, "joined again without a line break")
        assertEquals(listOf(false) + List(pieces.size - 1) { true }, pieces.map { it.continuesLine })
    }

    @Test
    fun aNumberedListGoesOnWithItsNumbers() {
        val pieces = flow.flow("1. a\n1. b\n1. c\n1. d", listOf(Room(100f, 10f)), room)
        assertEquals(listOf(Piece("1. a\n2. b"), Piece("3. c\n4. d")), pieces)
    }
}
