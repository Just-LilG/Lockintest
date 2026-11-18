package com.godbless.lockin.study

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** Why an expression could not be worked out. */
/** [at] is where in the typed sum things went wrong (so the cursor can jump there), or -1 when unknown. */
internal class CalcException(message: String, val at: Int = -1) : Exception(message)

/** How angles are measured for trig functions. */
internal enum class AngleMode(val label: String, val perRadian: Double) {
    Deg("Deg", 180.0 / PI),
    Rad("Rad", 1.0),
    Grad("Grad", 200.0 / PI),
}

/** How answers are written: NORM 1 / NORM 2 (plain until very big or small), FIX n decimals, SCI n digits. */
internal data class NumFormat(val kind: Kind = Kind.Norm2, val digits: Int = 12) {
    enum class Kind { Norm1, Norm2, Fix, Sci }

    val label: String
        get() = when (kind) {
            Kind.Norm1 -> "NORM-1"
            Kind.Norm2 -> "NORM-2"
            Kind.Fix -> "FIX-$digits"
            Kind.Sci -> "SCI-$digits"
        }
}

/** An answer plus any extra results (Pol and Rec give two numbers: r and θ, or x and y). */
internal class Outcome(val value: Double, val extra: List<Pair<String, Double>> = emptyList(), val bool: Boolean = false)

/**
 * Works out what is typed into the calculator, like a scientific calculator would.
 *
 * Operators: + − × ÷, ^ (power), ˣ√ (root: 3ˣ√8 = 2), mod, nPr and nCr (5P2, 5C2), and the
 * postfix ² ³ ⁻¹ ! %. Numbers can use E for powers of ten (6.02E23) and ° ' " for degrees,
 * minutes and seconds (12°30'15"). Brackets can be left open.
 * Things written next to each other multiply (2π, 3(4+5), 2sin(30), 3A).
 * A percentage after + or − is a share of the left side, so 200+10% is 220.
 *
 * Functions: sin cos tan cot and their inverses (asin…, acot), hyperbolic sinh cosh tanh and inverses
 * (asinh…), ln, log (base 10), log2, logb(base,x), sqrt, cbrt, abs, exp, floor, ceil, round, int, frac,
 * sign, gcd(a,b), lcm(a,b), Pol(x,y), Rec(r,θ), RanInt(a,b).
 * Calculus (the variable inside is x): d/dx(f,at), ∫(f,from,to), Σ(f,from,to), Π(f,from,to), lim(f,at).
 * Names: π, e, Ans, PreAns, M (memory), Ran#, ∞, and the variables A B C D E F x y z t.
 */
internal object Calc {
    private val functions = setOf(
        "sin", "cos", "tan", "cot", "asin", "acos", "atan", "acot",
        "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
        "ln", "log", "log2", "sqrt", "cbrt", "abs", "exp", "floor", "ceil", "round", "int", "frac", "sign",
    )

    /** Functions that take several values, or part of the expression itself. */
    private val multi = setOf("logb", "gcd", "lcm", "Pol", "Rec", "RanInt", "divr", "d/dx", "∫", "Σ", "Π", "lim")

    /** The memory letters you can store into and recall. */
    val variableNames = listOf("A", "B", "C", "D", "E", "F", "x", "y", "z", "t")

    /** Longest name first, so "asinh" wins over "asin" and "sin". */
    private val names = (functions + multi + variableNames + setOf("Ans", "PreAns", "Ran#", "mod", "e", "M")).sortedByDescending { it.length }

    fun eval(
        text: String,
        angle: AngleMode,
        ans: Double,
        memory: Double = 0.0,
        vars: Map<String, Double> = emptyMap(),
        maxWork: Int = 400_000,
        random: () -> Double = Math::random,
    ): Double = evalFull(text, angle, ans, memory, vars, maxWork, random).value

    /** Like [eval], but also hands back the second result of Pol( and Rec(. */
    fun evalFull(
        text: String,
        angle: AngleMode,
        ans: Double,
        memory: Double = 0.0,
        vars: Map<String, Double> = emptyMap(),
        maxWork: Int = 400_000,
        random: () -> Double = Math::random,
    ): Outcome {
        val p = Parser(normalise(text), angle, ans, memory, vars, random, Budget(maxWork))
        // Thousands of nested brackets (a long paste) would overflow the stack and crash the app.
        val v = try { p.parseAll() } catch (e: StackOverflowError) { throw CalcException("Too complex") }
        if (v.isNaN()) throw CalcException("Math error")
        if (v.isInfinite()) throw CalcException("Too big")
        return Outcome(v, p.extra, p.isBool)
    }

