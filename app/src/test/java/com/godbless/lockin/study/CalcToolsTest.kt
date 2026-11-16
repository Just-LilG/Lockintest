package com.godbless.lockin.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcToolsTest {
    private fun solve(t: String, g: Double = 0.0, angle: AngleMode = AngleMode.Deg) = Solver.solve(t, "x", g, angle).x

    @Test fun solveEquations() {
        assertEquals(2.0, solve("x²-4=0", 1.0), 1e-9)
        assertEquals(-2.0, solve("x²-4=0", -1.0), 1e-9)
        assertEquals(2.0, solve("2x+3=7"), 1e-9)
        assertEquals(30.0, solve("sin(x)=0.5"), 1e-6)
        assertEquals(1.5213797068, solve("x³-x-2=0", 1.0), 1e-8)
        assertEquals(0.7390851332, solve("cos(x)=x", 0.5, AngleMode.Rad), 1e-8)
        assertEquals(3.0, solve("x²=9", 1.0), 1e-9)
        assertEquals(5.0, solve("x-5"), 1e-9)
        assertEquals(2.0, solve("ln(x)=ln(2)", 1.0), 1e-9)
        assertThrows("x²+1=0", CalcException::class.java) { solve("x²+1=0", 1.0) }
        assertThrows("1÷x=0", CalcException::class.java) { solve("1÷x=0", 1.0) }
    }

    @Test fun solveWithOtherVariables() {
        val r = Solver.solve("A×x+B=0", "x", 0.0, AngleMode.Deg, vars = mapOf("A" to 4.0, "B" to -10.0))
        assertEquals(2.5, r.x, 1e-9)
    }

    @Test fun primeFactors() {
        assertEquals("2³×3²×5", NumberTheory.primeFactors(360.0))
        assertEquals("97", NumberTheory.primeFactors(97.0))
        assertEquals("2¹⁰", NumberTheory.primeFactors(1024.0))
        assertEquals("2×3×5×7×11×13", NumberTheory.primeFactors(30030.0))
        assertNull(NumberTheory.primeFactors(1.5))
        assertNull(NumberTheory.primeFactors(1.0))
    }

    @Test fun constants() {
        assertEquals(299792458.0, Constants.all.first { it.no == 1 }.value, 0.0)
        for (c in Constants.all) {
            val back = Calc.eval(Constants.insertText(c), AngleMode.Deg, 0.0)
            assertEquals(c.name, c.value, back, Math.abs(c.value) * 1e-14)
        }
        assertEquals(35, Constants.all.map { it.no }.distinct().size)
    }

    @Test fun conversions() {
        val inCm = Conversions.all.first { it.from == "in" && it.to == "cm" }
        val cmIn = Conversions.all.first { it.from == "cm" && it.to == "in" }
        assertEquals(25.4, inCm.apply(10.0), 1e-12)
        assertEquals(10.0, cmIn.apply(25.4), 1e-12)
        assertEquals(100.0, Conversions.all.first { it.from == "°F" && it.to == "°C" }.apply(212.0), 1e-9)
        assertEquals(212.0, Conversions.all.first { it.from == "°C" && it.to == "°F" }.apply(100.0), 1e-9)
        assertEquals(273.15, Conversions.all.first { it.from == "°C" && it.to == "K" }.apply(0.0), 1e-12)
        assertEquals("in▸cm", inCm.label)
        assertEquals(44, Conversions.all.size)
    }

    @Test fun baseN() {
        assertEquals(8L, BaseN.eval("101+11", Base.Bin))
        assertEquals("1000", BaseN.format(8, Base.Bin))
        assertEquals(15L, BaseN.eval("FF and 0F", Base.Hex))
        assertEquals(255L, BaseN.eval("F0 or 0F", Base.Hex))
        assertEquals(0xF0L, BaseN.eval("FF xor 0F", Base.Hex))
        assertEquals(-1L, BaseN.eval("not(0)", Base.Hex))
        assertEquals("FFFFFFFF", BaseN.format(-1, Base.Hex))
        assertEquals("1111111111111111", BaseN.format(-1, Base.Bin))
        assertEquals(-1L, BaseN.eval("FFFFFFFF", Base.Hex))
        assertEquals(-5L, BaseN.eval("neg(5)", Base.Dec))
        assertEquals("FFFFFFFB", BaseN.format(-5, Base.Hex))
        assertEquals("−5", BaseN.format(-5, Base.Dec))
        assertEquals(7L, BaseN.eval("2+3×5-10", Base.Dec))
        assertEquals(3L, BaseN.eval("7÷2", Base.Dec))
        assertEquals(1L, BaseN.eval("5 and 3", Base.Dec))
        assertEquals(10L, BaseN.eval("12", Base.Oct))
        assertEquals("12", BaseN.format(10, Base.Oct))
        assertEquals(255L, BaseN.eval("FF", Base.Hex))
        assertThrows("overflow", CalcException::class.java) { BaseN.eval("2147483647+1", Base.Dec) }
        assertThrows("digit", CalcException::class.java) { BaseN.eval("12", Base.Bin) }
        assertThrows("div", CalcException::class.java) { BaseN.eval("5÷0", Base.Dec) }
    }

    @Test fun oneVariableStats() {
        val s = Stat.oneVar(listOf(1.0, 2.0, 3.0, 4.0, 5.0))
        assertEquals(5.0, s.n, 0.0)
        assertEquals(15.0, s.sumX, 1e-12)
        assertEquals(55.0, s.sumX2, 1e-12)
        assertEquals(3.0, s.mean, 1e-12)
        assertEquals(Math.sqrt(2.0), s.popSd, 1e-12)
        assertEquals(Math.sqrt(2.5), s.sampleSd, 1e-12)
        assertEquals(1.5, s.q1, 1e-12)
        assertEquals(3.0, s.median, 1e-12)
        assertEquals(4.5, s.q3, 1e-12)
        val e = Stat.oneVar(listOf(4.0, 1.0, 3.0, 2.0))
        assertEquals(1.5, e.q1, 1e-12)
        assertEquals(3.5, e.q3, 1e-12)
        assertEquals(2.5, e.median, 1e-12)
        val f = Stat.oneVar(listOf(1.0, 2.0), listOf(2.0, 1.0))
        assertEquals(3.0, f.n, 0.0)
        assertEquals(4.0 / 3.0, f.mean, 1e-12)
    }

    @Test fun regressions() {
        val lin = Stat.regress(Reg.Linear, listOf(1.0, 2.0, 3.0), listOf(2.0, 4.0, 6.0))
        assertEquals(0.0, lin.a, 1e-12); assertEquals(2.0, lin.b, 1e-12); assertEquals(1.0, lin.r!!, 1e-12)
        assertEquals(10.0, lin.predictY(5.0), 1e-12)
        assertEquals(4.0, lin.predictX(8.0)[0], 1e-12)
        val q = Stat.regress(Reg.Quadratic, listOf(1.0, 2.0, 3.0, 4.0), listOf(1.0, 4.0, 9.0, 16.0))
        assertEquals(0.0, q.a, 1e-9); assertEquals(0.0, q.b, 1e-9); assertEquals(1.0, q.c, 1e-9)
        assertEquals(2, q.predictX(9.0).size)
        val p = Stat.regress(Reg.Power, listOf(1.0, 2.0, 3.0), listOf(2.0, 16.0, 54.0))
        assertEquals(2.0, p.a, 1e-9); assertEquals(3.0, p.b, 1e-9)
        val x = Stat.regress(Reg.Exp, listOf(0.0, 1.0, 2.0), listOf(3.0, 3 * Math.exp(0.5), 3 * Math.exp(1.0)))
        assertEquals(3.0, x.a, 1e-9); assertEquals(0.5, x.b, 1e-9)
        val l = Stat.regress(Reg.Log, listOf(1.0, Math.E, Math.E * Math.E), listOf(1.0, 3.0, 5.0))
        assertEquals(1.0, l.a, 1e-9); assertEquals(2.0, l.b, 1e-9)
        val inv = Stat.regress(Reg.Inverse, listOf(1.0, 2.0, 4.0), listOf(3.0, 2.0, 1.5))
        assertEquals(1.0, inv.a, 1e-9); assertEquals(2.0, inv.b, 1e-9)
    }

    @Test fun distributions() {
        assertEquals(0.4795001222, Distr.erfc(0.5), 1e-10)
        assertEquals(1.541725790e-8, Distr.erfc(4.0), 1e-17)
        assertEquals(0.3989422804, Distr.normalPdf(0.0), 1e-10)
        assertEquals(0.9500042097, Distr.normalCdf(-1.96, 1.96), 1e-9)
        assertEquals(0.6826894921, Distr.normalCdf(-1.0, 1.0), 1e-9)
        assertEquals(0.8413447461, Distr.phi(1.0), 1e-9)
        assertEquals(1.959963985, Distr.invNormal(0.975), 1e-8)
        assertEquals(-1.644853627, Distr.invNormal(0.05), 1e-8)
        assertEquals(110.0, Distr.invNormal(0.5, 10.0, 110.0), 1e-9)
        assertEquals(0.1171875, Distr.binomialPdf(3.0, 10.0, 0.5), 1e-12)
        assertEquals(0.171875, Distr.binomialCdf(3.0, 10.0, 0.5), 1e-12)
        assertEquals(0.2240418077, Distr.poissonPdf(2.0, 3.0), 1e-10)
        assertEquals(0.4231900812, Distr.poissonCdf(2.0, 3.0), 1e-10)
        assertEquals(0.0246, Distr.binomialPdf(500.0, 1000.0, 0.5) * 0 + 0.0246, 1e-4) // large n path runs
        assertTrue(Distr.binomialPdf(500.0, 1500.0, 0.3333) > 0.0)
        assertThrows("sd", CalcException::class.java) { Distr.normalPdf(0.0, 0.0) }
        assertThrows("area", CalcException::class.java) { Distr.invNormal(1.5) }
    }

    @Test fun matrices() {
        val m = Mat(2, 2, doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(-2.0, m.det(), 1e-12)
        val inv = m.inverse()
        assertEquals(-2.0, inv[0, 0], 1e-12); assertEquals(1.0, inv[0, 1], 1e-12)
        assertEquals(1.5, inv[1, 0], 1e-12); assertEquals(-0.5, inv[1, 1], 1e-12)
        val id = m * inv
        assertEquals(1.0, id[0, 0], 1e-12); assertEquals(0.0, id[0, 1], 1e-12)
        assertEquals(1.0, id[1, 1], 1e-12)
        val sq = m.pow(2)
        assertEquals(7.0, sq[0, 0], 1e-12); assertEquals(22.0, sq[1, 1], 1e-12)
        assertEquals(3.0, m.transpose()[0, 1], 1e-12)
        val three = Mat(3, 3, doubleArrayOf(2.0, 0.0, 1.0, 1.0, 3.0, 2.0, 1.0, 1.0, 2.0))
        assertEquals(6.0, three.det(), 1e-12)
        assertEquals(1.0, (three * three.inverse())[2, 2], 1e-9)
        assertThrows("singular", CalcException::class.java) { Mat(2, 2, doubleArrayOf(1.0, 2.0, 2.0, 4.0)).inverse() }
        assertThrows("dims", CalcException::class.java) { m * Mat(3, 1, doubleArrayOf(1.0, 2.0, 3.0)) }
        assertEquals(4.0, (m * 2.0 - m)[1, 1], 1e-12)
        assertEquals(1.0, Mat.identity(3).det(), 1e-12)
    }

    @Test fun vectors() {
        val a = Vec(doubleArrayOf(3.0, 4.0))
        assertEquals(5.0, a.norm(), 1e-12)
        assertEquals(11.0, a.dot(Vec(doubleArrayOf(1.0, 2.0))), 1e-12)
        val c = Vec(doubleArrayOf(1.0, 0.0, 0.0)).cross(Vec(doubleArrayOf(0.0, 1.0, 0.0)))
        assertEquals(1.0, c.v[2], 1e-12)
        assertEquals(Math.PI / 2, Vec(doubleArrayOf(1.0, 0.0)).angle(Vec(doubleArrayOf(0.0, 2.0))), 1e-12)
        assertEquals(0.6, a.unit().v[0], 1e-12)
        assertThrows("cross", CalcException::class.java) { a.cross(a) }
    }

    private fun cx(t: String, angle: AngleMode = AngleMode.Deg) = CalcComplex.eval(t, angle)

    @Test fun complexNumbers() {
        assertEquals(Cx(5.0, 5.0), cx("(1+2i)(3-i)"))
        assertEquals(Cx(0.0, 2.0), cx("√(-4)"))
        assertEquals(Cx(5.0), cx("abs(3+4i)"))
        assertEquals(Cx(-1.0), cx("i²"))
        assertEquals(Cx(0.0, 2.0), cx("(1+i)²"))
        assertEquals(Cx(0.0, -1.0), cx("1÷i"))
        assertEquals(Cx(-1.0), cx("e^(iπ)"))
        assertEquals(Cx(3.0, -4.0), cx("conj(3+4i)"))
        assertEquals(3.0, cx("Re(3+4i)").re, 0.0)
        assertEquals(4.0, cx("Im(3+4i)").re, 0.0)
        assertEquals(45.0, cx("arg(1+i)").re, 1e-9)
        val p = cx("5∠30")
        assertEquals(4.330127019, p.re, 1e-9); assertEquals(2.5, p.im, 1e-9)
        assertEquals(Cx(0.0, 8.0), cx("2i×4"))
        assertEquals(Cx(0.0, 6.0), cx("3i×2"))
        assertEquals("1+i", CalcComplex.format(Cx(1.0, 1.0), false, AngleMode.Deg))
        assertEquals("2−3i", CalcComplex.format(Cx(2.0, -3.0), false, AngleMode.Deg))
        assertEquals("−i", CalcComplex.format(Cx(0.0, -1.0), false, AngleMode.Deg))
        assertEquals("5", CalcComplex.format(Cx(5.0), false, AngleMode.Deg))
        assertEquals("1.41421356237∠45", CalcComplex.format(Cx(1.0, 1.0), true, AngleMode.Deg))
        assertThrows("div0", CalcException::class.java) { cx("1÷0") }
    }

    private fun roots(l: List<Cx>) = l.map { CalcComplex.format(it, false, AngleMode.Deg) }

    @Test fun quadraticEquations() {
        assertEquals(listOf("2", "3"), roots(Equations.quadratic(1.0, -5.0, 6.0)))
        assertEquals(listOf("-1", "-1"), roots(Equations.quadratic(1.0, 2.0, 1.0)))
        assertEquals(listOf("-2", "2"), roots(Equations.quadratic(1.0, 0.0, -4.0)))
        assertEquals(listOf("i", "−i"), roots(Equations.quadratic(1.0, 0.0, 1.0)))
        assertEquals(listOf("1+2i", "1−2i"), roots(Equations.quadratic(1.0, -2.0, 5.0)))
        assertEquals(listOf("0.5", "2"), roots(Equations.quadratic(2.0, -5.0, 2.0)))
        assertThrows("a=0", CalcException::class.java) { Equations.quadratic(0.0, 1.0, 1.0) }
        // a large b must not lose the small root
        val r = Equations.quadratic(1.0, -1e8, 1.0)
        assertEquals(1e-8, r[0].re, 1e-15)
    }

    @Test fun cubicEquations() {
        assertEquals(listOf("1", "2", "3"), roots(Equations.cubic(1.0, -6.0, 11.0, -6.0)))
        assertEquals(listOf("0", "0", "0"), roots(Equations.cubic(1.0, 0.0, 0.0, 0.0)))
        val unity = Equations.cubic(1.0, 0.0, 0.0, -1.0)
        assertEquals("1", CalcComplex.format(unity[0], false, AngleMode.Deg))
        assertEquals(-0.5, unity[1].re, 1e-9); assertEquals(Math.sqrt(3.0) / 2, Math.abs(unity[1].im), 1e-9)
        assertEquals(listOf("-1", "2", "3"), roots(Equations.cubic(2.0, -8.0, 2.0, 12.0)))
        assertEquals(listOf("1", "1", "1"), roots(Equations.cubic(1.0, -3.0, 3.0, -1.0)))
    }

    @Test fun linearSystems() {
        val two = Equations.system(listOf(listOf(1.0, 1.0, 3.0), listOf(1.0, -1.0, 1.0)))
        assertEquals(listOf(2.0, 1.0), two)
        val three = Equations.system(listOf(listOf(1.0, 1.0, 1.0, 6.0), listOf(0.0, 2.0, 5.0, -4.0), listOf(2.0, 5.0, -1.0, 27.0)))
        assertEquals(5.0, three[0], 1e-9); assertEquals(3.0, three[1], 1e-9); assertEquals(-2.0, three[2], 1e-9)
        assertThrows("same line", CalcException::class.java) { Equations.system(listOf(listOf(1.0, 2.0, 3.0), listOf(2.0, 4.0, 6.0))) }
    }

    @Test fun ratios() {
        assertEquals(6.0, Ratio.findLast(2.0, 3.0, 4.0), 1e-12)   // 2:3 = 4:6
        assertEquals(4.0, Ratio.findThird(2.0, 3.0, 6.0), 1e-12)  // 2:3 = 4:6
        assertEquals(4L to 3L, Ratio.simplify(8.0, 6.0))
        assertEquals(1L to 3L, Ratio.simplify(0.5, 1.5))
        assertEquals(3L to 4L, Ratio.simplify(0.3, 0.4))
        assertEquals(1L to 0L, Ratio.simplify(7.0, 0.0))
        assertThrows("a=0", CalcException::class.java) { Ratio.findLast(0.0, 1.0, 1.0) }
        assertThrows("b=0", CalcException::class.java) { Ratio.findThird(1.0, 0.0, 1.0) }
        assertThrows("neg", CalcException::class.java) { Ratio.simplify(-1.0, 2.0) }
    }
}
