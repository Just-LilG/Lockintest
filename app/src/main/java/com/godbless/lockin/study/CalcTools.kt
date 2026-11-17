package com.godbless.lockin.study

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

// The big calculator modes, kept apart from the expression engine in Calc.kt:
// SOLVE, CONST, CONV, FACT, BASE-N, STAT, DISTR, MATRIX, VECTOR and CMPLX.

// ---- SOLVE ---------------------------------------------------------------------------------

internal class SolveResult(val x: Double, val residual: Double)

internal object Solver {
    /**
     * Finds a value of [variable] that makes the left side equal the right side (or the expression zero when
     * there is no "="), starting near [guess], like SOLVE on a scientific calculator.
     */
    fun solve(
        text: String,
        variable: String = "x",
        guess: Double = 0.0,
        angle: AngleMode = AngleMode.Deg,
        ans: Double = 0.0,
        memory: Double = 0.0,
        vars: Map<String, Double> = emptyMap(),
    ): SolveResult {
        val at = text.indexOf('=')
        val g = if (at >= 0) "(${text.substring(0, at)})-(${text.substring(at + 1)})" else text
        fun f(x: Double): Double = try {
            Calc.eval(g, angle, ans, memory, vars + (variable to x))
        } catch (e: CalcException) {
            Double.NaN
        }
        // An identity (3=3, x=x) is true for every x, so there is no single answer to give.
        if (doubleArrayOf(0.0, 1.0, -1.0, 2.5, -7.0).all { f(guess + it) == 0.0 }) throw CalcException("True for every x")
        // A real root: f grows away from it. If f barely changes either side (like e^x near −30) it is only
        // sliding towards zero and there is no solution there.
        fun genuine(x: Double): Boolean {
            val fx = abs(f(x))
            val d = 1e-3 * maxOf(1.0, abs(x))
            val l = abs(f(x - d)); val r = abs(f(x + d))
            return maxOf(if (l.isNaN()) 0.0 else l, if (r.isNaN()) 0.0 else r) > 10 * fx
        }
        fun done(x: Double): SolveResult {
            val tidy = Calc.snap(x)
            val fx = f(tidy)
            return if (!fx.isNaN() && abs(fx) <= abs(f(x)) + 1e-15) SolveResult(tidy, fx) else SolveResult(x, f(x))
        }

        // Newton's method from the guess.
        var x = guess
        var ok = false
        for (n in 0 until 80) {
            val fx = f(x)
            if (fx.isNaN()) break
            if (abs(fx) < 1e-13) { ok = true; break }
            val h = 1e-6 * maxOf(1.0, abs(x))
            val d = (f(x + h) - f(x - h)) / (2 * h)
            if (d.isNaN() || d == 0.0) break
            val next = x - fx / d
            if (next.isNaN() || next.isInfinite()) break
            if (abs(next - x) < 1e-14 * maxOf(1.0, abs(x))) { x = next; ok = abs(f(x)) < 1e-8; break }
            x = next
        }
        // A "root" this far out is usually a function sliding towards zero (like 1÷x), not a real solution.
        if ((ok || abs(f(x)) < 1e-9) && abs(x) < 1e8 && genuine(x)) return done(x)

        // Otherwise look for a sign change in ever wider windows around the guess, then narrow it down.
        var radius = 1.0
        while (radius <= 1e6) {
            val steps = 400
            var prevX = guess - radius
            var prevF = f(prevX)
            for (k in 1..steps) {
                val cx = guess - radius + 2 * radius * k / steps
                val cf = f(cx)
                if (!prevF.isNaN() && !cf.isNaN() && (prevF == 0.0 || prevF * cf < 0)) {
                    var lo = prevX; var hi = cx; var flo = prevF
                    repeat(200) {
                        val mid = (lo + hi) / 2
                        val fm = f(mid)
                        if (fm.isNaN()) return@repeat
                        if (flo * fm <= 0) hi = mid else { lo = mid; flo = fm }
                    }
                    val root = (lo + hi) / 2
                    // A jump (like 1÷x at 0) changes sign without crossing zero: skip those.
                    // (A value that is exactly 0 only because the function underflowed, like e^x far out, is not a root.)
                    if (abs(f(root)) < 1e-6 * maxOf(1.0, abs(prevF), abs(cf)) && genuine(root)) return done(root)
                }
                prevX = cx; prevF = cf
            }
            radius *= 4
        }
        throw CalcException("Can't solve")
    }
}

// ---- FACT ----------------------------------------------------------------------------------

internal object NumberTheory {
    private const val SUP = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    /** 360 becomes 2³×3²×5. A prime (or 1) is returned as itself. Null for anything not a whole number from 2 up to 10 digits. */
    fun primeFactors(value: Double): String? {
        if (value != floor(value) || value < 2 || value > 9_999_999_999.0) return null
        var n = value.toLong()
        val parts = mutableListOf<String>()
        var p = 2L
        while (p * p <= n) {
            if (n % p == 0L) {
                var e = 0
                while (n % p == 0L) { n /= p; e++ }
                parts += p.toString() + (if (e > 1) e.toString().map { SUP[it - '0'] }.joinToString("") else "")
            }
            p += if (p == 2L) 1 else 2
        }
        if (n > 1) parts += n.toString()
        return parts.joinToString("×")
    }
}

// ---- CONST ---------------------------------------------------------------------------------

internal class PhysConst(val no: Int, val name: String, val symbol: String, val value: Double, val unit: String)