    /** Old callers that only know degrees on or off. */
    fun eval(text: String, degrees: Boolean, ans: Double): Double = eval(text, if (degrees) AngleMode.Deg else AngleMode.Rad, ans)

    private fun normalise(t: String) = t.replace('−', '-')

    /** Which of the letter variables a piece of text uses, in the order of [variableNames]. */
    fun variablesIn(text: String): List<String> {
        val used = mutableSetOf<String>()
        var i = 0
        val s = normalise(text)
        while (i < s.length) {
            if (s[i] == '⟨') { i = s.indexOf('⟩', i).let { if (it < 0) s.length else it + 1 }; continue }
            val name = if (s[i].isLetter() || s[i] == '∫') names.firstOrNull { s.startsWith(it, i) } else null
            if (name != null) {
                if (name in variableNames && !(name == "E" && i > 0 && (s[i - 1].isDigit() || s[i - 1] == '.'))) used += name
                i += name.length
            } else i++
        }
        return variableNames.filter { it in used }
    }

    // ---- Writing answers -------------------------------------------------------------------

    /** The answer as short text: plain digits when they fit, scientific for very large or small numbers. */
    fun format(value: Double): String {
        if (value == 0.0) return "0"
        val a = abs(value)
        if (a >= 1e12 || a < 1e-9) return scientific(value, 10)
        return BigDecimal(value).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    /** The answer in the chosen display format (NORM, FIX or SCI). */
    fun format(value: Double, fmt: NumFormat): String {
        // NaN and infinity have no digits: BigDecimal would throw a NumberFormatException the pages don't catch.
        if (value.isNaN() || value.isInfinite()) throw CalcException("Math error")
        if (value == 0.0) return if (fmt.kind == NumFormat.Kind.Fix) String.format(Locale.US, "%.${fmt.digits}f", 0.0) else if (fmt.kind == NumFormat.Kind.Sci) "0." + "0".repeat(fmt.digits) + "E0" else "0"
        val a = abs(value)
        return when (fmt.kind) {
            NumFormat.Kind.Fix -> {
                val s = String.format(Locale.US, "%.${fmt.digits}f", value)
                if (s.trimStart('-').all { it == '0' || it == '.' }) s.trimStart('-') else s
            }
            NumFormat.Kind.Sci -> {
                val s = String.format(Locale.US, "%.${fmt.digits}e", value)
                s.substringBefore('e') + "E" + s.substringAfter('e').toInt()
            }
            NumFormat.Kind.Norm1 ->
                if (a >= 1e10 || a < 1e-2) scientific(value, fmt.digits) else plain(value, fmt.digits)
            NumFormat.Kind.Norm2 ->
                if (a >= 1e10 || a < 1e-9) scientific(value, fmt.digits) else plain(value, fmt.digits)
        }
    }

    private fun plain(value: Double, digits: Int) = BigDecimal(value).round(MathContext(digits)).stripTrailingZeros().toPlainString()

    private fun scientific(value: Double, digits: Int): String {
        val s = String.format(Locale.US, "%.${(digits - 1).coerceAtLeast(0)}e", value)
        val mantissa = s.substringBefore('e').let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }
        return "${mantissa}E${s.substringAfter('e').toInt()}"
    }

    /** Degrees, minutes, seconds: 12.5041667 becomes 12°30'15". */
    fun toDms(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return format(value)
        val neg = value < 0
        val total = (abs(value) * 3600.0 * 1000.0).let { round(it) } / 1000.0 // seconds, to 3 places
        var d = floor(total / 3600.0).toLong()
        var rem = total - d * 3600.0
        var m = floor(rem / 60.0).toLong()
        var sec = rem - m * 60.0
        sec = round(sec * 100.0) / 100.0
        if (sec >= 60.0) { sec -= 60.0; m += 1 }
        if (m >= 60) { m -= 60; d += 1 }
        val s = BigDecimal(sec).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return (if (neg) "−" else "") + "$d°$m'$s\""
    }

    /** 1500 becomes 1.5k, 0.002 becomes 2m. */
    fun siPrefixed(value: Double): String {
        if (value == 0.0 || value.isNaN() || value.isInfinite()) return format(value)
        val prefixes = listOf("a", "f", "p", "n", "μ", "m", "", "k", "M", "G", "T", "P", "E")
        val e = (floor(log10(abs(value)) / 3.0)).toInt().coerceIn(-6, 6)
        val mantissa = value / 10.0.pow(e * 3)
        return format(mantissa) + prefixes[e + 6]
    }

