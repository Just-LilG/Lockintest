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
    private fun box(src: String, display: Boolean = true, spots: Boolean = false): MBox = MathLayout(metrics, 20f, spots).box(ast(src), display)

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

    // ---- colour, tappable pieces, and back to LaTeX
    @Test fun colourTintsOnlyItsBody() {
        val b = box("""a+\textcolor{lime}{b}""")
        val tints = b.ops.filterIsInstance<MOp.Text>().associate { it.text to it.tint }
        assertEquals(0, tints["a"])
        assertEquals(tintIndex("lime"), tints["b"])
        val rest = box("""\color{sky} x + y""").ops.filterIsInstance<MOp.Text>()
        assertTrue(rest.all { it.tint == tintIndex("sky") })
        assertEquals(0, tintIndex("nonsense"))
    }

    @Test fun tappingFindsTheSmallestPiece() {
        assertTrue(box("""\frac{b}{2a}+c""").ops.none { it is MOp.Spot }) // not made unless asked for
        val b = box("""\frac{b}{2a}+c""", spots = true)
        val spots = b.ops.filterIsInstance<MOp.Spot>()
        assertTrue(spots.any { it.node is MNode.Frac })
        val frac = spots.first { it.node is MNode.Frac }
        // a point on the numerator's b: the variable is the smallest spot, not the whole fraction
        val hit = MathHit.find(b.ops, frac.x + frac.w / 2, frac.y + frac.h * 0.2f, 1f)!!
        assertTrue(hit.node is MNode.Sym || hit.node is MNode.Frac)
        val onFraction = MathHit.find(b.ops, frac.x + frac.w / 2, frac.y + frac.h / 2, 0f)!!
        assertTrue(onFraction.w * onFraction.h <= frac.w * frac.h + 0.01f)
        assertNull(MathHit.find(b.ops, -500f, -500f, 1f))
    }

    @Test fun serialiserRoundTrips() {
        for (src in listOf(
            """\frac{-b\pm\sqrt{b^2-4ac}}{2a}""", """x_1^2+\alpha\cdot\beta""", """\left(\frac{a}{b}\right)^2""",
            """\sum_{i=1}^{n} i""", """\begin{pmatrix}1&2\\3&4\end{pmatrix}""", """\sqrt[3]{x}+\vec{v}+\overline{AB}""",
            """a\le b\ne c""", """\text{speed}=\frac{d}{t}""", """\binom{n}{k}""", """\textcolor{lime}{a}x^2""",
        )) {
            val once = MathSrc.tex(ast(src))
            val again = MathTex.parse(once) ?: error("did not re-parse: $once (from $src)")
            assertEquals(src, once, MathSrc.tex(again)) // writing it out is stable
            assertEquals(src, box(src).w, MathLayout(metrics, 20f).box(again, true).w, 0.01f) // and lays out the same
        }
    }

    @Test fun plainBracketsAroundAGrownGroupGrowToo() {
        val g = items("""a[(x+\frac b{2a})^2-(\frac b{2a})^2]+c""")
        assertTrue(g.any { it is MNode.Lr }) // the outer [ ] holds grown ( )^2 groups
    }
}