/** Physical constants (CODATA 2018, the exact SI values where they are exact). */
internal object Constants {
    val all: List<PhysConst> = listOf(
        PhysConst(1, "Speed of light in vacuum", "c₀", 299792458.0, "m/s"),
        PhysConst(2, "Planck constant", "h", 6.62607015e-34, "J·s"),
        PhysConst(3, "Reduced Planck constant", "ħ", 1.054571817e-34, "J·s"),
        PhysConst(4, "Elementary charge", "e", 1.602176634e-19, "C"),
        PhysConst(5, "Boltzmann constant", "k", 1.380649e-23, "J/K"),
        PhysConst(6, "Avogadro constant", "NA", 6.02214076e23, "1/mol"),
        PhysConst(7, "Molar gas constant", "R", 8.314462618, "J/(mol·K)"),
        PhysConst(8, "Faraday constant", "F", 96485.33212, "C/mol"),
        PhysConst(9, "Gravitational constant", "G", 6.6743e-11, "m³/(kg·s²)"),
        PhysConst(10, "Standard gravity", "g", 9.80665, "m/s²"),
        PhysConst(11, "Electric constant", "ε₀", 8.8541878128e-12, "F/m"),
        PhysConst(12, "Magnetic constant", "μ₀", 1.25663706212e-6, "N/A²"),
        PhysConst(13, "Electron mass", "me", 9.1093837015e-31, "kg"),
        PhysConst(14, "Proton mass", "mp", 1.67262192369e-27, "kg"),
        PhysConst(15, "Neutron mass", "mn", 1.67492749804e-27, "kg"),
        PhysConst(16, "Muon mass", "mμ", 1.883531627e-28, "kg"),
        PhysConst(17, "Atomic mass constant", "u", 1.6605390666e-27, "kg"),
        PhysConst(18, "Bohr radius", "a₀", 5.29177210903e-11, "m"),
        PhysConst(19, "Classical electron radius", "re", 2.8179403262e-15, "m"),
        PhysConst(20, "Compton wavelength", "λc", 2.42631023867e-12, "m"),
        PhysConst(21, "Fine-structure constant", "α", 7.2973525693e-3, ""),
        PhysConst(22, "Rydberg constant", "R∞", 10973731.56816, "1/m"),
        PhysConst(23, "Bohr magneton", "μB", 9.2740100783e-24, "J/T"),
        PhysConst(24, "Nuclear magneton", "μN", 5.0507837461e-27, "J/T"),
        PhysConst(25, "Magnetic flux quantum", "Φ₀", 2.067833848e-15, "Wb"),
        PhysConst(26, "Conductance quantum", "G₀", 7.748091729e-5, "S"),
        PhysConst(27, "Von Klitzing constant", "RK", 25812.80745, "Ω"),
        PhysConst(28, "Josephson constant", "KJ", 4.835978484e14, "Hz/V"),
        PhysConst(29, "Impedance of vacuum", "Z₀", 376.730313668, "Ω"),
        PhysConst(30, "Stefan–Boltzmann constant", "σ", 5.670374419e-8, "W/(m²·K⁴)"),
        PhysConst(31, "First radiation constant", "c₁", 3.741771852e-16, "W·m²"),
        PhysConst(32, "Second radiation constant", "c₂", 1.438776877e-2, "m·K"),
        PhysConst(33, "Wien displacement constant", "b", 2.897771955e-3, "m·K"),
        PhysConst(34, "Molar volume of ideal gas (0 °C, 1 atm)", "Vm", 2.241396954e-2, "m³/mol"),
        PhysConst(35, "Standard atmosphere", "atm", 101325.0, "Pa"),
    )

    /** The constant as it is typed into a sum: its symbol in angle brackets, like ⟨c₀⟩. */
    fun insertText(c: PhysConst): String = "⟨" + c.symbol + "⟩"
}

// ---- CONV ----------------------------------------------------------------------------------

/** One unit conversion: result = [scale] × value + [offset]. */
internal class Conversion(val no: Int, val from: String, val to: String, val scale: Double, val offset: Double = 0.0) {
    val label get() = "$from▸$to"
    fun apply(x: Double) = scale * x + offset
}

internal object Conversions {
    private class Pair2(val a: String, val b: String, val scale: Double, val offset: Double = 0.0)

    private val base = listOf(
        Pair2("in", "cm", 2.54),
        Pair2("ft", "m", 0.3048),
        Pair2("yd", "m", 0.9144),
        Pair2("mile", "km", 1.609344),
        Pair2("n mile", "m", 1852.0),
        Pair2("acre", "m²", 4046.8564224),
        Pair2("gal (US)", "L", 3.785411784),
        Pair2("gal (UK)", "L", 4.54609),
        Pair2("pc", "km", 3.0856775814913673e13),
        Pair2("ly", "km", 9.4607304725808e12),
        Pair2("km/h", "m/s", 1.0 / 3.6),
        Pair2("oz", "g", 28.349523125),
        Pair2("lb", "kg", 0.45359237),
        Pair2("atm", "Pa", 101325.0),
        Pair2("mmHg", "Pa", 133.322387415),
        Pair2("hp", "kW", 0.745699871582),
        Pair2("kgf/cm²", "Pa", 98066.5),
        Pair2("kgf·m", "J", 9.80665),
        Pair2("lbf/in²", "kPa", 6.894757293168),
        Pair2("°F", "°C", 5.0 / 9.0, -160.0 / 9.0),
        Pair2("°C", "K", 1.0, 273.15),
        Pair2("cal", "J", 4.1868),
    )