    /**
     * The answer as a fraction (3/4, −7/2), or null if it isn't a simple one.
     * Uses continued fractions and only accepts denominators up to 10 000.
     */
    fun fraction(value: Double): String? {
        if (value.isNaN() || value.isInfinite() || abs(value) >= 1e9) return null
        if (value == floor(value)) return null // a whole number is already as simple as it gets
        val sign = if (value < 0) "−" else ""
        val x = abs(value)
        var h0 = 0L; var h1 = 1L; var k0 = 1L; var k1 = 0L
        var r = x
        repeat(40) {
            val a = floor(r).toLong()
            val h2 = a * h1 + h0
            val k2 = a * k1 + k0
            if (k2 > 10_000) return null
            h0 = h1; h1 = h2; k0 = k1; k1 = k2
            if (abs(x - h1.toDouble() / k1) < 1e-10 * maxOf(1.0, x)) {
                return "$sign$h1/$k1"
            }
            val frac = r - a
            if (frac < 1e-12) return null
            r = 1 / frac
        }
        return null
    }

    /** x as numerator over denominator (denominator up to [maxDen]) if it matches to within [tol], else null. */
    internal fun rational(x: Double, maxDen: Long, tol: Double): Pair<Long, Long>? {
        if (x.isNaN() || x.isInfinite() || abs(x) > 1e12) return null
        val neg = x < 0
        val ax = abs(x)
        var h0 = 0L; var h1 = 1L; var k0 = 1L; var k1 = 0L
        var r = ax
        repeat(40) {
            val a = floor(r).toLong()
            val h2 = a * h1 + h0
            val k2 = a * k1 + k0
            if (k2 > maxDen) return null
            h0 = h1; h1 = h2; k0 = k1; k1 = k2
            if (abs(ax - h1.toDouble() / k1) <= tol) return (if (neg) -h1 else h1) to k1
            val frac = r - a
            if (frac < 1e-13) return null
            r = 1 / frac
        }
        return null
    }

    /**
     * The answer in exact "natural" form: 3/4, 3π/4, √2/2, 3√5/7. Null when the answer is a whole number
     * or is not a simple exact value (so the decimal is what you see).
     */
    fun exact(value: Double): String? {
        if (value.isNaN() || value.isInfinite() || value == 0.0 || abs(value) > 1e9) return null
        if (value == floor(value)) return null
        val neg = value < 0
        val sign = if (neg) "−" else ""
        val ax = abs(value)
        // a fraction
        rational(ax, 1000, 1e-12 * maxOf(1.0, ax))?.let { (n, d) -> if (d > 1) return "$sign$n/$d" }
        // a multiple of π
        rational(ax / PI, 360, 1e-12 * maxOf(1.0, ax / PI))?.let { (n, d) ->
            if (n != 0L) {
                val top = if (n == 1L) "π" else "${n}π"
                return if (d == 1L) "$sign$top" else "$sign$top/$d"
            }
        }
        // a root: a√b / c
        val s = ax * ax
        rational(s, 200, 1e-11 * maxOf(1.0, s))?.let { (p, q) ->
            val inside = p * q
            if (inside in 2..100_000_000L) {
                var a = 1L
                var b = inside
                var f = 2L
                while (f * f <= b) {
                    while (b % (f * f) == 0L) { b /= f * f; a *= f }
                    f++
                }
                if (b > 1) {
                    val g = gcdLong(a, q)
                    val num = a / g
                    val den = q / g
                    val top = (if (num == 1L) "" else "$num") + "√$b"
                    return if (den == 1L) "$sign$top" else "$sign$top/$den"
                }
            }
        }
        return null
    }

    internal fun gcdLong(a: Long, b: Long): Long {
        var x = abs(a); var y = abs(b)
        while (y != 0L) { val t = x % y; x = y; y = t }
        return x
    }

    /** Tidies a computed value: 5.999999999999 → 6, so d/dx and ∫ answers look right. */
    internal fun snap(v: Double): Double {
        if (v.isNaN() || v.isInfinite() || v == 0.0) return v
        val r = BigDecimal(v).round(MathContext(10)).toDouble()
        return if (abs(r - v) <= 1e-9 * maxOf(1.0, abs(v))) r else v
    }

    private class Value(val v: Double, val percent: Boolean = false)

    /** Stops runaway work (huge sums, deep integrals). */
    private class Budget(var left: Int)

