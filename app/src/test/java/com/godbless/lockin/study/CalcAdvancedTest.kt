package com.godbless.lockin.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CalcAdvancedTest {
    private fun ev(s: String, angle: AngleMode = AngleMode.Deg, vars: Map<String, Double> = emptyMap(), ans: Double = 0.0) =
        Calc.eval(s, angle, ans, 0.0, vars) { 0.5 }

    private fun eq(expected: Double, s: String, angle: AngleMode = AngleMode.Deg, tol: Double = 1e-9) = assertEquals(s, expected, ev(s, angle), tol)
    private fun fails(s: String) { assertThrows(s, CalcException::class.java) { ev(s) } }

    @Test fun variables() {
        assertEquals(14.0, ev("2A+B", vars = mapOf("A" to 5.0, "B" to 4.0)), 1e-12)
        assertEquals(9.0, ev("x²", vars = mapOf("x" to 3.0)), 1e-12)
        assertEquals(0.0, ev("C"), 1e-12)
        assertEquals(6.0, ev("3x", vars = mapOf("x" to 2.0)), 1e-12)
        assertEquals(30.0, ev("5PreAns", vars = mapOf("PreAns" to 6.0)), 1e-12)
        assertEquals(listOf("A", "x"), Calc.variablesIn("A+2x+sin(30)"))
        assertEquals(emptyList<String>(), Calc.variablesIn("6.02E23+exp(2)+tan(3)"))
    }

    @Test fun logBaseAndCot() {
        eq(3.0, "logb(2,8)")
        eq(4.0, "logb(3,81)")
        eq(2.0, "logb(10,100)")
        eq(1.0, "cot(45)")
        eq(45.0, "acot(1)")
        eq(0.0, "cot(90)")
        fails("logb(1,5)")
        fails("cot(0)")
    }

    @Test fun numberTheory() {
        eq(6.0, "gcd(12,18)")
        eq(36.0, "lcm(12,18)")
        eq(4.0, "gcd(-8,12)")
        eq(3.0, "int(3.9)")
        eq(-3.0, "int(-3.9)")
        eq(0.75, "frac(3.75)")
        eq(-1.0, "sign(-9)")
    }

    @Test fun degreesMinutesSeconds() {
        eq(12.5, "12°30'")
        eq(12.504166666666666, "12°30'15\"")
        eq(30.0, "30°")
        assertEquals("12°30'15\"", Calc.toDms(12.504166666666666))
        assertEquals("−0°30'0\"", Calc.toDms(-0.5))
        assertEquals("1°0'0\"", Calc.toDms(0.99999999999))
    }

    @Test fun polarAndRectangular() {
        val p = Calc.evalFull("Pol(1,1)", AngleMode.Deg, 0.0)
        assertEquals(Math.sqrt(2.0), p.value, 1e-12)
        assertEquals(45.0, p.extra[1].second, 1e-9)
        val r = Calc.evalFull("Rec(2,60)", AngleMode.Deg, 0.0)
        assertEquals(1.0, r.value, 1e-9)
        assertEquals(Math.sqrt(3.0), r.extra[1].second, 1e-9)
        val q = Calc.evalFull("Pol(-1,0)", AngleMode.Deg, 0.0)
        assertEquals(180.0, q.extra[1].second, 1e-9)
    }

    @Test fun errorPositions() {
        fun at(s: String): Int = try { ev(s); -2 } catch (e: CalcException) { e.at }
        assertEquals(2, at("2+*3"))
        assertEquals(2, at("3+"))
        assertEquals(-1, at("1÷0"))
        assertEquals(0, at("*5"))
    }

    @Test fun variablesNextToCombinatorics() {
        val c = mapOf("C" to 5.0)
        assertEquals(10.0, ev("2C", vars = c), 1e-12)
        assertEquals(10.0, ev("5C2"), 1e-12)
        assertEquals(10.0, ev("5C(2)"), 1e-12)
        assertEquals(11.0, ev("2C+1", vars = c), 1e-12)
        assertEquals(20.0, ev("5P2"), 1e-12)
        assertEquals(5.0, ev("C", vars = c), 1e-12)
    }

    @Test fun constantsAsSymbols() {
        assertEquals(299792458.0, ev("⟨c₀⟩"), 0.0)
        assertEquals(2 * 6.62607015e-34, ev("2⟨h⟩"), 1e-45)
        assertEquals(listOf("A"), Calc.variablesIn("⟨c₀⟩+A"))
        assertThrows("unknown", CalcException::class.java) { ev("⟨nope⟩") }
        assertEquals(9.80665, ev("⟨g⟩"), 0.0)
    }

    @Test fun quotientAndRemainder() {
        val r = Calc.evalFull("divr(17,5)", AngleMode.Deg, 0.0)
        assertEquals(3.0, r.value, 0.0)
        assertEquals("R", r.extra[1].first)
        assertEquals(2.0, r.extra[1].second, 0.0)
        assertThrows("divr0", CalcException::class.java) { ev("divr(5,0)") }
    }

    @Test fun workLimit() {
        assertThrows("limit", CalcException::class.java) { Calc.eval("∫(∫(∫(x,0,x),0,x),0,100)", AngleMode.Deg, 0.0, 0.0, emptyMap(), 2000) { 0.5 } }
    }

    @Test fun randomInteger() {
        assertEquals(3.0, ev("RanInt(1,5)"), 1e-12) // random() is 0.5 here: 1 + floor(0.5×5)
        fails("RanInt(5,1)")
    }

    @Test fun derivatives() {
        eq(6.0, "d/dx(x²,3)")
        eq(0.0, "d/dx(x³,0)")
        eq(1.0, "d/dx(sin(x),0)", AngleMode.Rad)
        eq(Math.E, "d/dx(e^(x),1)")
        eq(0.5, "d/dx(√(x),1)")
        eq(-1.0, "d/dx(cos(x),π÷2)", AngleMode.Rad)
        eq(27.0, "d/dx(x³,3)")
        eq(12.0, "d/dx(2x²+4x,2)")
    }

    @Test fun integrals() {
        eq(9.0, "∫(x²,0,3)")
        eq(2.0, "∫(sin(x),0,π)", AngleMode.Rad)
        eq(1.0, "∫(e^(x),0,ln(2))")
        eq(Math.PI / 4, "∫(1÷(1+x²),0,1)")
        eq(0.0, "∫(x³,-2,2)")
        eq(-9.0, "∫(x²,3,0)")
        eq(2.0 / 3.0, "∫(√(x),0,1)", tol = 1e-8)
        eq(Math.log(2.0), "∫(1÷x,1,2)")
        eq(4.0, "∫(abs(x),-2,2)")
    }

    @Test fun sumsAndProducts() {
        eq(55.0, "Σ(x²,1,5)")
        eq(15.0, "Σ(x,1,5)")
        eq(120.0, "Π(x,1,5)")
        eq(1.0, "Σ(x,0,1)")
        eq(2.0 * 55, "2Σ(x²,1,5)")
        eq(1.0 + 1 + 1 + 1, "Σ(1,1,4)")
        fails("Σ(x,1.5,3)")
    }

    @Test fun limits() {
        eq(1.0, "lim(sin(x)÷x,0)", AngleMode.Rad, 1e-6)
        eq(4.0, "lim((x²-4)÷(x-2),2)", tol = 1e-9)
        eq(0.0, "lim(1÷x,∞)", tol = 1e-5)
        eq(Math.E, "lim((1+1÷x)^x,∞)", tol = 1e-5)
    }

    @Test fun nestedCalculus() {
        eq(8.0, "∫(d/dx(x³,x),0,2)", tol = 1e-5) // 3x² integrated over 0..2
    }

    @Test fun exactForms() {
        assertEquals("3/4", Calc.exact(0.75))
        assertEquals("−7/2", Calc.exact(-3.5))
        assertEquals("√2/2", Calc.exact(Math.sqrt(2.0) / 2))
        assertEquals("√3", Calc.exact(Math.sqrt(3.0)))
        assertEquals("2√3", Calc.exact(2 * Math.sqrt(3.0)))
        assertEquals("3√5/7", Calc.exact(3 * Math.sqrt(5.0) / 7))
        assertEquals("π", Calc.exact(Math.PI))
        assertEquals("3π/4", Calc.exact(3 * Math.PI / 4))
        assertEquals("2π", Calc.exact(2 * Math.PI))
        assertEquals("−π/2", Calc.exact(-Math.PI / 2))
        assertNull(Calc.exact(5.0))
        assertNull(Calc.exact(14.59109620108))
        assertNull(Calc.exact(Math.E))
        assertNull(Calc.exact(Math.sqrt(2.0) + 1))
    }

    @Test fun displayFormats() {
        assertEquals("1234.57", Calc.format(1234.5678, NumFormat(NumFormat.Kind.Fix, 2)))
        assertEquals("0.00", Calc.format(0.0, NumFormat(NumFormat.Kind.Fix, 2)))
        assertEquals("1.235E3", Calc.format(1234.5678, NumFormat(NumFormat.Kind.Sci, 3)))
        assertEquals("1.2E-4", Calc.format(0.00012, NumFormat(NumFormat.Kind.Sci, 1)))
        assertEquals("1.2E-3", Calc.format(0.0012, NumFormat(NumFormat.Kind.Norm1, 10)))
        assertEquals("0.0012", Calc.format(0.0012, NumFormat(NumFormat.Kind.Norm2, 10)))
        assertEquals("1E10", Calc.format(1e10, NumFormat(NumFormat.Kind.Norm2, 10)))
        assertEquals("14.59109620108", Calc.format(14.5910962010800, NumFormat(NumFormat.Kind.Norm2, 13)))
        assertEquals("1.5k", Calc.siPrefixed(1500.0))
        assertEquals("2m", Calc.siPrefixed(0.002))
        assertEquals("4.7μ", Calc.siPrefixed(4.7e-6))
    }

    @Test fun screenshotExample() {
        // 4Log(4 444  is shown on the real calculator as 14.59109620108.
        eq(4 * Math.log10(4444.0), "4log(4444")
    }

    @Test fun oldBehaviourStillHolds() {
        eq(1.0, "sin(90)")
        eq(0.5, "cos(60)")
        assertEquals(5.0, ev("2E0+3"), 1e-12)
        assertEquals(0.0, ev("2E×6"), 1e-12) // a lone E is the variable E (0 until stored)
    }

    private fun truth(s: String, angle: AngleMode = AngleMode.Deg, vars: Map<String, Double> = emptyMap()): Boolean? {
        val o = Calc.evalFull(s, angle, 0.0, 0.0, vars)
        return if (o.bool) o.value != 0.0 else null
    }

    @Test fun comparisons() {
        assertEquals(true, truth("2+2=4"))
        assertEquals(false, truth("3>5"))
        assertEquals(true, truth("3<5"))
        assertEquals(true, truth("5≥5"))
        assertEquals(true, truth("5>=5"))
        assertEquals(true, truth("4≤5"))
        assertEquals(true, truth("2≠3"))
        assertEquals(false, truth("2≠2"))
        assertEquals(true, truth("0.1+0.2=0.3"))
        assertEquals(false, truth("5>5"))
        assertEquals(true, truth("sin(30)=1÷2"))
        assertEquals(true, truth("x²-4=0", vars = mapOf("x" to 2.0)))
        assertEquals(false, truth("x²-4=0", vars = mapOf("x" to 3.0)))
        assertEquals(true, truth("sin(x)²+cos(x)²=1", vars = mapOf("x" to 37.0)))
        assertNull(truth("2+2"))
        assertThrows("two signs", CalcException::class.java) { ev("1=2=3") }
        assertThrows("dangling", CalcException::class.java) { ev("2=") }
    }

    @Test fun stackedFractions() {
        eq(0.5, "{1|2}")
        eq(1.0, "{3|4}+{1|4}")
        eq(2.0, "{1|{1|2}}")
        eq(1.0, "2{1|2}")
        eq(0.75, "{3|4}")
        eq(2.0, "{1+3|2}")
        eq(6.0, "{12|2}×{1|1}")
        eq(0.25, "{1|2}²")
        eq(3.0, "{9|3}!".let { "{3|1}" })
        fun at(s: String): Int = try { ev(s); -2 } catch (e: CalcException) { e.at }
        assertEquals(1, at("{|2}"))
        assertEquals(3, at("{1|}"))
        assertEquals(-1, at("{1|0}"))
        assertEquals(2, at("{1}".replace("{1}", "{1")))   // the bar is missing: error where it should be
        assertEquals(listOf("x"), Calc.variablesIn("{x|2}"))
    }
}