    /** Each pair works both ways, so the list has two entries per pair (in▸cm and cm▸in). */
    val all: List<Conversion> = buildList {
        var n = 1
        for (p in base) {
            add(Conversion(n++, p.a, p.b, p.scale, p.offset))
            add(Conversion(n++, p.b, p.a, 1.0 / p.scale, -p.offset / p.scale))
        }
    }
}

// ---- BASE-N --------------------------------------------------------------------------------

internal enum class Base(val radix: Int, val label: String, val bits: Int) { Dec(10, "DEC", 32), Hex(16, "HEX", 32), Bin(2, "BIN", 16), Oct(8, "OCT", 32) }

/** Whole-number sums in binary, octal, decimal and hex, with and/or/xor/xnor/not/neg, as on a calculator's BASE-N mode. */
internal object BaseN {
    private fun max(b: Base) = (1L shl (b.bits - 1)) - 1
    private fun min(b: Base) = -(1L shl (b.bits - 1))

    /** Shows a value in the base; negative numbers appear as two's complement (FFFFFFFB for −5 in hex). */
    fun format(v: Long, base: Base): String {
        if (base == Base.Dec) return v.toString().replace('-', '−')
        val mask = (1L shl base.bits) - 1
        val u = if (v < 0) v and mask else v
        val digitsFull = when (base) { Base.Bin -> 16; Base.Hex -> 8; else -> 11 }
        val s = u.toString(base.radix).uppercase()
        return if (v < 0) s.padStart(digitsFull, if (base == Base.Hex) 'F' else if (base == Base.Bin) '1' else '7') else s
    }

    private class P(val s: String, val base: Base) {
        var i = 0
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(): Char? { skip(); return if (i < s.length) s[i] else null }
        fun word(w: String): Boolean { skip(); return s.startsWith(w, i) }

        fun range(v: Long): Long {
            if (v > max(base) || v < min(base)) throw CalcException("Math error")
            return v
        }

        fun logic(): Long {
            var l = and()
            while (true) {
                l = when {
                    word("xnor") -> { i += 4; range(and().let { (l xor it).inv() }) }
                    word("xor") -> { i += 3; range(l xor and()) }
                    word("or") -> { i += 2; range(l or and()) }
                    else -> return l
                }
            }
        }

        fun and(): Long {
            var l = sum()
            while (word("and")) { i += 3; l = range(l and sum()) }
            return l
        }

        fun sum(): Long {
            var l = product()
            while (true) {
                val c = peek()
                if (c == '+') { i++; l = range(l + product()) }
                else if (c == '-' || c == '−') { i++; l = range(l - product()) }
                else return l
            }
        }

        fun product(): Long {
            var l = unary()
            while (true) {
                val c = peek()
                if (c == '×' || c == '*') { i++; l = range(l * unary()) }
                else if (c == '÷' || c == '/') {
                    i++
                    val d = unary()
                    if (d == 0L) throw CalcException("Can't divide by 0")
                    l = l / d
                } else return l
            }
        }

        fun unary(): Long {
            val c = peek() ?: throw CalcException("Syntax error")
            if (c == '-' || c == '−') { i++; return range(-unary()) }
            if (word("not(")) { i += 4; val v = logic(); close(); return range(v.inv()) }
            if (word("neg(")) { i += 4; val v = logic(); close(); return range(-v) }
            if (c == '(') { i++; val v = logic(); close(); return v }
            return number()
        }

        fun close() { if (peek() == ')') i++ }

        fun number(): Long {
            skip()
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in 'A'..'F')) i++
            if (i == start) throw CalcException("Syntax error")
            val text = s.substring(start, i)
            if (text.any { Character.digit(it, base.radix) < 0 }) throw CalcException("Syntax error")
            val raw = text.toLongOrNull(base.radix) ?: throw CalcException("Math error")
            // Typing a negative number in binary, octal or hex means two's complement.
            return when {
                base == Base.Dec -> range(raw)
                raw > max(base) && raw <= (1L shl base.bits) - 1 -> raw - (1L shl base.bits)
                else -> range(raw)
            }
        }
    }

    fun eval(text: String, base: Base): Long {
        val p = P(text, base)
        val v = try { p.logic() } catch (e: StackOverflowError) { throw CalcException("Too complex") }
        p.skip()
        if (p.i < text.length) throw CalcException("Syntax error")
        return v
    }
}

// ---- STAT ----------------------------------------------------------------------------------

internal class OneVar(
    val n: Double, val sumX: Double, val sumX2: Double, val mean: Double,
    val popSd: Double, val sampleSd: Double, val min: Double, val max: Double,
    val q1: Double, val median: Double, val q3: Double,
)

internal enum class Reg(val label: String) { Linear("a+bx"), Quadratic("a+bx+cx²"), Log("a+b·ln x"), Exp("a·e^(bx)"), Power("a·x^b"), Inverse("a+b/x") }

internal class RegResult(val type: Reg, val a: Double, val b: Double, val c: Double = 0.0, val r: Double? = null) {
    /** The estimated y for an x. */
    fun predictY(x: Double): Double = when (type) {
        Reg.Linear -> a + b * x
        Reg.Quadratic -> a + b * x + c * x * x
        Reg.Log -> a + b * ln(x)
        Reg.Exp -> a * exp(b * x)
        Reg.Power -> a * x.pow(b)
        Reg.Inverse -> a + b / x
    }

