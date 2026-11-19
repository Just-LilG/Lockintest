package com.godbless.lockin.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class PlotMathTest {
    private fun v(s: String, vararg env: Pair<Char, Double>): Double {
        val p = Expr.parse(s) ?: error("did not parse: $s")
        return Expr.eval(p.ex, env.toMap())
    }

    @Test fun arithmeticAndPrecedence() {
        assertEquals(14.0, v("2+3*4"), 1e-9)
        assertEquals(20.0, v("(2+3)*4"), 1e-9)
        assertEquals(512.0, v("2^3^2"), 1e-9) // right to left
        assertEquals(-4.0, v("-2^2"), 1e-9)
        assertEquals(5.0, v("10-2-3"), 1e-9)
        assertEquals(0.5, v("2^-1"), 1e-9)
    }

    @Test fun impliedMultiplicationAndFunctions() {
        assertEquals(15.0, v("3(4+1)"), 1e-9)
        assertEquals(2 * PI, v("2pi"), 1e-9)
        assertEquals(24.0, v("2x(x+1)", 'x' to 3.0), 1e-9)
        assertEquals(-1.0, v("ax^2+bx+c", 'a' to 1.0, 'b' to -4.0, 'c' to 3.0, 'x' to 2.0), 1e-9)
        assertEquals(7.0, v("sqrt(16)+abs(-3)"), 1e-9)
        assertEquals(1.0, v("sin(pi/2)"), 1e-9)
        assertEquals(1.0, v("ln(e)"), 1e-9)
        assertEquals(0.0, v("sin x", 'x' to 0.0), 1e-9)
    }

    @Test fun badInputIsNullAndMathErrorsAreNaN() {
        assertNull(Expr.parse("(x^"))
        assertNull(Expr.parse("2 +"))
        assertNull(Expr.parse("x @ 2"))
        assertTrue(v("1/x", 'x' to 0.0).isNaN())
        assertTrue(v("sqrt(x)", 'x' to -1.0).isNaN())
        assertTrue(v("ln(x)", 'x' to 0.0).isNaN())
        assertNull(Expr.parse("(".repeat(200) + "1" + ")".repeat(200)))
    }

    @Test fun numberCheckJudgesOnlyPureNumbers() {
        assertEquals(true, NumCheck.check("""2^3 + 4 = 12"""))
        assertEquals(false, NumCheck.check("""2^3 + 4 = 13"""))
        assertEquals(true, NumCheck.check("""\frac{1}{3} = 0.333"""))
        assertEquals(false, NumCheck.check("""\frac{1}{3} = 0.34"""))
        assertEquals(true, NumCheck.check("""\frac{3}{4} + \frac{1}{4} = 1"""))
        assertEquals(true, NumCheck.check("""\sqrt{16} = 4"""))
        assertEquals(true, NumCheck.check("""\pi \approx 3.14"""))
        assertEquals(false, NumCheck.check("""\sqrt{2} \approx 1.5"""))
        assertEquals(true, NumCheck.check("""2 \times 3 = 6 = 12 \div 2"""))
        assertEquals(true, NumCheck.check("""\left(1+2\right)^{2} = 9"""))
        assertNull(NumCheck.check("""x^2 = 9"""))
        assertNull(NumCheck.check("""a = b"""))
        assertNull(NumCheck.check("""5 + 5"""))
        assertNull(NumCheck.check("""10\% = 0.1"""))
    }

    @Test fun plotSpecReadsTheBlock() {
        val p = PlotSpec.parse("y = x^2 - 4x + 3\nx: -1..5\npoints: (2,-1) vertex; (1,0) root; (3,0) root\ntitle: A parabola")!!
        assertEquals(-1.0, p.xMin, 0.0)
        assertEquals(5.0, p.xMax, 0.0)
        assertEquals(3, p.points.size)
        assertEquals("vertex", p.points[0].label)
        assertEquals("A parabola", p.title)
        val q = PlotSpec.parse("y = a x^2 + b x + c\nx: -3 to 5\nparam: a = 1 [-3..3]\nparam: b = -4 [-8..8]\nparam: c = 3 [-5..5]")!!
        assertEquals(3, q.params.size)
        assertEquals(-8.0, q.params[1].lo, 0.0)
    }

    @Test fun plotSpecRefusesWhatItCannotDraw() {
        assertNull(PlotSpec.parse("y = x^2")) // no x range
        assertNull(PlotSpec.parse("y = z^2\nx: 0..1")) // unknown variable
        assertNull(PlotSpec.parse("y = (x^\nx: 0..1"))
        assertNull(PlotSpec.parse("y = x\nx: 5..1"))
    }

    @Test fun aSliderAlwaysGetsARange() {
        val p = PlotSpec.parse("y = a x\nx: 0..1\nparam: a = 2 [3..3]")!!
        assertTrue(p.params[0].lo < p.params[0].hi)
        assertTrue(p.params[0].value in p.params[0].lo..p.params[0].hi)
    }

    @Test fun samplingAndRange() {
        val spec = PlotSpec.parse("y = x^2\nx: -2..2")!!
        val s = PlotCalc.sample(spec, emptyMap(), 5)
        assertEquals(listOf(4.0, 1.0, 0.0, 1.0, 4.0), s.ys[0].toList())
        val (lo, hi) = PlotCalc.yRange(spec, s)
        assertTrue(lo < 0.0 && hi > 4.0 - 1e-9)
        assertEquals(2.0, PlotCalc.niceStep(8.0), 1e-9)
        assertEquals(0.5, PlotCalc.niceStep(2.0), 1e-9)
        // a pole does not flatten the rest of the curve
        val tan = PlotSpec.parse("y = 1/x\nx: -1..1")!!
        val (lo2, hi2) = PlotCalc.yRange(tan, PlotCalc.sample(tan, emptyMap()))
        assertTrue(hi2 - lo2 < 1e5)
    }

    @Test fun diagramsParse() {
        val d = DiagramSpec.parse("flow", "Sunlight -> Leaf -> Glucose\nLeaf -> Oxygen : released")!!
        assertEquals(4, d.nodes.size)
        assertEquals(3, d.edges.size)
        assertEquals("released", d.edges[2].third)
        val c = DiagramSpec.parse("cycle", "Evaporation\nCondensation\nPrecipitation\nCollection")!!
        assertEquals(4, c.edges.size)
        assertEquals(Triple(3, 0, null), c.edges[3])
        assertEquals(3, DiagramSpec.parse("cycle", "A -> B -> C -> A")!!.nodes.size)
        val t = DiagramSpec.parse("timeline", "1905: Einstein publishes relativity\n1915 | General relativity")!!
        assertEquals("1905", t.items[0].first)
        assertEquals(2, t.items.size)
        assertNull(DiagramSpec.parse("flow", (1..13).joinToString("\n") { "n$it -> n${it + 1}" }))
    }

    @Test fun flowLayersAndLayoutStayInsideTheWidth() {
        val d = DiagramSpec.parse("flow", "A -> B\nA -> C\nB -> D\nC -> D")!!
        val rows = DiagramPlacer.layers(d)
        assertEquals(3, rows.size)
        assertEquals(2, rows[1].size)
        val lay = DiagramPlacer.flow(d, 300f, 12f, 30f) { _, maxW -> Pair(minOf(100f, maxW), 30f) }
        for (r in lay.rects) assertTrue(r.x >= -0.01f && r.x + r.w <= 300.01f)
        assertTrue(lay.height > 90f)
        val loop = DiagramSpec.parse("cycle", "A -> B -> C -> A")!!
        assertEquals(3, DiagramPlacer.layers(loop).size) // the closing edge does not make a layer
        val ring = DiagramPlacer.cycle(DiagramSpec.parse("cycle", "a\nb\nc\nd")!!, 300f) { _, _ -> Pair(80f, 30f) }
        assertEquals(4, ring.rects.size)
        assertFalse(ring.rects.any { it.x < -0.01f || it.x + it.w > 300.01f })
    }

    @Test fun anythingParsesWithoutThrowing() {
        val bits = listOf("x", "2", "+", "-", "*", "/", "^", "(", ")", "|", "sin", "sqrt", "pi", "e", ".", " ", "a", "b", "(x", "x)")
        val rnd = kotlin.random.Random(11)
        repeat(3000) {
            val s = (1..rnd.nextInt(1, 14)).joinToString("") { bits[rnd.nextInt(bits.size)] }
            val p = Expr.parse(s)
            if (p != null) Expr.eval(p.ex, mapOf('x' to 1.5, 'a' to 2.0, 'b' to 3.0))
            NumCheck.check(s + " = " + s)
        }
        assertNotNull(Expr.parse("1"))
    }
}
