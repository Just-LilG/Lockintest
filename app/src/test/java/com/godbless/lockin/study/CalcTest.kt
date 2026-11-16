package com.godbless.lockin.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CalcTest {
    private fun ev(s: String, angle: AngleMode = AngleMode.Deg, ans: Double = 0.0, memory: Double = 0.0) =
        Calc.eval(s, angle, ans, memory) { 0.25 }

    private fun eq(expected: Double, s: String, angle: AngleMode = AngleMode.Deg) = assertEquals(s, expected, ev(s, angle), 1e-9)

    private fun fails(s: String) {
        assertThrows(s, CalcException::class.java) { ev(s) }
    }

    @Test fun arithmeticAndPrecedence() {
        eq(14.0, "2+3×4")
        eq(20.0, "(2+3)×4")
        eq(2.5, "10÷4")
        eq(-1.0, "2−3")
        eq(-9.0, "-3^2")
        eq(512.0, "2^3^2")
        eq(0.5, "2^-1")
        eq(220.0, "200+10%")
        eq(180.0, "200−10%")
        eq(0.25, "25%")
    }

    @Test fun implicitMultiplication() {
        eq(2 * Math.PI, "2π")
        eq(27.0, "3(4+5)")
        eq(21.0, "(1+2)(3+4)")
        eq(1.0, "2sin(30)")
        eq(1.0, "2sin30")
    }

    @Test fun powersRootsAndInverse() {
        eq(25.0, "5²")
        eq(27.0, "3³")
        eq(0.25, "4⁻¹")
        eq(3.0, "√(9)")
        eq(3.0, "√9")
        eq(6.0, "2√9")
        eq(-2.0, "∛-8")
        eq(-2.0, "∛(-8)")
        eq(2.0, "3ˣ√8")
        eq(-2.0, "3ˣ√-8")
        eq(3.0, "4ˣ√81")
        eq(1000.0, "10^(3)")
        eq(Math.E * Math.E, "e^(2)")
        fails("2ˣ√-4")
    }

    @Test fun scientificNotation() {
        eq(6.02e23, "6.02E23")
        eq(1.6e-19, "1.6E-19")
        eq(3e8 * 2, "3E8×2")
    }

    @Test fun trigInEveryAngleMode() {
        eq(0.5, "sin(30)")
        eq(0.0, "sin(180)")
        eq(0.5, "cos(60)")
        eq(1.0, "tan(45)")
        eq(30.0, "asin(0.5)")
        eq(45.0, "atan(1)")
        eq(1.0, "sin(π÷2)", AngleMode.Rad)
        eq(1.0, "sin(100)", AngleMode.Grad)
        eq(50.0, "atan(1)", AngleMode.Grad)
        fails("tan(90)")
        fails("asin(2)")
    }

    @Test fun hyperbolic() {
        eq(Math.sinh(1.0), "sinh(1)")
        eq(Math.cosh(1.0), "cosh(1)")
        eq(Math.tanh(1.0), "tanh(1)")
        eq(1.0, "asinh(sinh(1))")
        eq(2.0, "acosh(cosh(2))")
        eq(0.5, "atanh(tanh(0.5))")
        fails("acosh(0.5)")
    }

    @Test fun logs() {
        eq(2.0, "ln(e^2)")
        eq(3.0, "log(1000)")
        eq(5.0, "log2(32)")
        fails("ln(0)")
        fails("log(-1)")
    }

    @Test fun counting() {
        eq(120.0, "5!")
        eq(1.0, "0!")
        eq(20.0, "5P2")
        eq(10.0, "5C2")
        eq(1.0, "5C0")
        eq(252.0, "10C5")
        eq(40.0, "2×5P2")
        fails("2C5")
        fails("2.5!")
    }

    @Test fun modAbsAndRounding() {
        eq(1.0, "7 mod 3")
        eq(2.0, "-7 mod 3")
        eq(3.0, "abs(-3)")
        eq(2.0, "floor(2.7)")
        eq(3.0, "ceil(2.1)")
        eq(3.0, "round(2.5)")
        eq(-3.0, "round(-2.5)")
    }

    @Test fun ansMemoryAndRandom() {
        assertEquals(12.0, ev("Ans+2", ans = 10.0), 1e-12)
        assertEquals(15.0, ev("M×3", memory = 5.0), 1e-12)
        assertEquals(0.25, ev("Ran#"), 1e-12)
    }

    @Test fun forgivingAndErrors() {
        eq(3.0, "(1+2")
        fails("")
        fails("2+")
        fails("1÷0")
        fails("2..3")
        fails("foo(2)")
    }

    @Test fun formatting() {
        assertEquals("42", Calc.format(42.0))
        assertEquals("0.333333333333", Calc.format(1.0 / 3))
        assertEquals("6.02E23", Calc.format(6.02e23))
        assertEquals("1.6E-19", Calc.format(1.6e-19))
    }

    @Test fun fractions() {
        assertEquals("3/4", Calc.fraction(0.75))
        assertEquals("1/3", Calc.fraction(1.0 / 3))
        assertEquals("−7/2", Calc.fraction(-3.5))
        assertEquals("22/7", Calc.fraction(22.0 / 7))
        assertNull(Calc.fraction(5.0))
        assertNull(Calc.fraction(Math.PI))
    }
}