    /** The estimated x(s) for a y: two answers are possible for a quadratic. */
    fun predictX(y: Double): List<Double> = when (type) {
        Reg.Linear -> listOf((y - a) / b)
        Reg.Quadratic -> {
            val d = b * b - 4 * c * (a - y)
            if (c == 0.0 || d < 0) emptyList()
            else listOf((-b + sqrt(d)) / (2 * c), (-b - sqrt(d)) / (2 * c))
        }
        Reg.Log -> listOf(exp((y - a) / b))
        Reg.Exp -> listOf(ln(y / a) / b)
        Reg.Power -> listOf((y / a).pow(1.0 / b))
        Reg.Inverse -> listOf(b / (y - a))
    }
}

internal object Stat {
    private fun expand(xs: List<Double>, freq: List<Double>?): List<Double> {
        if (freq == null) return xs
        val out = ArrayList<Double>()
        for ((k, x) in xs.withIndex()) {
            val f = freq.getOrElse(k) { 1.0 }
            if (f < 0 || f != floor(f)) throw CalcException("Math error")
            repeat(f.toInt()) { out += x }
            if (out.size > 100_000) throw CalcException("Too big")
        }
        return out
    }

    private fun median(sorted: List<Double>): Double {
        val m = sorted.size
        return if (m % 2 == 1) sorted[m / 2] else (sorted[m / 2 - 1] + sorted[m / 2]) / 2
    }

    /** One-variable statistics. Quartiles leave out the middle value when there is an odd number of values. */
    fun oneVar(xs: List<Double>, freq: List<Double>? = null): OneVar {
        val d = expand(xs, freq).sorted()
        if (d.isEmpty()) throw CalcException("Math error")
        val n = d.size.toDouble()
        val sum = d.sum()
        val sum2 = d.sumOf { it * it }
        val mean = sum / n
        val ss = d.sumOf { (it - mean) * (it - mean) }
        val half = d.size / 2
        val lower = d.subList(0, half)
        val upper = d.subList(if (d.size % 2 == 1) half + 1 else half, d.size)
        return OneVar(
            n, sum, sum2, mean, sqrt(ss / n), if (n > 1) sqrt(ss / (n - 1)) else Double.NaN, d.first(), d.last(),
            if (lower.isEmpty()) d.first() else median(lower), median(d), if (upper.isEmpty()) d.last() else median(upper),
        )
    }

    private fun line(us: List<Double>, vs: List<Double>): Triple<Double, Double, Double> {
        val n = us.size.toDouble()
        val su = us.sum(); val sv = vs.sum()
        val suu = us.sumOf { it * it }; val svv = vs.sumOf { it * it }
        val suv = us.indices.sumOf { us[it] * vs[it] }
        val den = n * suu - su * su
        if (den == 0.0) throw CalcException("Math error")
        val b = (n * suv - su * sv) / den
        val a = (sv - b * su) / n
        val rd = sqrt((n * suu - su * su) * (n * svv - sv * sv))
        val r = if (rd == 0.0) Double.NaN else (n * suv - su * sv) / rd
        return Triple(a, b, r)
    }

    /** Fits a curve through the (x, y) pairs. */
    fun regress(type: Reg, xs: List<Double>, ys: List<Double>): RegResult {
        if (xs.size != ys.size || xs.size < 2) throw CalcException("Math error")
        return when (type) {
            Reg.Linear -> line(xs, ys).let { (a, b, r) -> RegResult(type, a, b, r = r) }
            Reg.Log -> { if (xs.any { it <= 0 }) throw CalcException("Math error"); line(xs.map { ln(it) }, ys).let { (a, b, r) -> RegResult(type, a, b, r = r) } }
            Reg.Exp -> { if (ys.any { it <= 0 }) throw CalcException("Math error"); line(xs, ys.map { ln(it) }).let { (a, b, r) -> RegResult(type, exp(a), b, r = r) } }
            Reg.Power -> { if (xs.any { it <= 0 } || ys.any { it <= 0 }) throw CalcException("Math error"); line(xs.map { ln(it) }, ys.map { ln(it) }).let { (a, b, r) -> RegResult(type, exp(a), b, r = r) } }
            Reg.Inverse -> { if (xs.any { it == 0.0 }) throw CalcException("Math error"); line(xs.map { 1 / it }, ys).let { (a, b, r) -> RegResult(type, a, b, r = r) } }
            Reg.Quadratic -> {
                if (xs.size < 3) throw CalcException("Math error")
                fun s(k: Int) = xs.sumOf { it.pow(k) }
                fun t(k: Int) = xs.indices.sumOf { xs[it].pow(k) * ys[it] }
                val m = Mat(3, 4, doubleArrayOf(
                    xs.size.toDouble(), s(1), s(2), t(0),
                    s(1), s(2), s(3), t(1),
                    s(2), s(3), s(4), t(2),
                ))
                val sol = m.solveAugmented()
                RegResult(type, sol[0], sol[1], sol[2])
            }
        }
    }
}

// ---- DISTR ---------------------------------------------------------------------------------

internal object Distr {
    /** The complementary error function, accurate to about 1e-15. */
    fun erfc(x: Double): Double {
        if (x < 0) return 2 - erfc(-x)
        if (x < 3.0) {
            // erf(x) = 2/√π · e^(−x²) · Σ 2ⁿ x^(2n+1) / (1·3·5…(2n+1)): all terms positive, so no cancellation.
            var term = x
            var sum = x
            var n = 0
            while (abs(term) > 1e-17 * abs(sum) && n < 400) {
                n++
                term *= 2 * x * x / (2 * n + 1)
                sum += term
            }
            return 1 - 2 / sqrt(PI) * exp(-x * x) * sum
        }
        // Continued fraction for the tail.
        var f = 0.0
        for (k in 60 downTo 1) f = (k / 2.0) / (x + f)
        return exp(-x * x) / sqrt(PI) / (x + f)
    }

