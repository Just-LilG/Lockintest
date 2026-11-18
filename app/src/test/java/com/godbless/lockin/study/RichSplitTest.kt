package com.godbless.lockin.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichSplitTest {
    private fun para(text: String) = RichSplit.blocks(text).first() as Block.Para
    private fun mathOf(text: String) = para(text).spans.filterIsInstance<Span.Math>().map { it.tex }

    @Test fun dollarMathIsSplitFromText() {
        val spans = para("Use \$x^2+1\$ here.").spans
        assertEquals(3, spans.size)
        assertEquals("x^2+1", (spans[1] as Span.Math).tex)
        assertEquals(" here.", (spans[2] as Span.Txt).text)
    }

    @Test fun moneyIsNotMaths() {
        assertTrue(mathOf("It costs \$5 and \$6 each.").isEmpty())
        assertTrue(mathOf("It costs \\\$5 each.").isEmpty())
    }

    @Test fun displayEquationsAreTheirOwnBlock() {
        val b = RichSplit.blocks("before \$\$\\frac{a}{b}\$\$ after")
        assertEquals(3, b.size)
        assertEquals("\\frac{a}{b}", (b[1] as Block.Display).tex)
    }

    @Test fun latexBracketDelimitersAreNormalised() {
        assertEquals(listOf("v = u + at"), mathOf("\\(v = u + at\\)"))
        assertTrue(RichSplit.blocks("\\[ E = mc^2 \\]")[0] is Block.Display)
    }

    @Test fun listsBoldAndItalic() {
        val b = RichSplit.blocks("- one\n- two\n\n1. three")
        assertEquals(listOf("•", "•", "1."), b.map { (it as Block.Para).marker })
        assertTrue((b[2] as Block.Para).gapBefore)
        val spans = para("**Key idea:** it is *really* so").spans.filterIsInstance<Span.Txt>()
        assertTrue(spans[0].bold)
        assertTrue(spans.any { it.italic && it.text == "really" })
        assertFalse(para("2*3*4").spans.any { it is Span.Txt && it.italic })
    }

    @Test fun plainTextPowersAreRescued() {
        assertEquals(listOf("x^2"), mathOf("coefficient of the x^2 term inside the brackets"))
        assertEquals(listOf("x^2 + 5x + 6 = 0"), mathOf("Solve x^2 + 5x + 6 = 0 using the formula"))
        assertEquals(listOf("a(x^2 + \\frac{b}{a}x) + c"), mathOf("The expression a(x^2 + (b/a)x) + c is the start."))
        assertEquals(listOf("x = \\frac{-b ± \\sqrt{b^2 - 4ac}}{2a}"), mathOf("using x = (-b ± sqrt(b^2 - 4ac)) / (2a)."))
        assertEquals(listOf("2^10 = 1024", "e^x", "x^{n+1}"), mathOf("2^10 = 1024, e^x, x^(n+1)"))
        assertEquals(listOf("\\pi r^2"), mathOf("area = pi r^2"))
    }

    @Test fun ordinaryProseIsLeftAlone() {
        for (t in listOf("and/or w/o spaces", "snake_case_name", "a normal sentence, with no maths in it.")) {
            assertTrue(t, mathOf(t).isEmpty())
        }
    }

    @Test fun wholeEquationDetection() {
        assertTrue(RichSplit.looksMath("a(x^2 + (b/a)x) + c"))
        assertTrue(RichSplit.looksMath("v = u + at"))
        assertFalse(RichSplit.looksMath("Hello world"))
        assertFalse(RichSplit.looksMath("the quadratic formula"))
        assertEquals("a(x^2 + \\frac{b}{a}x) + c", RichSplit.forceTex("a(x^2 + (b/a)x) + c"))
        assertEquals("v = u + at", RichSplit.forceTex("\$v = u + at\$"))
    }

    @Test fun everythingParsesAfterTheRescue() {
        for (t in listOf("a(x^2 + (b/a)x) + c", "sqrt(x^2+y^2)", "x = (-b ± sqrt(b^2 - 4ac)) / (2a)", "x^(n+1)", "pi r^2")) {
            val tex = RichSplit.forceTex(t)
            assertTrue("$t -> $tex", MathTex.parse(tex) != null)
        }
        assertNull(MathTex.parse("x".repeat(1000)))
    }

    @Test fun richDetection() {
        assertFalse(RichSplit.isRich(RichSplit.blocks("Just words, and **bold** ones.")))
        assertTrue(RichSplit.isRich(RichSplit.blocks("With \$x\$ in it")))
        assertTrue(RichSplit.isRich(RichSplit.blocks("- a list")))
        assertTrue(RichSplit.isRich(RichSplit.blocks("\$\$x\$\$")))
    }
}
