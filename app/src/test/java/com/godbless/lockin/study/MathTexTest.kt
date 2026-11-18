package com.godbless.lockin.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MathTexTest {
    /** Every character half an em wide: enough to test the layout rules without a font. */
    private val metrics = MathMetrics { text, _, size -> text.length * size * 0.5f }

    private fun ast(src: String): MNode = MathTex.parse(src) ?: error("did not parse: $src")
    private fun items(src: String): List<MNode> = (ast(src) as MNode.Seq).items
    private fun box(src: String, display: Boolean = true): MBox = MathLayout(metrics, 20f).box(ast(src), display)

    @Test fun fractionIsTallerThanText() {
        assertTrue(box("""\frac{a}{b}""").height > box("a").height * 1.5f)
    }

    @Test fun superscriptRaisesAndWidens() {
        assertTrue(box("x^2").asc > box("x").asc)
        assertTrue(box("x^2").w > box("x").w)
    }

    @Test fun subscriptDropsBelowTheBaseline() {
        assertTrue(box("x_1").desc > box("x").desc)
    }

    @Test fun relationsGetSpaceAndUnaryMinusDoesNot() {
        assertTrue(box("a=b").w > box("a").w + box("=").w + box("b").w)
        assertEquals(box("-").w + box("x").w, box("-x").w, 0.01f)
        assertTrue(box("a-x").w > box("a").w + box("-").w + box("x").w)
    }

    @Test fun fractionArgumentsTakeOneCharacter() {
        val f = items("""\frac12""")[0] as MNode.Frac
        assertEquals("1", (f.num as MNode.Sym).text)
        assertEquals("2", (f.den as MNode.Sym).text)
    }

    @Test fun numbersStayTogether() {
        assertEquals("1.5", (items("1.5x")[0] as MNode.Sym).text)
    }

    @Test fun bracketsAroundTallContentGrow() {
        assertTrue(items("""(\frac12)""")[0] is MNode.Lr)
        assertEquals(5, items("(x+1)").size) // a plain group stays plain symbols
        val grown = items("""a[(x+\frac b{2a})^2]""")
        assertTrue(grown.any { it is MNode.Scripts || it is MNode.Lr })
    }

    @Test fun matrixAndCasesParse() {
        val m = items("""\begin{pmatrix}1&2\\3&4\end{pmatrix}""")[0] as MNode.Mat
        assertEquals(2, m.rows.size)
        assertEquals(2, m.rows[0].size)
        assertTrue(box("""\begin{pmatrix}1&2\\3&4\end{pmatrix}""").height > box("1").height * 2)
        val c = items("""f(x)=\begin{cases}x&x\ge 0\\-x&x<0\end{cases}""").last() as MNode.Mat
        assertEquals("cases", c.env)
    }

    @Test fun topLevelRowBreakStacksLines() {
        val m = ast("""a\\b""") as MNode.Mat
        assertEquals(2, m.rows.size)
    }

    @Test fun unknownCommandsAndUnicodeDoNotThrow() {
        assertEquals("foo", (items("""\foo{x}""")[0] as MNode.Txt).text)
        assertTrue(items("x²")[0] is MNode.Scripts)
        assertNotNull(MathTex.parse("a ± b ≤ c × 2"))
    }

    @Test fun tooLongOrTooDeepIsNullNotACrash() {
        assertNull(MathTex.parse("x".repeat(MathTex.MAX_SOURCE + 1)))
        assertNull(MathTex.parse("{".repeat(100) + "x" + "}".repeat(100)))
    }

    @Test fun inlinePiecesAddUpToTheWholeLine() {
        for (src in listOf("x^2+2x+1=(x+1)^2", "a=b+c-d\\le e", """\frac{a}{b}+\sqrt{x}=y""")) {
            val a = ast(src)
            val l = MathLayout(metrics, 20f)
            val whole = l.box(a, false).w
            val pieces = l.pieces(a)
            assertTrue(pieces.size > 1)
            assertEquals(src, whole, pieces.sumOf { it.w.toDouble() }.toFloat(), 0.01f)
        }
    }

    @Test fun anythingTheModelCouldTypeLaysOutWithoutThrowing() {
        val bits = listOf(
            """\frac""", """\sqrt""", """\left(""", """\right)""", """\left[""", """\right.""", "{", "}", "^", "_", "&", """\\""",
            """\begin{pmatrix}""", """\end{pmatrix}""", """\begin{cases}""", """\end{cases}""", """\begin{aligned}""",
            """\end{aligned}""", """\sum""", """\int""", "x", "2", "1.5", "+", "-", "=", """\alpha""", """\text{hi there}""",
            """\hat""", """\vec""", """\overline""", """\boxed""", "'", """\unknown""", """\mathbb{R}""", """\sqrt[3]""", """\lim""",
            """\underbrace""", """\binom""", " ", "\$", """\%""", """\begin{array}{cc}""", """\end{array}""",
        )
        val rnd = Random(7)
        repeat(4000) {
            val src = (1..rnd.nextInt(1, 18)).joinToString("") { bits[rnd.nextInt(bits.size)] }
            val a = MathTex.parse(src) ?: return@repeat
            for (display in listOf(true, false)) {
                val b = MathLayout(metrics, 20f).box(a, display)
                assertTrue(src, b.w.isFinite() && b.asc.isFinite() && b.desc.isFinite())
            }
        }
    }
}