    /** The standard normal curve's area to the left of z. */
    fun phi(z: Double) = 0.5 * erfc(-z / sqrt(2.0))

    fun normalPdf(x: Double, sd: Double = 1.0, mean: Double = 0.0): Double {
        if (sd <= 0) throw CalcException("Math error")
        val z = (x - mean) / sd
        return exp(-z * z / 2) / (sd * sqrt(2 * PI))
    }

    fun normalCdf(lower: Double, upper: Double, sd: Double = 1.0, mean: Double = 0.0): Double {
        if (sd <= 0) throw CalcException("Math error")
        return phi((upper - mean) / sd) - phi((lower - mean) / sd)
    }

    /** The x with the given area to its left (InvN). */
    fun invNormal(area: Double, sd: Double = 1.0, mean: Double = 0.0): Double {
        if (area <= 0 || area >= 1 || sd <= 0) throw CalcException("Math error")
        // Acklam's approximation, then one Halley step.
        val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
        val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
        val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
        val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
        val pLow = 0.02425
        var x = when {
            area < pLow -> { val q = sqrt(-2 * ln(area)); (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1) }
            area > 1 - pLow -> { val q = sqrt(-2 * ln(1 - area)); -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1) }
            else -> { val q = area - 0.5; val r = q * q; (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1) }
        }
        val e = phi(x) - area
        val u = e * sqrt(2 * PI) * exp(x * x / 2)
        x -= u / (1 + x * u / 2)
        return mean + sd * x
    }

    private fun lgamma(x: Double): Double {
        val g = doubleArrayOf(0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7)
        if (x < 0.5) return ln(PI / abs(sin(PI * x))) - lgamma(1 - x)
        val y = x - 1
        var s = g[0]
        val t = y + 7.5
        for (i in 1..8) s += g[i] / (y + i)
        return 0.5 * ln(2 * PI) + (y + 0.5) * ln(t) - t + ln(s)
    }

    private fun whole(x: Double): Long {
        if (x < 0 || x != floor(x) || x > 1e9) throw CalcException("Math error")
        return x.toLong()
    }

    fun binomialPdf(x: Double, n: Double, p: Double): Double {
        val k = whole(x); val m = whole(n)
        if (p < 0 || p > 1 || k > m) throw CalcException("Math error")
        if (p == 0.0) return if (k == 0L) 1.0 else 0.0
        if (p == 1.0) return if (k == m) 1.0 else 0.0
        if (m <= 1000) {
            var c = 1.0
            val r = minOf(k, m - k)
            for (j in 1..r) c = c * (m - r + j) / j
            return c * p.pow(k.toDouble()) * (1 - p).pow((m - k).toDouble())
        }
        return exp(lgamma(m + 1.0) - lgamma(k + 1.0) - lgamma(m - k + 1.0) + k * ln(p) + (m - k) * ln(1 - p))
    }

    fun binomialCdf(x: Double, n: Double, p: Double): Double {
        val k = whole(x)
        var s = 0.0
        for (j in 0..k) s += binomialPdf(j.toDouble(), n, p)
        return minOf(1.0, s)
    }

    fun poissonPdf(x: Double, lambda: Double): Double {
        val k = whole(x)
        if (lambda <= 0) throw CalcException("Math error")
        return exp(k * ln(lambda) - lambda - lgamma(k + 1.0))
    }

    fun poissonCdf(x: Double, lambda: Double): Double {
        val k = whole(x)
        var s = 0.0
        for (j in 0..k) s += poissonPdf(j.toDouble(), lambda)
        return minOf(1.0, s)
    }
}

// ---- MATRIX and VECTOR ---------------------------------------------------------------------

/** A matrix of up to 4×4 (this class has no limit, the screen does). */
internal class Mat(val rows: Int, val cols: Int, val a: DoubleArray) {
    init { require(a.size == rows * cols) }

    operator fun get(r: Int, c: Int) = a[r * cols + c]

    operator fun plus(o: Mat): Mat { same(o); return Mat(rows, cols, DoubleArray(a.size) { a[it] + o.a[it] }) }
    operator fun minus(o: Mat): Mat { same(o); return Mat(rows, cols, DoubleArray(a.size) { a[it] - o.a[it] }) }
    operator fun times(k: Double) = Mat(rows, cols, DoubleArray(a.size) { a[it] * k })
    operator fun times(o: Mat): Mat {
        if (cols != o.rows) throw CalcException("Dimension error")
        val out = DoubleArray(rows * o.cols)
        for (i in 0 until rows) for (j in 0 until o.cols) {
            var s = 0.0
            for (k in 0 until cols) s += this[i, k] * o[k, j]
            out[i * o.cols + j] = s
        }
        return Mat(rows, o.cols, out)
    }

    private fun same(o: Mat) { if (rows != o.rows || cols != o.cols) throw CalcException("Dimension error") }

    fun transpose() = Mat(cols, rows, DoubleArray(a.size) { this[it % rows, it / rows] })

