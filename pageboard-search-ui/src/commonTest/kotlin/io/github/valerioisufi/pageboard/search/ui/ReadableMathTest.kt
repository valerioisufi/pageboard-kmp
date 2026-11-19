package io.github.valerioisufi.pageboard.search.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadableMathTest {
    @Test
    fun writesTheFormulasOfATextAsPlainMath() {
        assertEquals(
            "Squeeze: cos x ≤ (sin x)/x ≤ 1, so the limit is 1.",
            ReadableMath.of("Squeeze: \$\\cos x \\le \\frac{\\sin x}{x} \\le 1\$, so the limit is 1.")
        )
    }

    @Test
    fun keepsTheTextWithoutFormulasAsItIs() {
        val text = "It costs \$5 and \$10, a_b and x^2 outside formulas."
        assertEquals(text, ReadableMath.of(text))
    }

    @Test
    fun writesDisplayFormulas() {
        assertEquals("Area: ∫₀¹ x² dx = 1/3", ReadableMath.of("Area: \$\$\\int_0^1 x^2 \\, dx = \\frac{1}{3}\$\$"))
    }

    @Test
    fun writesScriptsBySuperscriptsAndSubscriptsWhenTheyHaveThem() {
        assertEquals("x²⁺ⁿ", ReadableMath.latex("x^{2+n}"))
        assertEquals("aₙ₊₁", ReadableMath.latex("a_{n+1}"))
        assertEquals("e^(iπ) + 1 = 0", ReadableMath.latex("e^{i\\pi} + 1 = 0"))
        assertEquals("lim_(x → 0) (sin x)/x = 1", ReadableMath.latex("\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1"))
    }

    @Test
    fun writesRootsSymbolsAndFonts() {
        assertEquals("√(x+1) ≠ ∛y", ReadableMath.latex("\\sqrt{x+1} \\neq \\sqrt[3]{y}"))
        assertEquals("∀ε > 0 ∃ δ ∈ ℝ", ReadableMath.latex("\\forall\\varepsilon > 0 \\exists \\delta \\in \\mathbb{R}"))
        assertEquals("f(x) = |x| per x ∈ ℝ", ReadableMath.latex("f\\left(x\\right) = \\left|x\\right| \\text{ per } x \\in \\mathbb R"))
        assertEquals("α/2", ReadableMath.latex("\\frac\\alpha2"))
        assertEquals("(a+b)/(c-d)", ReadableMath.latex("\\frac{a+b}{c-d}"))
        assertEquals("(x+1)/2", ReadableMath.latex("\\dfrac{(x+1)}{2}"))
    }

    @Test
    fun writesMatricesInTheirBracketsAndCasesInBraces() {
        assertEquals("(1 2; 3 4)", ReadableMath.latex("\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}"))
        assertEquals("A = [a b; c d]", ReadableMath.latex("A = \\begin{bmatrix} a & b \\\\ c & d \\\\ \\end{bmatrix}"))
        assertEquals("{1 0; 0 1}", ReadableMath.latex("\\begin{Bmatrix}1&0\\\\0&1\\end{Bmatrix}"))
        assertEquals("det A = |a b; c d|", ReadableMath.latex("\\det A = \\begin{vmatrix}a&b\\\\c&d\\end{vmatrix}"))
        assertEquals("‖a b‖", ReadableMath.latex("\\begin{Vmatrix}a&b\\end{Vmatrix}"))
        assertEquals("1 2; 3 4", ReadableMath.latex("\\begin{matrix}1&2\\\\3&4\\end{matrix}"))
        assertEquals("1 2", ReadableMath.latex("\\begin{array}{cc} 1 & 2 \\end{array}"))
        assertEquals("a = b; = c", ReadableMath.latex("\\begin{aligned} a &= b \\\\ &= c \\end{aligned}"))
        assertEquals(
            "|x| = { x, x ≥ 0; -x, x < 0 }",
            ReadableMath.latex("|x| = \\begin{cases} x & x \\ge 0 \\\\ -x & x < 0 \\end{cases}")
        )
        assertEquals(
            "{ (1 0; 0 1), if n = 0; 0, otherwise }",
            ReadableMath.latex("\\begin{cases} \\begin{pmatrix}1&0\\\\0&1\\end{pmatrix} & \\text{if } n = 0 \\\\ 0 & \\text{otherwise} \\end{cases}")
        )
        assertEquals("(1 2)", ReadableMath.latex("\\begin{pmatrix}1&2"))
    }

    @Test
    fun readsTheContentOfCommandsThatAreNotWordsOfTheFormula() {
        assertEquals("a+…+a (n)", ReadableMath.latex("\\underbrace{a+\\dots+a}_{n}"))
        assertEquals("x+y (k)", ReadableMath.latex("\\overbrace{x+y}^{k}"))
        assertEquals("argmaxₓ f(x)", ReadableMath.latex("\\operatorname*{argmax}_x f(x)"))
        assertEquals("a ≠ b, x ∉ A", ReadableMath.latex("a \\not= b, x \\not\\in A"))
        assertEquals("∫ f(x) dx", ReadableMath.latex("\\int f(x)\\,\\mathrm{d}x"))
        assertEquals("x", ReadableMath.latex("\\textcolor{red}{x}"))
        assertEquals("=", ReadableMath.latex("\\overset{!}{=}"))
    }

    @Test
    fun writesAnUnknownCommandByTheContentOfItsArgumentsOrElseByItsName() {
        assertEquals("foo x", ReadableMath.latex("\\foo x"))
        assertEquals("x y", ReadableMath.latex("\\foo{x}{y}"))
    }

    @Test
    fun boundsTheNestingOfTheGroups() {
        val deep = "{".repeat(5_000) + "x" + "}".repeat(5_000)
        assertTrue("x" in ReadableMath.latex(deep))
        val commands = "\\frac".repeat(2_000) + "12"
        assertTrue(ReadableMath.latex(commands).isNotEmpty())
        val matrices = "\\begin{pmatrix}".repeat(2_000) + "x" + "\\end{pmatrix}".repeat(2_000)
        assertTrue("x" in ReadableMath.latex(matrices))
    }
}