    private class Parser(
        private val s: String,
        private val angle: AngleMode,
        private val ans: Double,
        private val memory: Double,
        private val vars: Map<String, Double>,
        private val random: () -> Double,
        private val budget: Budget,
    ) {
        private var i = 0
        var extra: List<Pair<String, Double>> = emptyList()

        /** True when the sum was a comparison (2+2=4, 3>5): the answer is then 1 for true and 0 for false. */
        var isBool = false

        fun parseAll(): Double {
            var v = expr().v
            skip()
            if (i < s.length) {
                val op = comparator() ?: throw CalcException("Syntax error", i)
                val r = expr().v
                skip()
                if (i < s.length) throw CalcException("Syntax error", i)
                val same = abs(v - r) <= 1e-9 * maxOf(1.0, abs(v), abs(r))
                val yes = when (op) {
                    "=" -> same
                    "≠" -> !same
                    ">" -> v > r && !same
                    "<" -> v < r && !same
                    "≥" -> v > r || same
                    else -> v < r || same
                }
                v = if (yes) 1.0 else 0.0
                isBool = true
            }
            return v
        }

        /** Reads =, ≠, >, <, ≥, ≤ (or the typed forms !=, >=, <=) at the cursor, if there is one. */
        private fun comparator(): String? {
            skip()
            val rest = s.substring(i)
            val table = listOf("≥" to "≥", ">=" to "≥", "≤" to "≤", "<=" to "≤", "≠" to "≠", "!=" to "≠", ">" to ">", "<" to "<", "=" to "=")
            val hit = table.firstOrNull { rest.startsWith(it.first) } ?: return null
            i += hit.first.length
            return hit.second
        }

        private fun skip() {
            while (i < s.length && s[i] == ' ') i++
        }

        private fun peek(): Char? {
            skip()
            return if (i < s.length) s[i] else null
        }

        private fun startsWith(word: String): Boolean {
            skip()
            return s.startsWith(word, i)
        }

        /** expr = term (('+' | '-') term)* */
        private fun expr(): Value {
            var left = term()
            while (true) {
                val c = peek()
                if (c == '+' || c == '-') {
                    i++
                    val right = term()
                    // 200 + 10% means 200 plus a tenth of 200.
                    val r = if (right.percent) left.v * right.v else right.v
                    left = Value(if (c == '+') left.v + r else left.v - r)
                } else {
                    return left
                }
            }
        }

        /** term = combo (('×' | '÷' | 'mod') combo | implicit combo)* */
        private fun term(): Value {
            var left = combo()
            while (true) {
                val c = peek()
                when {
                    c == '×' || c == '*' -> { i++; left = Value(left.v * combo().v) }
                    c == '÷' || c == '/' -> {
                        i++
                        val d = combo().v
                        if (d == 0.0) throw CalcException("Can't divide by 0")
                        left = Value(left.v / d)
                    }
                    startsWith("mod") -> {
                        i += 3
                        val d = combo().v
                        if (d == 0.0) throw CalcException("Can't divide by 0")
                        // The remainder has the sign of the divisor, as on most calculators.
                        left = Value(left.v - d * floor(left.v / d))
                    }
                    startsOperand(c) -> left = Value(left.v * combo().v)
                    else -> return left
                }
            }
        }

        private fun startsOperand(c: Char?): Boolean =
            c != null && (c == '(' || c == '{' || c == 'π' || c == '√' || c == '∛' || c == '∞' || c == '∫' || c == '⟨' || c.isDigit() || c == '.' ||
                (c.isLetter() && !startsWith("mod") && !startsWith("ˣ√")))

        /** combo = unary (('P' | 'C') unary)*   nPr and nCr bind tighter than × and ÷. */
        private fun combo(): Value {
            var left = unary()
            while (true) {
                val c = peek()
                if (((c == 'P' && !startsWith("PreAns") && !startsWith("Pol")) || c == 'C') && operandAfter(i + 1)) {
                    i++
                    val r = unary().v
                    left = Value(if (c == 'P') permutations(left.v, r) else combinations(left.v, r))
                } else {
                    return left
                }
            }
        }

        /** Is a number (or bracket) next? 5C2 is nCr, but 2C on its own is 2 times the variable C. */
        private fun operandAfter(from: Int): Boolean {
            var k = from
            while (k < s.length && s[k] == ' ') k++
            return k < s.length && (s[k].isDigit() || s[k] == '.' || s[k] == '(' || s[k] == 'π')
        }

        /** unary = '-' unary | '+' unary | power */
        private fun unary(): Value {
            val c = peek()
            if (c == '-') { i++; return Value(-unary().v) }
            if (c == '+') { i++; return unary() }
            return power()
        }

        /** power = postfix ('^' unary | 'ˣ√' unary)?   right to left, so 2^3^2 is 2^9. */
        private fun power(): Value {
            val base = postfix()
            if (peek() == '^') {
                i++
                val exponent = unary().v
                val r = base.v.pow(exponent)
                if (r.isNaN()) throw CalcException("Math error")
                return Value(r)
            }
            if (startsWith("ˣ√")) {
                i += 2
                return Value(root(base.v, unary().v))
            }
            return base
        }

        /** postfix = primary ('!' | '%' | '²' | '³' | '⁻¹')* */
        private fun postfix(): Value {
            var v = primary()
            while (true) {
                when {
                    peek() == '!' -> { i++; v = Value(factorial(v.v)) }
                    peek() == '%' -> { i++; v = Value(v.v / 100.0, percent = true) }
                    peek() == '²' -> { i++; v = Value(v.v * v.v) }
                    peek() == '³' -> { i++; v = Value(v.v * v.v * v.v) }
                    startsWith("⁻¹") -> {
                        i += 2
                        if (v.v == 0.0) throw CalcException("Can't divide by 0")
                        v = Value(1.0 / v.v)
                    }
                    else -> return v
                }
            }
        }

        private fun primary(): Value {
            val c = peek() ?: throw CalcException("Syntax error", i)
            if (c == '(') {
                i++
                val inner = expr()
                if (peek() == ')') i++ // a missing closing bracket is forgiven
                return Value(inner.v)
            }
            if (c.isDigit() || c == '.') return number()
            if (c == 'π') { i++; return Value(PI) }
            if (c == '∞') { i++; return Value(Double.POSITIVE_INFINITY) }
            if (c == '{') {
                // A stacked fraction from the a/b key: {top|bottom}.
                i++
                val top = expr().v
                if (peek() != '|') throw CalcException("Syntax error", i)
                i++
                val bottom = expr().v
                if (peek() == '}') i++ // a missing closing mark is forgiven
                if (bottom == 0.0) throw CalcException("Can't divide by 0")
                return Value(top / bottom)
            }
            if (c == '⟨') {
                // A physical constant written as its symbol: ⟨c₀⟩, ⟨h⟩, ⟨G⟩.
                val end = s.indexOf('⟩', i)
                val sym = if (end > i) s.substring(i + 1, end) else ""
                val k = Constants.all.firstOrNull { it.symbol == sym } ?: throw CalcException("Syntax error", i)
                i = end + 1
                return Value(k.value)
            }
            // √ and ∛ written as symbols: √9, √(9), ∛-8.
            if (c == '√' || c == '∛') {
                i++
                return Value(apply(if (c == '√') "sqrt" else "cbrt", argument()))
            }
            if (c.isLetter() || c == '∫') {
                val name = names.firstOrNull { s.startsWith(it, i) } ?: throw CalcException("Syntax error", i)
                i += name.length
                return when (name) {
                    "e" -> Value(Math.E)
                    "Ans" -> Value(ans)
                    "PreAns" -> Value(vars["PreAns"] ?: 0.0)
                    "M" -> Value(memory)
                    "Ran#" -> Value(random())
                    "mod" -> throw CalcException("Syntax error", i)
                    in multi -> callMulti(name)
                    in variableNames -> Value(vars[name] ?: 0.0)
                    else -> Value(apply(name, argument()))
                }
            }
            throw CalcException("Syntax error", i)
        }

        // ---- Functions with several values -------------------------------------------------

        private fun close() {
            if (peek() == ')') i++ // a missing closing bracket is forgiven
        }

        private fun comma() {
            if (peek() != ',') throw CalcException("Syntax error", i)
            i++
        }

        /** The text of the first argument, up to the comma that ends it (so x² or sin(x)+1 stay as written). */
        private fun rawArgument(): String {
            skip()
            val start = i
            var depth = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '(') depth++
                else if (ch == ')') { if (depth == 0) break; depth-- }
                else if (ch == ',' && depth == 0) break
                i++
            }
            val raw = s.substring(start, i)
            if (raw.isBlank()) throw CalcException("Syntax error", i)
            return raw
        }

        private fun sub(f: String, x: Double): Double {
            if (--budget.left < 0) throw CalcException("Time out")
            return Parser(f, angle, ans, memory, vars + ("x" to x), random, budget).parseAll()
        }

        private fun callMulti(name: String): Value {
            if (peek() != '(') throw CalcException("Syntax error", i)
            i++
            when (name) {
                "d/dx" -> {
                    val f = rawArgument(); comma()
                    val at = expr().v
                    close()
                    return Value(derivative(f, at))
                }
                "∫" -> {
                    val f = rawArgument(); comma()
                    val a = expr().v; comma()
                    val b = expr().v
                    close()
                    return Value(integral(f, a, b))
                }
                "Σ", "Π" -> {
                    val f = rawArgument(); comma()
                    val a = expr().v; comma()
                    val b = expr().v
                    close()
                    if (a != floor(a) || b != floor(b)) throw CalcException("Math error")
                    if (b - a > 100_000) throw CalcException("Math error")
                    var acc = if (name == "Σ") 0.0 else 1.0
                    var k = a
                    while (k <= b) {
                        val t = sub(f, k)
                        acc = if (name == "Σ") acc + t else acc * t
                        k += 1.0
                    }
                    return Value(acc)
                }
                "lim" -> {
                    val f = rawArgument(); comma()
                    val at = expr().v
                    close()
                    return Value(limit(f, at))
                }
            }
            val args = mutableListOf(expr().v)
            while (peek() == ',') { i++; args += expr().v }
            close()
            return Value(applyMulti(name, args))
        }

        private fun applyMulti(name: String, a: List<Double>): Double {
            fun need(n: Int) { if (a.size != n) throw CalcException("Syntax error", i) }
            fun whole(x: Double): Long {
                if (x != floor(x) || abs(x) > 1e15) throw CalcException("Math error")
                return x.toLong()
            }
            return when (name) {
                "logb" -> {
                    need(2)
                    val b = a[0]; val x = a[1]
                    if (b <= 0.0 || b == 1.0 || x <= 0.0) throw CalcException("Math error")
                    val r = ln(x) / ln(b)
                    val n = round(r)
                    if (abs(r - n) < 1e-12 * maxOf(1.0, abs(r))) n else r
                }
                "gcd" -> { need(2); gcdLong(whole(a[0]), whole(a[1])).toDouble() }
                "lcm" -> {
                    need(2)
                    val x = abs(whole(a[0])); val y = abs(whole(a[1]))
                    if (x == 0L || y == 0L) 0.0 else (x / gcdLong(x, y) * y).toDouble()
                }
                "Pol" -> {
                    need(2)
                    val r = hypot(a[0], a[1])
                    val th = atan2(a[1], a[0]) * angle.perRadian
                    extra = listOf("r" to r, "θ" to clean(th))
                    r
                }
                "Rec" -> {
                    need(2)
                    val th = a[1] / angle.perRadian
                    val x = clean(a[0] * cos(th)); val y = clean(a[0] * sin(th))
                    extra = listOf("x" to x, "y" to y)
                    x
                }
                "divr" -> {
                    need(2)
                    val x = whole(a[0]); val y = whole(a[1])
                    if (y == 0L) throw CalcException("Can't divide by 0")
                    val q = (x / y).toDouble()
                    extra = listOf("Q" to q, "R" to (x - y * (x / y)).toDouble())
                    q
                }
                "RanInt" -> {
                    need(2)
                    val lo = whole(a[0]); val hi = whole(a[1])
                    if (hi < lo) throw CalcException("Math error")
                    (lo + floor(random() * (hi - lo + 1))).coerceIn(lo.toDouble(), hi.toDouble())
                }
                else -> throw CalcException("Syntax error", i)
            }
        }

        // ---- Calculus ----------------------------------------------------------------------

        /** The slope of f at a point, from symmetric differences refined twice (Richardson). */
        private fun derivative(f: String, at: Double): Double {
            fun d(h: Double) = (sub(f, at + h) - sub(f, at - h)) / (2 * h)
            val h = 1e-3 * maxOf(1.0, abs(at))
            val d1 = d(h); val d2 = d(h / 2); val d3 = d(h / 4)
            val r1 = (4 * d2 - d1) / 3
            val r2 = (4 * d3 - d2) / 3
            val v = (16 * r2 - r1) / 15
            if (v.isNaN() || v.isInfinite()) throw CalcException("Math error")
            return snap(v)
        }

        private val xgk = doubleArrayOf(
            0.991455371120812639206854697526329, 0.949107912342758524526189684047851, 0.864864423359769072789712788640926,
            0.741531185599394439863864773280788, 0.586087235467691130294144838258730, 0.405845151377397166906606412076961,
            0.207784955007898467600689403773245, 0.0,
        )
        private val wgk = doubleArrayOf(
            0.022935322010529224963732008058970, 0.063092092629978553290700663189204, 0.104790010322250183839876322541518,
            0.140653259715525918745189590510238, 0.169004726639267902826583426598550, 0.190350578064785409913256402421014,
            0.204432940075298892414161999234649, 0.209482141084727828012999174891714,
        )
        private val wg = doubleArrayOf(
            0.129484966168869693270611432679082, 0.279705391489276667901467771423780,
            0.381830050505118944950369775488975, 0.417959183673469387755102040816327,
        )

        /** One Gauss–Kronrod (7, 15) pass over [a, b]: the better estimate and the rougher one. */
        private fun gk15(f: String, a: Double, b: Double): Pair<Double, Double> {
            val c = (a + b) / 2
            val h = (b - a) / 2
            val fc = sub(f, c)
            var k = fc * wgk[7]
            var g = fc * wg[3]
            for (j in 0 until 7) {
                val dx = h * xgk[j]
                val f1 = sub(f, c - dx)
                val f2 = sub(f, c + dx)
                k += wgk[j] * (f1 + f2)
                if (j % 2 == 1) g += wg[j / 2] * (f1 + f2)
            }
            return k * h to g * h
        }

        private fun adaptive(f: String, a: Double, b: Double, tol: Double, depth: Int): Double {
            val (k, g) = gk15(f, a, b)
            if (k.isNaN() || k.isInfinite()) throw CalcException("Math error")
            if (abs(k - g) <= tol * maxOf(1.0, abs(k)) || depth >= 10) return k
            val m = (a + b) / 2
            return adaptive(f, a, m, tol / 2, depth + 1) + adaptive(f, m, b, tol / 2, depth + 1)
        }

        private fun integral(f: String, a: Double, b: Double): Double {
            if (a == b) return 0.0
            if (a.isInfinite() || b.isInfinite()) throw CalcException("Math error")
            val v = adaptive(f, a, b, 1e-9, 0)
            return snap(v)
        }

        /** The value f settles to near [at] (or as x grows, for ∞). */
        private fun limit(f: String, at: Double): Double {
            fun tryEval(x: Double): Double? = try { sub(f, x).takeIf { !it.isNaN() } } catch (e: CalcException) { if (e.message == "Time out") throw e else null }
            if (at.isInfinite()) {
                val sgn = if (at > 0) 1.0 else -1.0
                val a = tryEval(sgn * 1e6) ?: throw CalcException("Math error")
                val b = tryEval(sgn * 1e9) ?: throw CalcException("Math error")
                if (b.isInfinite() || abs(b) > 1e12) throw CalcException("Too big")
                if (abs(a - b) > 1e-4 * maxOf(1.0, abs(b)) && abs(b) > 1e6) throw CalcException("Too big")
                return snap(b)
            }
            val hs = listOf(1e-3, 1e-5, 1e-7)
            val right = hs.map { tryEval(at + it * maxOf(1.0, abs(at))) }
            val left = hs.map { tryEval(at - it * maxOf(1.0, abs(at))) }
            val r = right.lastOrNull { it != null }; val l = left.lastOrNull { it != null }
            if (r == null && l == null) throw CalcException("Math error")
            if (r != null && l != null && abs(r - l) > 1e-5 * maxOf(1.0, abs(r))) throw CalcException("Math error")
            val pick = r ?: l!!
            if (abs(pick) > 1e9) throw CalcException("Too big")
            // A limit is only found to about 7 digits, so tidy 4.0000002 into 4.
            val loose = BigDecimal(pick).round(MathContext(7)).toDouble()
            return if (abs(loose - pick) <= 1e-6 * maxOf(1.0, abs(pick))) loose else pick
        }

        // ---- Numbers -----------------------------------------------------------------------

        /** Digits with an optional decimal point, an optional E exponent (6.02E23), and ° ' " for degrees-minutes-seconds. */
        private fun number(): Value {
            var v = plainNumber()
            if (i < s.length && s[i] == '°') {
                i++
                var total = v
                // minutes
                var mark = i
                if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                    val m = plainNumber(allowExponent = false)
                    if (i < s.length && s[i] == '\'') { i++; total += m / 60.0; mark = i
                        if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                            val sec = plainNumber(allowExponent = false)
                            if (i < s.length && s[i] == '"') { i++; total += sec / 3600.0 } else i = mark
                        }
                    } else i = mark
                }
                v = total
            }
            return Value(v)
        }

        private fun plainNumber(allowExponent: Boolean = true): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (s.substring(start, i).count { it == '.' } > 1) throw CalcException("Syntax error", i)
            if (allowExponent && i < s.length && s[i] == 'E') {
                val save = i
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                val digits = i
                while (i < s.length && s[i].isDigit()) i++
                if (i == digits) i = save // a lone E is the variable E, not an exponent
            }
            val text = s.substring(start, i).let { if (it.startsWith(".")) "0$it" else it }
            return text.toDoubleOrNull() ?: throw CalcException("Syntax error", i)
        }

        /** A function's argument. Brackets are optional for one simple value: sin30, √9, ln2. */
        private fun argument(): Double {
            if (peek() == '(') {
                i++
                val a = expr().v
                if (peek() == ')') i++
                return a
            }
            if (peek() == '-') { i++; return -postfix().v }
            return postfix().v
        }

        private fun apply(name: String, x: Double): Double {
            val r = x / angle.perRadian // the angle in radians
            fun back(v: Double) = v * angle.perRadian
            fun need(ok: Boolean) { if (!ok) throw CalcException("Math error") }
            return when (name) {
                "sin" -> clean(sin(r))
                "cos" -> clean(cos(r))
                "tan" -> {
                    // tan is undefined at 90° and its odd multiples.
                    need(abs(cos(r)) > 1e-12)
                    clean(tan(r))
                }
                "cot" -> {
                    need(abs(sin(r)) > 1e-12)
                    clean(cos(r) / sin(r))
                }
                "asin" -> { need(x in -1.0..1.0); back(asin(x)) }
                "acos" -> { need(x in -1.0..1.0); back(acos(x)) }
                "atan" -> back(atan(x))
                "acot" -> back(PI / 2 - atan(x))
                "sinh" -> sinh(x)
                "cosh" -> cosh(x)
                "tanh" -> tanh(x)
                "asinh" -> asinh(x)
                "acosh" -> { need(x >= 1.0); acosh(x) }
                "atanh" -> { need(x > -1.0 && x < 1.0); atanh(x) }
                "ln" -> { need(x > 0); ln(x) }
                "log" -> { need(x > 0); log10(x) }
                "log2" -> { need(x > 0); log2(x) }
                "sqrt" -> { need(x >= 0); sqrt(x) }
                "cbrt" -> cbrt(x)
                "abs" -> abs(x)
                "exp" -> exp(x)
                "floor" -> floor(x)
                "ceil" -> kotlin.math.ceil(x)
                "round" -> if (x < 0) -floor(-x + 0.5) else floor(x + 0.5) // halves away from zero, like a calculator
                "int" -> if (x < 0) kotlin.math.ceil(x) else floor(x)
                "frac" -> x - (if (x < 0) kotlin.math.ceil(x) else floor(x))
                "sign" -> if (x > 0) 1.0 else if (x < 0) -1.0 else 0.0
                else -> throw CalcException("Syntax error", i)
            }
        }

        /** sin(180°) should be 0, not 0.0000000000000001, and sin(30°) exactly 0.5. */
        private fun clean(v: Double): Double {
            if (abs(v) < 1e-12) return 0.0
            val rounded = round(v * 1e12) / 1e12
            return if (abs(rounded - v) < 1e-14) rounded else v
        }

        /** The y-th root of x. Odd roots of negative numbers are allowed: 3ˣ√−8 = −2. */
        private fun root(y: Double, x: Double): Double {
            if (y == 0.0) throw CalcException("Math error")
            if (x < 0) {
                val odd = y == floor(y) && y.toLong() % 2L != 0L
                if (!odd) throw CalcException("Math error")
                return -abs(x).pow(1.0 / y)
            }
            val r = x.pow(1.0 / y)
            val nearest = round(r)
            return if (abs(nearest.pow(y) - x) < 1e-9 * maxOf(1.0, abs(x))) nearest else r
        }

        private fun wholeNumber(x: Double): Long {
            if (x < 0 || x != floor(x) || x > 1e6) throw CalcException("Math error")
            return x.toLong()
        }

        private fun factorial(x: Double): Double {
            val n = wholeNumber(x)
            if (n > 170) throw CalcException("Too big")
            var r = 1.0
            for (k in 2..n) r *= k
            return r
        }

        private fun permutations(nx: Double, rx: Double): Double {
            val n = wholeNumber(nx); val r = wholeNumber(rx)
            if (r > n) throw CalcException("Math error")
            var p = 1.0
            for (k in 0 until r) p *= (n - k)
            if (p.isInfinite()) throw CalcException("Too big")
            return p
        }

        private fun combinations(nx: Double, rx: Double): Double {
            val n = wholeNumber(nx); var r = wholeNumber(rx)
            if (r > n) throw CalcException("Math error")
            r = minOf(r, n - r)
            var c = 1.0
            for (k in 1..r) c = c * (n - r + k) / k
            if (c.isInfinite()) throw CalcException("Too big")
            return round(c)
        }
    }
}