    fun det(): Double {
        if (rows != cols) throw CalcException("Dimension error")
        val m = a.copyOf()
        val n = rows
        var d = 1.0
        // A pivot this small next to the biggest entry is rounding noise, so the matrix is singular:
        // [[1,2,3],[4,5,6],[7,8,9]] has determinant 0, not 6.7E-16.
        val noise = 1e-12 * (a.maxOfOrNull { abs(it) } ?: 0.0)
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r * n + c]) > abs(m[p * n + c])) p = r
            if (abs(m[p * n + c]) <= noise) return 0.0
            if (p != c) { for (k in 0 until n) { val t = m[c * n + k]; m[c * n + k] = m[p * n + k]; m[p * n + k] = t }; d = -d }
            d *= m[c * n + c]
            for (r in c + 1 until n) {
                val f = m[r * n + c] / m[c * n + c]
                for (k in c until n) m[r * n + k] -= f * m[c * n + k]
            }
        }
        return Calc.snap(d)
    }

    fun inverse(): Mat {
        if (rows != cols) throw CalcException("Dimension error")
        val n = rows
        val aug = Mat(n, 2 * n, DoubleArray(n * 2 * n) { idx -> val r = idx / (2 * n); val c = idx % (2 * n); if (c < n) this[r, c] else if (c - n == r) 1.0 else 0.0 })
        val sol = aug.gaussJordan() ?: throw CalcException("Math error")
        return Mat(n, n, DoubleArray(n * n) { sol[it / n * (2 * n) + n + it % n] }.map { Calc.snap(it) }.toDoubleArray())
    }

    fun pow(k: Int): Mat {
        if (rows != cols) throw CalcException("Dimension error")
        if (k < 0) return inverse().pow(-k)
        var result = identity(rows)
        var base = this
        var e = k
        while (e > 0) { if (e and 1 == 1) result = result * base; base = base * base; e = e shr 1 }
        return result
    }

    /** Row-reduces in place on a copy; null when the left square part is singular. Returns the whole reduced array. */
    internal fun gaussJordan(): DoubleArray? {
        val m = a.copyOf()
        val n = rows
        val w = cols
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r * w + c]) > abs(m[p * w + c])) p = r
            if (abs(m[p * w + c]) < 1e-14) return null
            if (p != c) for (k in 0 until w) { val t = m[c * w + k]; m[c * w + k] = m[p * w + k]; m[p * w + k] = t }
            val piv = m[c * w + c]
            for (k in 0 until w) m[c * w + k] /= piv
            for (r in 0 until n) if (r != c) {
                val f = m[r * w + c]
                if (f != 0.0) for (k in 0 until w) m[r * w + k] -= f * m[c * w + k]
            }
        }
        return m
    }

    /** Solves an n × (n+1) system (the last column is the right-hand side). */
    fun solveAugmented(): DoubleArray {
        val m = gaussJordan() ?: throw CalcException("Math error")
        return DoubleArray(rows) { m[it * cols + (cols - 1)] }
    }

    companion object {
        fun identity(n: Int) = Mat(n, n, DoubleArray(n * n) { if (it / n == it % n) 1.0 else 0.0 })
    }
}

internal class Vec(val v: DoubleArray) {
    val size get() = v.size
    operator fun plus(o: Vec): Vec { same(o); return Vec(DoubleArray(size) { v[it] + o.v[it] }) }
    operator fun minus(o: Vec): Vec { same(o); return Vec(DoubleArray(size) { v[it] - o.v[it] }) }
    operator fun times(k: Double) = Vec(DoubleArray(size) { v[it] * k })
    private fun same(o: Vec) { if (size != o.size) throw CalcException("Dimension error") }

    fun dot(o: Vec): Double { same(o); return Calc.snap(v.indices.sumOf { v[it] * o.v[it] }) }
    fun norm(): Double = Calc.snap(sqrt(v.sumOf { it * it }))

    fun cross(o: Vec): Vec {
        if (size != 3 || o.size != 3) throw CalcException("Dimension error")
        return Vec(doubleArrayOf(v[1] * o.v[2] - v[2] * o.v[1], v[2] * o.v[0] - v[0] * o.v[2], v[0] * o.v[1] - v[1] * o.v[0]))
    }

    /** The angle between two vectors, in radians. */
    fun angle(o: Vec): Double {
        val d = norm() * o.norm()
        if (d == 0.0) throw CalcException("Math error")
        return kotlin.math.acos((dot(o) / d).coerceIn(-1.0, 1.0))
    }

    fun unit(): Vec { val n = norm(); if (n == 0.0) throw CalcException("Math error"); return Vec(DoubleArray(size) { v[it] / n }) }
}

// ---- CMPLX ---------------------------------------------------------------------------------

internal data class Cx(val re: Double, val im: Double = 0.0) {
    operator fun plus(o: Cx) = Cx(re + o.re, im + o.im)
    operator fun minus(o: Cx) = Cx(re - o.re, im - o.im)
    operator fun times(o: Cx) = Cx(re * o.re - im * o.im, re * o.im + im * o.re)
    operator fun div(o: Cx): Cx {
        val d = o.re * o.re + o.im * o.im
        if (d == 0.0) throw CalcException("Can't divide by 0")
        return Cx((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
    }
    fun abs() = hypot(re, im)
    fun arg() = atan2(im, re)
    fun conj() = Cx(re, -im)
    fun sqrt(): Cx {
        val r = abs()
        return Cx(sqrt((r + re) / 2), (if (im < 0) -1.0 else 1.0) * sqrt(maxOf(0.0, (r - re) / 2)))
    }
    fun exp(): Cx { val e = kotlin.math.exp(re); return Cx(e * cos(im), e * sin(im)) }
    fun ln(): Cx { if (re == 0.0 && im == 0.0) throw CalcException("Math error"); return Cx(kotlin.math.ln(abs()), arg()) }

    fun pow(o: Cx): Cx {
        if (o.im == 0.0 && o.re == floor(o.re) && abs(o.re) <= 64) {
            var n = abs(o.re).toInt()
            var r = Cx(1.0); var b = this
            while (n > 0) { if (n and 1 == 1) r = r * b; b = b * b; n = n shr 1 }
            return if (o.re < 0) Cx(1.0) / r else r
        }
        if (re == 0.0 && im == 0.0) return if (o.re > 0) Cx(0.0) else throw CalcException("Math error")
        return (o * ln()).exp()
    }

    companion object {
        fun polar(r: Double, theta: Double) = Cx(r * cos(theta), r * sin(theta))
    }
}

/** Sums with i: (1+2i)(3−i), √(−4), 5∠30, |3+4i|. */
internal object CalcComplex {
    private val names = listOf("sqrt", "abs", "arg", "conj", "Re", "Im", "exp", "ln", "sin", "cos", "Ans", "e", "i").sortedByDescending { it.length }

    fun eval(text: String, angle: AngleMode, ans: Cx = Cx(0.0)): Cx {
        val p = P(text.replace('−', '-'), angle, ans)
        val v = try { p.expr() } catch (e: StackOverflowError) { throw CalcException("Too complex") }
        p.skip()
        if (p.i < p.s.length) throw CalcException("Syntax error")
        return tidy(v)
    }

    private fun tidy(c: Cx): Cx {
        fun t(x: Double, other: Double) = if (abs(x) < 1e-12 * maxOf(1.0, abs(other))) 0.0 else Calc.snap(x)
        return Cx(t(c.re, c.im), t(c.im, c.re))
    }

    /** a+bi, or r∠θ when [polar] is set. */
    fun format(c: Cx, polar: Boolean, angle: AngleMode, fmt: NumFormat = NumFormat()): String {
        if (polar) {
            val th = if (c.re == 0.0 && c.im == 0.0) 0.0 else c.arg() * angle.perRadian
            return Calc.format(c.abs(), fmt) + "∠" + Calc.format(Calc.snap(th), fmt)
        }
        if (c.im == 0.0) return Calc.format(c.re, fmt)
        val size = abs(c.im)
        val imag = if (size == 1.0) "i" else Calc.format(size, fmt) + "i"
        if (c.re == 0.0) return (if (c.im < 0) "−" else "") + imag
        return Calc.format(c.re, fmt) + (if (c.im < 0) "−" else "+") + imag
    }

    private class P(val s: String, val angle: AngleMode, val ans: Cx) {
        var i = 0
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(): Char? { skip(); return if (i < s.length) s[i] else null }
        fun starts(w: String): Boolean { skip(); return s.startsWith(w, i) }

        fun expr(): Cx {
            var l = term()
            while (true) {
                val c = peek()
                if (c == '+') { i++; l += term() } else if (c == '-') { i++; l -= term() } else return l
            }
        }

        fun term(): Cx {
            var l = unary()
            while (true) {
                val c = peek()
                when {
                    c == '×' || c == '*' -> { i++; l *= unary() }
                    c == '÷' || c == '/' -> { i++; l /= unary() }
                    c == '∠' -> { i++; l = Cx.polar(l.re, unary().re / angle.perRadian) }
                    c != null && (c == '(' || c == 'π' || c == '√' || c.isDigit() || c == '.' || c.isLetter()) -> l *= unary()
                    else -> return l
                }
            }
        }

        fun unary(): Cx {
            val c = peek()
            if (c == '-') { i++; return Cx(0.0) - unary() }
            if (c == '+') { i++; return unary() }
            return power()
        }

        fun power(): Cx {
            val b = postfix()
            if (peek() == '^') { i++; return b.pow(unary()) }
            return b
        }

        fun postfix(): Cx {
            var v = primary()
            while (true) {
                when {
                    peek() == '²' -> { i++; v *= v }
                    peek() == '³' -> { i++; v = v * v * v }
                    starts("⁻¹") -> { i += 2; v = Cx(1.0) / v }
                    else -> return v
                }
            }
        }

        fun primary(): Cx {
            val c = peek() ?: throw CalcException("Syntax error")
            if (c == '(') { i++; val v = expr(); if (peek() == ')') i++; return v }
            if (c.isDigit() || c == '.') return Cx(number())
            if (c == 'π') { i++; return Cx(PI) }
            if (c == '√') { i++; return arg().sqrt() }
            if (c.isLetter()) {
                val n = names.firstOrNull { s.startsWith(it, i) } ?: throw CalcException("Syntax error")
                i += n.length
                return when (n) {
                    "i" -> Cx(0.0, 1.0)
                    "e" -> Cx(Math.E)
                    "Ans" -> ans
                    else -> fn(n, arg())
                }
            }
            throw CalcException("Syntax error")
        }

        fun arg(): Cx {
            if (peek() == '(') { i++; val v = expr(); if (peek() == ')') i++; return v }
            return postfix()
        }

        fun fn(n: String, z: Cx): Cx = when (n) {
            "sqrt" -> z.sqrt()
            "abs" -> Cx(z.abs())
            "arg" -> Cx(z.arg() * angle.perRadian)
            "conj" -> z.conj()
            "Re" -> Cx(z.re)
            "Im" -> Cx(z.im)
            "exp" -> z.exp()
            "ln" -> z.ln()
            "sin" -> { val w = Cx(z.re / angle.perRadian, z.im / angle.perRadian); Cx(sin(w.re) * cosh(w.im), cos(w.re) * sinh(w.im)) }
            "cos" -> { val w = Cx(z.re / angle.perRadian, z.im / angle.perRadian); Cx(cos(w.re) * cosh(w.im), -sin(w.re) * sinh(w.im)) }
            else -> throw CalcException("Syntax error")
        }

        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (s.substring(start, i).count { it == '.' } > 1) throw CalcException("Syntax error")
            if (i < s.length && s[i] == 'E') {
                val save = i
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                val d = i
                while (i < s.length && s[i].isDigit()) i++
                if (i == d) i = save
            }
            val t = s.substring(start, i).let { if (it.startsWith(".")) "0$it" else it }
            return t.toDoubleOrNull() ?: throw CalcException("Syntax error")
        }
    }
}


// ---- EQN -----------------------------------------------------------------------------------

/** Equations: quadratics and cubics (real and complex roots), and systems of two or three linear equations. */
internal object Equations {
    private fun tidy(c: Cx): Cx {
        fun t(x: Double, other: Double) = if (abs(x) < 1e-9 * maxOf(1.0, abs(other))) 0.0 else Calc.snap(x)
        return Cx(t(c.re, c.im), t(c.im, c.re))
    }

    /** ax² + bx + c = 0, real roots first. */
    fun quadratic(a: Double, b: Double, c: Double): List<Cx> {
        if (a == 0.0) throw CalcException("Math error")
        val d = b * b - 4 * a * c
        if (d >= 0) {
            val sq = sqrt(d)
            val q = -0.5 * (b + (if (b < 0) -sq else sq))
            // The stable form avoids losing digits when b is large.
            val r1 = if (q != 0.0) q / a else 0.0
            val r2 = if (q != 0.0) c / q else 0.0
            return listOf(r1, r2).sorted().map { tidy(Cx(it)) }
        }
        val re = -b / (2 * a)
        val im = sqrt(-d) / (2 * abs(a))
        return listOf(tidy(Cx(re, im)), tidy(Cx(re, -im)))
    }

    /** ax³ + bx² + cx + d = 0 by Cardano's method in complex numbers. */
    fun cubic(a: Double, b: Double, c: Double, d: Double): List<Cx> {
        if (a == 0.0) throw CalcException("Math error")
        val bb = b / a; val cc = c / a; val dd = d / a
        val p = cc - bb * bb / 3
        val q = 2 * bb * bb * bb / 27 - bb * cc / 3 + dd
        val shift = -bb / 3
        val root = Cx(q * q / 4 + p * p * p / 27).sqrt()
        var u3 = Cx(-q / 2) + root
        if (u3.abs() < 1e-14) u3 = Cx(-q / 2) - root
        val ts: List<Cx> = if (u3.abs() < 1e-14) {
            listOf(Cx(0.0), Cx(0.0), Cx(0.0))
        } else {
            val u = Cx.polar(Math.cbrt(u3.abs()), u3.arg() / 3)
            val v = Cx(-p / 3) / u
            val w = Cx(-0.5, sqrt(3.0) / 2)
            val w2 = w * w
            listOf(u + v, u * w + v * w2, u * w2 + v * w)
        }
        return ts.map { tidy(it + Cx(shift)) }.sortedWith(compareBy({ abs(it.im) > 0 }, { it.re }))
    }

    /** Solves two or three linear equations. [rows] holds each equation's coefficients then its right-hand side. */
    fun system(rows: List<List<Double>>): List<Double> {
        val n = rows.size
        if (n !in 2..3 || rows.any { it.size != n + 1 }) throw CalcException("Math error")
        val m = Mat(n, n + 1, DoubleArray(n * (n + 1)) { rows[it / (n + 1)][it % (n + 1)] })
        val sol = try { m.solveAugmented() } catch (e: CalcException) { throw CalcException("No single answer") }
        return sol.map { Calc.snap(it) }
    }
}


// ---- RATIO ---------------------------------------------------------------------------------

internal object Ratio {
    /** A : B = C : X, so X = B × C ÷ A. */
    fun findLast(a: Double, b: Double, c: Double): Double {
        if (a == 0.0) throw CalcException("A can't be 0")
        return Calc.snap(b * c / a)
    }

    /** A : B = X : D, so X = A × D ÷ B. */
    fun findThird(a: Double, b: Double, d: Double): Double {
        if (b == 0.0) throw CalcException("B can't be 0")
        return Calc.snap(a * d / b)
    }

    /** A : B in its smallest whole numbers (decimals up to 6 places are scaled up first). */
    fun simplify(a: Double, b: Double): Pair<Long, Long> {
        if (a < 0 || b < 0 || a.isNaN() || b.isNaN() || (a == 0.0 && b == 0.0)) throw CalcException("Math error")
        var scale = 1.0
        var k = 0
        while (k < 6 && (a * scale != floor(a * scale + 1e-9) || b * scale != floor(b * scale + 1e-9))) {
            // allow tiny float noise (0.1 × 10 is exactly 1, but 0.3 × 10 is not exactly 3)
            if (abs(a * scale - round(a * scale)) < 1e-9 && abs(b * scale - round(b * scale)) < 1e-9) break
            scale *= 10; k++
        }
        val x = round(a * scale).toLong()
        val y = round(b * scale).toLong()
        if (abs(a * scale - x) > 1e-6 || abs(b * scale - y) > 1e-6) throw CalcException("Math error")
        val g = Calc.gcdLong(x, y)
        return if (g == 0L) x to y else (x / g) to (y / g)
    }
}
