package com.godbless.lockin.study

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// The calculator's brain: the keys, what each one does on every layer (plain, SHIFT, ALPHA, 2nd, HYP), and the
// state that goes with them. No Android in here, so it can be tested on its own; the screen just draws it.

internal enum class KeyStyle { Fn, Num, Op, Del, Eq, Shift, Alpha, Mode }

internal enum class KeyAct {
    Shift, Alpha, Second, Hyp, Ac, ClrAll, Del, Eq, MemAdd, MemSub, Sd, Eng, Si, Calc, Solve, Sto, Rcl, ClrVars,
    Dms, Fact, Mode, Const, Conv, Func, Help, Matrix, Vector, Stat, Cmplx, Distr, History, Copy, Paste, Compare, Frac,
}

/** Things the screen has to do for a key (open a menu, switch page, use the clipboard). */
internal enum class Req { None, Mode, Const, Conv, Func, Sto, Rcl, History, Stat, Matrix, Vector, Distr, Base, Copy, Paste, Compare }

/** What a key does on one layer: the text printed above it, the text it types, and any special action. */
internal class L(val label: String, val insert: String = label, val act: KeyAct? = null)

internal class CalcKeyDef(
    val label: String,
    val insert: String = label,
    val act: KeyAct? = null,
    val shift: L? = null,
    val alpha: L? = null,
    val second: L? = null,
    val hyp: L? = null,
    val shiftHyp: L? = null,
    val style: KeyStyle = KeyStyle.Fn,
    val spoken: String = label,
)

private fun trig(name: String, letter: String): CalcKeyDef {
    val cap = name.replaceFirstChar { it.uppercase() }
    return CalcKeyDef(
        cap, "$name(",
        shift = L("$cap⁻¹", "a$name("), alpha = L(letter, letter),
        hyp = L("${name}h", "${name}h("), shiftHyp = L("${name}h⁻¹", "a${name}h("),
    )
}

private fun num(d: String, shift: L? = null, alpha: L? = null) = CalcKeyDef(d, shift = shift, alpha = alpha, style = KeyStyle.Num)

internal object CalcKeys {
    /** The two columns either side of the direction pad, two rows each. */
    val LeftTop = listOf(
        listOf(
            CalcKeyDef("SHIFT", act = KeyAct.Shift, style = KeyStyle.Shift),
            CalcKeyDef("CALC", act = KeyAct.Calc, shift = L("SOLVE", act = KeyAct.Solve), alpha = L("=", "=")),
        ),
        listOf(
            CalcKeyDef("ALPHA", act = KeyAct.Alpha, style = KeyStyle.Alpha),
            CalcKeyDef("∫dx", "∫(", shift = L("d/dx", "d/dx("), spoken = "integral"),
        ),
    )
    val RightTop = listOf(
        listOf(
            CalcKeyDef("MODE", act = KeyAct.Mode, style = KeyStyle.Mode),
            CalcKeyDef("x⁻¹", "⁻¹", shift = L("x!", "!"), alpha = L("><=", act = KeyAct.Compare), spoken = "reciprocal"),
        ),
        listOf(
            CalcKeyDef("2nd", act = KeyAct.Second, style = KeyStyle.Mode, spoken = "second function"),
            CalcKeyDef("logₓy", "logb(", shift = L("Σ", "Σ("), alpha = L("Π", "Π("), spoken = "log to any base"),
        ),
    )

    val SciRows = listOf(
        listOf(
            CalcKeyDef("a/b", "/", act = KeyAct.Frac, alpha = L("÷R", "divr("), spoken = "fraction"),
            CalcKeyDef("√x", "√(", shift = L("∛x", "∛("), alpha = L("mod", " mod "), second = L("Int", "int("), spoken = "square root"),
            CalcKeyDef("x²", "²", shift = L("x³", "³"), alpha = L("z", "z"), second = L("Frac", "frac("), spoken = "squared"),
            CalcKeyDef("xʸ", "^", shift = L("ˣ√y", "ˣ√"), second = L("Sign", "sign("), spoken = "power"),
            CalcKeyDef("Log", "log(", shift = L("10ˣ", "10^("), second = L("log₂", "log2(")),
            CalcKeyDef("Ln", "ln(", shift = L("eˣ", "e^("), alpha = L("t", "t"), second = L("Round", "round(")),
        ),
        listOf(
            CalcKeyDef("(−)", "−", shift = L("∠", "∠"), alpha = L("A", "A"), spoken = "negative"),
            CalcKeyDef("°′″", "°", act = KeyAct.Dms, shift = L("FACT", act = KeyAct.Fact), alpha = L("B", "B"), spoken = "degrees minutes seconds"),
            CalcKeyDef("hyp", act = KeyAct.Hyp, shift = L("|x|", "abs("), alpha = L("C", "C"), style = KeyStyle.Mode, spoken = "hyperbolic"),
            trig("sin", "D"), trig("cos", "E"), trig("tan", "F"),
        ),
        listOf(
            CalcKeyDef("RCL", act = KeyAct.Rcl, shift = L("STO", act = KeyAct.Sto), alpha = L("CLRv", act = KeyAct.ClrVars), spoken = "recall"),
            CalcKeyDef("ENG", act = KeyAct.Eng, shift = L("i", "i"), alpha = L("Cot", "cot("), spoken = "engineering notation"),
            CalcKeyDef("(", shift = L("%", "%"), alpha = L("Cot⁻¹", "acot(")),
            CalcKeyDef(")", shift = L(",", ","), alpha = L("x", "x")),
            CalcKeyDef("S⇌D", act = KeyAct.Sd, alpha = L("y", "y"), spoken = "fraction or decimal"),
            CalcKeyDef("M+", act = KeyAct.MemAdd, shift = L("M−", act = KeyAct.MemSub), alpha = L("M", "M"), spoken = "memory add"),
        ),
    )

    val NumRows = listOf(
        listOf(
            num("7", shift = L("CONST", act = KeyAct.Const)),
            num("8", shift = L("CONV", act = KeyAct.Conv), alpha = L("SI", act = KeyAct.Si)),
            num("9", shift = L("Limit", "lim("), alpha = L("∞", "∞")),
            CalcKeyDef("DEL", act = KeyAct.Del, style = KeyStyle.Del, spoken = "delete"),
            CalcKeyDef("AC", act = KeyAct.Ac, shift = L("CLR ALL", act = KeyAct.ClrAll), style = KeyStyle.Del, spoken = "clear"),
        ),
        listOf(
            num("4", shift = L("MATRIX", act = KeyAct.Matrix)),
            num("5", shift = L("VECTOR", act = KeyAct.Vector)),
            num("6", shift = L("FUNC", act = KeyAct.Func), alpha = L("HELP", act = KeyAct.Help)),
            CalcKeyDef("×", shift = L("nPr", "P"), alpha = L("GCD", "gcd("), style = KeyStyle.Op, spoken = "times"),
            CalcKeyDef("÷", shift = L("nCr", "C"), alpha = L("LCM", "lcm("), style = KeyStyle.Op, spoken = "divide"),
        ),
        listOf(
            num("1", shift = L("STAT", act = KeyAct.Stat)),
            num("2", shift = L("CMPLX", act = KeyAct.Cmplx)),
            num("3", shift = L("DISTR", act = KeyAct.Distr)),
            CalcKeyDef("+", shift = L("Pol", "Pol("), alpha = L("Ceil", "ceil("), style = KeyStyle.Op, spoken = "plus"),
            CalcKeyDef("−", shift = L("Rec", "Rec("), alpha = L("Floor", "floor("), style = KeyStyle.Op, spoken = "minus"),
        ),
        listOf(
            num("0", shift = L("COPY", act = KeyAct.Copy), alpha = L("PASTE", act = KeyAct.Paste)),
            CalcKeyDef(".", shift = L("Ran#", "Ran#"), alpha = L("RanInt", "RanInt("), style = KeyStyle.Num, spoken = "point"),
            CalcKeyDef("×10ˣ", "E", shift = L("π", "π"), alpha = L("e", "e"), style = KeyStyle.Num, spoken = "times ten to the"),
            CalcKeyDef("Ans", shift = L("PreAns", "PreAns"), style = KeyStyle.Num),
            CalcKeyDef("=", act = KeyAct.Eq, shift = L("History", act = KeyAct.History), style = KeyStyle.Eq, spoken = "equals"),
        ),
    )

    val all: List<CalcKeyDef> = (LeftTop.flatten() + RightTop.flatten() + SciRows.flatten() + NumRows.flatten())

    /** The memory letters on the ALPHA layer, for the STO and RCL pickers. */
    val letters = Calc.variableNames + "M"
}

/** The label a key shows right now (a trig key shows sinh while HYP is lit, a 2nd key its 2nd job). */
internal fun keyLabel(k: CalcKeyDef, shift: Boolean, hyp: Boolean, second: Boolean): String = when {
    shift && hyp && k.shiftHyp != null -> k.shiftHyp.label
    hyp && k.hyp != null -> k.hyp.label
    second && k.second != null -> k.second.label
    else -> k.label
}

/** The calculator's LCD colours: screen, ink and a faint ink for the switched-off flags. */
internal class LcdTheme(val name: String, val screen: Long, val ink: Long, val dim: Long)

internal val LcdThemes = listOf(
    LcdTheme("Mint", 0xFFD3E2DF, 0xFF101414, 0xFF9DB0AC),
    LcdTheme("Paper", 0xFFF1EEE4, 0xFF1A1814, 0xFFC4BFB1),
    LcdTheme("Amber", 0xFFE8C766, 0xFF2A1D00, 0xFFC9A64A),
    LcdTheme("Night", 0xFF0E1A17, 0xFF6BF2A4, 0xFF1F3A32),
)

/** An answer in engineering form: the power of ten a multiple of 3, like 12.5×10³. */
internal fun engineeringText(v: Double, fmt: NumFormat): String {
    if (v == 0.0 || v.isNaN() || v.isInfinite()) return Calc.format(v, fmt)
    val exp = (floor(log10(abs(v)) / 3.0) * 3).toInt()
    val mantissa = v / 10.0.pow(exp)
    if (exp == 0) return Calc.format(mantissa, fmt)
    val sup = exp.toString().map { ch -> if (ch == '-') '⁻' else "⁰¹²³⁴⁵⁶⁷⁸⁹"[ch - '0'] }.joinToString("")
    return "${Calc.format(mantissa, fmt)}×10$sup"
}

/** Where one stacked fraction sits in the sum text: its `{`, its `|` bar and its `}`. */
internal class FracSpan(val open: Int, val bar: Int, val close: Int)

internal fun fractionSpans(text: String): List<FracSpan> {
    val out = ArrayList<FracSpan>()
    val stack = ArrayList<IntArray>() // [open, bar]
    for ((i, ch) in text.withIndex()) {
        when (ch) {
            '{' -> stack.add(intArrayOf(i, -1))
            '|' -> if (stack.isNotEmpty()) stack[stack.lastIndex][1] = i
            '}' -> if (stack.isNotEmpty()) {
                val t = stack.removeAt(stack.lastIndex)
                out.add(FracSpan(t[0], t[1], i))
            }
        }
    }
    return out
}

/** A sum split for drawing: plain text pieces and stacked fractions (which hold more pieces). */
internal sealed class SumNode

internal class SumText(val start: Int, val text: String) : SumNode()

internal class SumFrac(val open: Int, val top: List<SumNode>, val bottom: List<SumNode>, val close: Int) : SumNode()

/** Splits a sum into text and fractions, or null if the { | } marks don't pair up. Every gap gets a (maybe empty) text piece. */
internal fun parseSum(text: String): List<SumNode>? {
    var i = 0
    fun seq(stop: Char?): List<SumNode>? {
        val out = ArrayList<SumNode>()
        var segStart = i
        while (true) {
            if (i >= text.length) {
                if (stop != null) return null
                out += SumText(segStart, text.substring(segStart, i))
                return out
            }
            val ch = text[i]
            if (ch == '{') {
                out += SumText(segStart, text.substring(segStart, i))
                val open = i
                i++
                val top = seq('|') ?: return null
                i++ // the bar
                val bottom = seq('}') ?: return null
                i++ // the closing mark
                out += SumFrac(open, top, bottom, i - 1)
                segStart = i
            } else if (stop != null && ch == stop) {
                out += SumText(segStart, text.substring(segStart, i))
                return out
            } else if (ch == '|' || ch == '}') {
                return null
            } else {
                i++
            }
        }
    }
    return seq(null)
}

/** The text piece the cursor is in (each cursor position is in exactly one piece). */
internal fun caretPiece(nodes: List<SumNode>, cursor: Int): SumText? {
    for (n in nodes) {
        when (n) {
            is SumText -> if (cursor >= n.start && cursor <= n.start + n.text.length) return n
            is SumFrac -> (caretPiece(n.top, cursor) ?: caretPiece(n.bottom, cursor))?.let { return it }
        }
    }
    return null
}

private val simpleFraction = Regex("\\{([^{}|]*)\\|([^{}|]*)\\}")

/** A sum written on one line for lists: {1|2} becomes 1÷2, {1+2|3} becomes (1+2)÷3. */
internal fun prettySum(s: String): String {
    var t = s
    var guard = 0
    while ('{' in t && guard++ < 20) {
        val next = simpleFraction.replace(t) { m ->
            fun wrap(x: String) = if (x.isNotEmpty() && x.all { it.isDigit() || it == '.' }) x else "($x)"
            wrap(m.groupValues[1]) + "÷" + wrap(m.groupValues[2])
        }
        if (next == t) break
        t = next
    }
    return t
}

internal class CalcModel {
    // ---- What is typed and what came out -----------------------------------------------------
    var expr = ""
    var cursor = 0
    var lastExpr = ""
    var done = false
    var error = ""

    var ans = 0.0
    var ansIm = 0.0
    var preAns = 0.0
    var memory = 0.0
    val vars = linkedMapOf<String, Double>()

    /** Extra result lines (Pol and Rec, ÷R, SOLVE, FACT): a name and its value. */
    var lines: List<Pair<String, String>> = emptyList()
    var preview = ""
    var complexText = ""

    /** Earlier sums, newest first. */
    val history = ArrayList<Pair<String, String>>()
    var browse = -1

    // ---- Settings ----------------------------------------------------------------------------
    var angle = AngleMode.Deg
    var fmt = NumFormat(NumFormat.Kind.Norm2, 13)
    var mathDisplay = true
    var lcd = 0
    var cmplx = false
    var polar = false
    var haptics = true

    // ---- One-shot layers and answer views ------------------------------------------------------
    var shift = false
    var alpha = false
    var second = false
    var hyp = false
    var flip = false // S⇌D has been pressed
    var eng = false
    var si = false
    var dms = false

    /** Set by CONV: the answer stays a decimal (25.4, not 127/5). */
    var noExact = false

    /** The answer is True or False (the sum was a comparison). */
    var isBool = false

    // ---- CALC and SOLVE ----------------------------------------------------------------------
    /** 0 none, 1 asking CALC values, 2 asking SOLVE values, 3 CALC answered, 4 SOLVE answered. */
    var promptKind = 0
    var promptExpr = ""
    var promptVars: List<String> = emptyList()
    var promptIndex = 0

    val prompting get() = promptKind == 1 || promptKind == 2

    /** The line above the input while CALC or SOLVE is asking for a value: "A =", or "x = (start near)". */
    val caption: String
        get() {
            if (!prompting || promptIndex !in promptVars.indices) return ""
            val name = promptVars[promptIndex]
            return if (promptKind == 2 && promptIndex == promptVars.lastIndex) "Solve for $name, start near" else "$name ="
        }

    // ---- Typing ------------------------------------------------------------------------------

    private val carryOn = setOf("+", "−", "×", "÷", "/", "^", "!", "%", "²", "³", "⁻¹", "ˣ√", " mod ", "P", "C")

    /** Pieces that DEL and the arrows treat as one. Longest first. */
    private val tokens = listOf(
        "asinh(", "acosh(", "atanh(", "round(", "floor(", "RanInt(", "sinh(", "cosh(", "tanh(", "asin(", "acos(", "atan(", "acot(",
        "ceil(", "frac(", "sign(", "divr(", "logb(", "d/dx(", "PreAns", "sin(", "cos(", "tan(", "cot(", "log2(", "log(", "int(",
        "gcd(", "lcm(", "Pol(", "Rec(", "lim(", "ln(", "abs(", "10^(", "e^(", "√(", "∛(", "∫(", "Σ(", "Π(",
        " mod ", "Ran#", "Ans", "ˣ√", "⁻¹",
    )

    private fun tokenBefore(before: String): Int {
        if (before.endsWith("⟩")) {
            val k = before.lastIndexOf('⟨')
            if (k >= 0) return before.length - k
        }
        return tokens.firstOrNull { before.endsWith(it) }?.length ?: 1
    }

    private fun tokenAt(text: String, c: Int): Int {
        if (text.startsWith("⟨", c)) {
            val k = text.indexOf('⟩', c)
            if (k >= 0) return k - c + 1
        }
        return tokens.firstOrNull { text.startsWith(it, c) }?.length ?: 1
    }

    private fun leaveAnswer() {
        done = false; flip = false; eng = false; si = false; dms = false; noExact = false; isBool = false
        lines = emptyList()
        if (promptKind == 3 || promptKind == 4) promptKind = 0
    }

    fun type(text: String, fresh: Boolean = false) {
        if (text.isEmpty()) return
        if (done) {
            val keep = !fresh && text in carryOn
            leaveAnswer()
            expr = if (keep) "Ans$text" else text
            cursor = expr.length
            return
        }
        val c = cursor.coerceIn(0, expr.length)
        expr = expr.substring(0, c) + text + expr.substring(c)
        cursor = c + text.length
    }

    fun delete() {
        if (done) {
            val back = promptKind == 3 || promptKind == 4
            leaveAnswer()
            expr = if (back) promptExpr else ""
            cursor = expr.length
            return
        }
        val c = cursor.coerceIn(0, expr.length)
        if (c == 0) return
        val before = expr.substring(0, c)
        if (before.last() == '{' || before.last() == '|' || before.last() == '}') { deleteAtFraction(c); return }
        val n = tokenBefore(before)
        expr = expr.substring(0, c - n) + expr.substring(c)
        cursor = c - n
    }

    /**
     * DEL next to a fraction's own marks. An empty fraction goes away; DEL at the start of an empty bottom takes the
     * fraction apart and keeps the top; otherwise the cursor just steps out of (or into) the fraction.
     */
    private fun deleteAtFraction(c: Int) {
        val spans = fractionSpans(expr)
        val span = when (expr[c - 1]) {
            '{' -> spans.firstOrNull { it.open == c - 1 }
            '|' -> spans.firstOrNull { it.bar == c - 1 }
            else -> spans.firstOrNull { it.close == c - 1 }
        }
        if (span == null) { // a stray mark: just remove it
            expr = expr.removeRange(c - 1, c)
            cursor = c - 1
            return
        }
        val topEmpty = span.bar == span.open + 1
        val bottomEmpty = span.close == span.bar + 1
        when {
            topEmpty && bottomEmpty -> {
                expr = expr.substring(0, span.open) + expr.substring(span.close + 1)
                cursor = span.open
            }
            expr[c - 1] == '|' && bottomEmpty -> {
                expr = expr.substring(0, span.open) + expr.substring(span.open + 1, span.bar) + expr.substring(span.close + 1)
                cursor = span.bar - 1
            }
            else -> cursor = c - 1
        }
    }

    // ---- Stacked fractions ---------------------------------------------------------------------

    /** How much of [before] is the number (or bracket, function, fraction…) a new fraction should take as its top. */
    private fun operandLengthBefore(before: String): Int {
        var k = before.length
        // ², ³, ! and % belong to the number before them: 5² over 3 takes the whole 5².
        while (k > 0) {
            val ch = before[k - 1]
            if (ch == '²' || ch == '³' || ch == '!' || ch == '%') k--
            else if (k >= 2 && before.startsWith("⁻¹", k - 2)) k -= 2
            else break
        }
        val core = coreOperandLength(before.substring(0, k))
        return if (core == 0) 0 else (before.length - k) + core
    }

    private fun coreOperandLength(s: String): Int {
        if (s.isEmpty()) return 0
        val last = s.last()
        when {
            last == ')' -> {
                var depth = 0
                var k = s.length
                while (k > 0) {
                    k--
                    if (s[k] == ')') depth++ else if (s[k] == '(') { depth--; if (depth == 0) break }
                }
                if (depth != 0) return 0
                var start = k
                val fn = tokens.firstOrNull { it.endsWith("(") && s.substring(0, k + 1).endsWith(it) }
                if (fn != null) start = k + 1 - fn.length
                else if (k > 0 && (s[k - 1] == '√' || s[k - 1] == '∛')) start = k - 1
                return s.length - start
            }
            last == '}' -> {
                var depth = 0
                var k = s.length
                while (k > 0) {
                    k--
                    if (s[k] == '}') depth++ else if (s[k] == '{') { depth--; if (depth == 0) break }
                }
                return if (depth == 0) s.length - k else 0
            }
            last == '⟩' -> { val k = s.lastIndexOf('⟨'); return if (k >= 0) s.length - k else 1 }
            last.isDigit() || last == '.' -> {
                var k = s.length
                while (k > 0 && (s[k - 1].isDigit() || s[k - 1] == '.')) k--
                return s.length - k
            }
            else -> {
                val t = listOf("PreAns", "Ans", "Ran#").firstOrNull { s.endsWith(it) }
                if (t != null) return t.length
                return if (last.isLetter() || last == 'π' || last == '∞') 1 else 0
            }
        }
    }

    /** The a/b key: a stacked fraction. The number before the cursor becomes the top and the cursor drops to the bottom. */
    private fun typeFraction() {
        if (done) {
            leaveAnswer()
            expr = "{Ans|}"
            cursor = 5
            return
        }
        val c = cursor.coerceIn(0, expr.length)
        val n = operandLengthBefore(expr.substring(0, c))
        val start = c - n
        expr = expr.substring(0, start) + "{" + expr.substring(start, c) + "|}" + expr.substring(c)
        cursor = if (n > 0) c + 2 else start + 1
    }

    /** ▲ ▼ inside a fraction move between its top and bottom (and never browse history). True if the arrow was used up. */
    private fun moveInFraction(up: Boolean): Boolean {
        if (done || prompting) return false
        val c = cursor.coerceIn(0, expr.length)
        val span = fractionSpans(expr).filter { it.open < c && c <= it.close && it.bar > it.open }.maxByOrNull { it.open } ?: return false
        val inBottom = c > span.bar
        if (up && inBottom) cursor = span.bar
        else if (!up && !inBottom) cursor = span.bar + 1
        return true
    }

    /** 0 left, 1 right, 2 up (older sums), 3 down (newer sums). */
    fun move(dir: Int) {
        error = ""
        if ((dir == 2 || dir == 3) && moveInFraction(dir == 2)) { refresh(); return }
        when (dir) {
            0 -> {
                if (done) { reopen(null); return }
                val c = cursor.coerceIn(0, expr.length)
                val before = expr.substring(0, c)
                cursor = (c - tokenBefore(before)).coerceAtLeast(0)
            }
            1 -> {
                if (done) { reopen(0); return }
                val c = cursor.coerceIn(0, expr.length)
                cursor = (c + tokenAt(expr, c)).coerceAtMost(expr.length)
            }
            2 -> if (history.isNotEmpty() && !prompting) {
                browse = (browse + 1).coerceAtMost(history.lastIndex)
                leaveAnswer(); expr = history[browse].first; cursor = expr.length
            }
            3 -> if (!prompting) {
                browse -= 1
                leaveAnswer()
                if (browse < 0) { browse = -1; expr = ""; cursor = 0 } else { expr = history[browse].first; cursor = expr.length }
            }
        }
        refresh()
    }

    /** Back to editing the last sum (after an answer), with the cursor at [at]. */
    private fun reopen(at: Int?) {
        if (!done) return
        val src = if (promptKind == 3 || promptKind == 4) promptExpr else lastExpr
        leaveAnswer()
        expr = src
        cursor = (at ?: expr.length).coerceIn(0, expr.length)
        refresh()
    }

    // ---- Undo --------------------------------------------------------------------------------

    private val undoStack = ArrayList<String>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** Remembers the state before a change, so Undo can bring it back. */
    fun checkpoint() {
        val s = encode()
        if (undoStack.lastOrNull() == s) return
        undoStack.add(s)
        if (undoStack.size > 30) undoStack.removeAt(0)
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val o = decode(undoStack.removeAt(undoStack.lastIndex))
        // Settings (angle, format, theme…) stay as they are; only the work itself goes back.
        expr = o.expr; cursor = o.cursor; lastExpr = o.lastExpr; done = o.done; error = ""
        ans = o.ans; ansIm = o.ansIm; preAns = o.preAns; memory = o.memory
        vars.clear(); vars.putAll(o.vars)
        lines = o.lines; complexText = o.complexText
        history.clear(); history.addAll(o.history)
        browse = -1
        flip = o.flip; eng = o.eng; si = o.si; dms = o.dms; noExact = o.noExact
        promptKind = o.promptKind; promptExpr = o.promptExpr; promptVars = o.promptVars; promptIndex = o.promptIndex
        shift = false; alpha = false; second = false; hyp = false
        refresh()
        return true
    }

    /** Typing from a menu (a function name, a constant): undoable, and the preview updates. */
    fun insert(text: String) {
        checkpoint()
        type(text)
        refresh()
    }

    // ---- Keys --------------------------------------------------------------------------------

    private fun layer(k: CalcKeyDef): L = when {
        shift && hyp && k.shiftHyp != null -> k.shiftHyp
        hyp && !shift && k.hyp != null -> k.hyp
        shift && k.shift != null -> k.shift
        alpha && k.alpha != null -> k.alpha
        second && k.second != null -> k.second
        else -> L(k.label, k.insert, k.act)
    }

    fun shownLabel(k: CalcKeyDef): String = keyLabel(k, shift, hyp, second)

    fun press(k: CalcKeyDef): Req {
        error = ""
        val l = layer(k)
        when (l.act) {
            KeyAct.Shift -> { shift = !shift; alpha = false; return Req.None }
            KeyAct.Alpha -> { alpha = !alpha; shift = false; return Req.None }
            KeyAct.Second -> { second = !second; return Req.None }
            KeyAct.Hyp -> { hyp = !hyp; shift = false; alpha = false; return Req.None }
            else -> {}
        }
        // SHIFT, ALPHA and 2nd last for one key, and HYP for one trig key, as on a real calculator.
        shift = false; alpha = false; second = false
        if (k.hyp != null) hyp = false
        browse = -1
        checkpoint()
        val req = run(l)
        refresh()
        return req
    }

    private fun run(l: L): Req {
        when (l.act) {
            KeyAct.Ac -> { clear() }
            KeyAct.ClrAll -> { clearAll() }
            KeyAct.Del -> delete()
            KeyAct.Eq -> equals()
            KeyAct.Calc -> startCalc()
            KeyAct.Solve -> startSolve()
            KeyAct.MemAdd, KeyAct.MemSub -> {
                val v = currentValue() ?: return Req.None
                memory += if (l.act == KeyAct.MemAdd) v else -v
                if (!done && expr.isNotBlank()) showValue(v, expr)
            }
            KeyAct.Sd -> if (done) flip = !flip
            KeyAct.Eng -> if (done) { eng = !eng; si = false; dms = false }
            KeyAct.Si -> if (done) { si = !si; eng = false; dms = false }
            KeyAct.Dms -> if (done) { dms = !dms; eng = false; si = false } else type(dmsMark())
            KeyAct.Fact -> factorize()
            KeyAct.ClrVars -> vars.clear()
            KeyAct.Cmplx -> setComplex(!cmplx)
            KeyAct.Mode -> return Req.Mode
            KeyAct.Const -> return Req.Const
            KeyAct.Conv -> return Req.Conv
            KeyAct.Func, KeyAct.Help -> return Req.Func
            KeyAct.Sto -> return Req.Sto
            KeyAct.Rcl -> return Req.Rcl
            KeyAct.Matrix -> return Req.Matrix
            KeyAct.Vector -> return Req.Vector
            KeyAct.Stat -> return Req.Stat
            KeyAct.Distr -> return Req.Distr
            KeyAct.History -> return Req.History
            KeyAct.Compare -> return Req.Compare
            KeyAct.Copy -> return Req.Copy
            KeyAct.Paste -> return Req.Paste
            else -> when {
                // (−) is a sign, not the minus operator: after an answer it starts a new number (−5), it does not carry on from Ans.
                l.label == "(−)" && l.insert == "−" -> type("−", fresh = true)
                l.label == "×10ˣ" && l.insert == "E" -> typeExp()
                l.act == KeyAct.Frac -> typeFraction()
                else -> type(l.insert)
            }
        }
        return Req.None
    }

    /**
     * The ×10ˣ key. A bare "E" is the variable E (0), so after an answer it carries on as Ans×10^ and
     * with nothing before it (or after an operator or bracket) it means 1E, so "×10ˣ 3" is 1000.
     */
    private fun typeExp() {
        if (done) { type("Ans×10^"); return }
        val before = expr.substring(0, cursor.coerceIn(0, expr.length))
        val last = before.lastOrNull()
        val afterNumber = last != null && (last.isDigit() || last == '.' || last == ')' || last == 'π' || last == '²' || last == '³')
        type(if (afterNumber) "E" else "1E")
    }

    /** The next ° ′ ″ mark: 12 then ° then 30 then ′ then 15 then ″. */
    private fun dmsMark(): String {
        val before = expr.substring(0, cursor.coerceIn(0, expr.length))
        val seg = before.takeLastWhile { it.isDigit() || it == '.' || it == '°' || it == '\'' || it == '"' }
        return when {
            seg.isEmpty() || seg.contains('"') -> ""
            seg.contains('\'') -> "\""
            seg.contains('°') -> "'"
            else -> "°"
        }
    }

    fun clear() {
        if (prompting || promptKind == 3 || promptKind == 4) {
            val back = promptExpr
            promptKind = 0; leaveAnswer()
            expr = back; cursor = expr.length
            return
        }
        expr = ""; cursor = 0; lastExpr = ""; leaveAnswer()
    }

    fun clearAll() {
        clear(); promptKind = 0
        expr = ""; cursor = 0; lastExpr = ""
        ans = 0.0; ansIm = 0.0; preAns = 0.0; memory = 0.0
        vars.clear(); history.clear(); lines = emptyList(); complexText = ""
    }

    fun setComplex(on: Boolean) {
        if (cmplx == on) return
        cmplx = on
        clear()
    }

    // ---- Working things out ------------------------------------------------------------------

    private fun varMap(): Map<String, Double> = vars + ("PreAns" to preAns)

    private fun evalReal(text: String, maxWork: Int = 400_000): Outcome =
        Calc.evalFull(text, angle, ans, memory, varMap(), maxWork)

    private fun evalComplex(text: String): Cx = CalcComplex.eval(text, angle, Cx(ans, ansIm))

    /** The number on the screen right now: the answer after "=", or the sum typed so far worked out. */
    fun currentValue(): Double? {
        if (done || expr.isBlank()) return ans
        return try {
            if (cmplx) evalComplex(expr).re else evalReal(expr).value
        } catch (e: CalcException) {
            error = e.message ?: "Error"
            null
        }
    }

    /**
     * Like a real calculator, a sum that was typed with a decimal point (or E, degrees-minutes-seconds or a
     * random number) answers in decimals; only whole numbers and fractions give exact answers like 3/4 or √2/2.
     */
    private fun exactAllowed(src: String): Boolean =
        !noExact && '.' !in src && '°' !in src && !Regex("[0-9]E").containsMatchIn(src) && "Ran" !in src

    private fun exactFor(v: Double, src: String): String? =
        if (!exactAllowed(src)) null else if (mathDisplay) Calc.exact(v) else Calc.fraction(v)

    /** A number written the way the screen writes answers: exact when it can be (and wanted), else decimal. */
    fun numberText(v: Double, src: String = expr): String {
        if (mathDisplay) exactFor(v, src)?.let { return it }
        return Calc.format(v, fmt)
    }

    private fun showValue(v: Double, source: String) {
        lastExpr = source
        preAns = ans; ans = v; ansIm = 0.0
        leaveAnswer()
        expr = Calc.format(v, fmt)
        cursor = expr.length
        done = true
    }

    private fun noteHistory(src: String, shown: String) {
        history.add(0, src to shown)
        if (history.size > 40) history.removeAt(history.lastIndex)
    }

    fun equals() {
        when {
            prompting -> { promptAccept(); return }
            promptKind == 3 || promptKind == 4 -> { restartPrompt(); return }
        }
        if (expr.isBlank()) return
        // Already an answer: = again leaves it alone. Re-reading the shown (rounded) text would lose digits
        // and the exact form (1÷3 = = × 3 gave 0.9999999999999).
        if (done) return
        try {
            if (cmplx) {
                val c = evalComplex(expr)
                lastExpr = expr
                preAns = ans; ans = c.re; ansIm = c.im
                complexText = CalcComplex.format(c, polar, angle, fmt)
                leaveAnswer(); expr = complexText; cursor = expr.length; done = true
                noteHistory(lastExpr, complexText)
                return
            }
            val out = evalReal(expr)
            val src = expr
            preAns = ans; ans = out.value; ansIm = 0.0
            leaveAnswer()
            lastExpr = src
            expr = Calc.format(out.value, fmt)
            cursor = expr.length
            done = true
            isBool = out.bool
            lines = out.extra.map { (n, v) -> n to Calc.format(v, fmt) }
            noteHistory(src, if (lines.isNotEmpty()) lines.joinToString("  ") { it.first + "=" + it.second } else resultText())
        } catch (e: CalcException) {
            error = e.message ?: "Math error"
            // Like a real calculator, put the cursor where the sum went wrong.
            if (e.at >= 0) cursor = e.at.coerceIn(0, expr.length)
        }
    }

    /** What the big line shows: an error, or the answer in the chosen view. Empty while typing. */
    fun resultText(): String {
        if (error.isNotEmpty()) return error
        if (!done) return preview
        if (cmplx) return complexText
        if (isBool) return if (ans != 0.0) "True" else "False"
        if (lines.isNotEmpty()) return ""
        return when {
            dms -> Calc.toDms(ans)
            eng -> engineeringText(ans, fmt)
            si -> Calc.siPrefixed(ans)
            else -> {
                val ex = exactFor(ans, lastExpr)
                if (ex != null && (mathDisplay != flip)) ex else Calc.format(ans, fmt)
            }
        }
    }

    /** Whether S⇌D would change what is shown (there is an exact form to swap with). */
    fun canFlip(): Boolean = done && !cmplx && !isBool && lines.isEmpty() && exactFor(ans, lastExpr) != null

    /** Works out the live preview shown while the sum is still being typed. */
    fun refresh() {
        preview = ""
        if (done || expr.isBlank() || error.isNotEmpty()) return
        if (prompting) return
        if ("Ran#" in expr || "RanInt" in expr) return
        try {
            if (cmplx) {
                val c = evalComplex(expr)
                preview = CalcComplex.format(c, polar, angle, fmt)
            } else {
                val o = evalReal(expr, 15_000)
                preview = if (o.bool) (if (o.value != 0.0) "True" else "False") else numberText(o.value)
            }
        } catch (e: CalcException) {
            preview = ""
        }
        if (preview == expr) preview = ""
    }

    // ---- STO, RCL, CONST, CONV, FACT -----------------------------------------------------------

    /** Stores the number on the screen into a memory letter. */
    fun store(name: String) {
        checkpoint()
        val v = currentValue() ?: return
        if (name == "M") memory = v else vars[name] = v
    }

    fun recall(name: String) {
        checkpoint()
        type(name)
        refresh()
    }

    fun insertConstant(c: PhysConst) {
        checkpoint()
        type(Constants.insertText(c))
        refresh()
    }

    /** Converts the number on the screen with one of the CONV pairs and shows it as the answer. */
    fun convert(c: Conversion) {
        checkpoint()
        val v = currentValue() ?: return
        val shown = Calc.format(v, fmt)
        val r = c.apply(v)
        showValue(r, "$shown ${c.label}")
        noExact = true
        noteHistory(lastExpr, Calc.format(r, fmt))
        refresh()
    }

    private fun factorize() {
        val v = currentValue() ?: return
        val f = NumberTheory.primeFactors(v)
        if (f == null) { error = "Math error"; return }
        val src = "FACT(${Calc.format(v, fmt)})"
        showValue(v, src)
        lines = listOf("" to f)
    }

    // ---- CALC and SOLVE ----------------------------------------------------------------------

    private fun beginPrompt(kind: Int, src: String, vs: List<String>) {
        promptKind = kind; promptExpr = src; promptVars = vs; promptIndex = 0
        leaveAnswer()
        if (kind == 3 || kind == 4) promptKind = kind - 2
        loadPrompt()
    }

    private fun loadPrompt() {
        val name = promptVars[promptIndex]
        expr = Calc.format(if (name == "M") memory else vars[name] ?: 0.0, fmt)
        cursor = expr.length
    }

    fun startCalc() {
        if (cmplx) return
        val src = if (done) (if (promptKind == 3 || promptKind == 4) promptExpr else lastExpr) else expr
        if (src.isBlank()) return
        val vs = Calc.variablesIn(src)
        if (vs.isEmpty()) { if (!done) equals(); return }
        beginPrompt(1, src, vs)
    }

    fun startSolve() {
        if (cmplx) return
        val src = if (done) (if (promptKind == 3 || promptKind == 4) promptExpr else lastExpr) else expr
        if (src.isBlank()) return
        val vs = Calc.variablesIn(src)
        if (vs.isEmpty()) { error = "Syntax error"; return }
        val unknown = if ("x" in vs) "x" else vs.first()
        beginPrompt(2, src, vs.filter { it != unknown } + unknown)
    }

    private fun restartPrompt() {
        beginPrompt(if (promptKind == 3) 1 else 2, promptExpr, promptVars)
    }

    private fun promptAccept() {
        val name = promptVars[promptIndex]
        val v = try {
            evalReal(expr).value
        } catch (e: CalcException) {
            error = e.message ?: "Math error"; return
        }
        if (name == "M") memory = v else vars[name] = v
        if (promptIndex + 1 < promptVars.size) {
            promptIndex++
            loadPrompt()
        } else {
            finishPrompt()
        }
    }

    private fun finishPrompt() {
        val kind = promptKind
        val src = promptExpr
        try {
            if (kind == 1) {
                val out = evalReal(src)
                preAns = ans; ans = out.value; ansIm = 0.0
                promptKind = 3
                leaveAnswer(); promptKind = 3
                lastExpr = src
                expr = Calc.format(out.value, fmt); cursor = expr.length
                done = true
                isBool = out.bool
                lines = out.extra.map { (n, v) -> n to Calc.format(v, fmt) }
                noteHistory(src, resultText())
            } else {
                val unknown = promptVars.last()
                val r = Solver.solve(src, unknown, vars[unknown] ?: 0.0, angle, ans, memory, varMap())
                vars[unknown] = r.x
                preAns = ans; ans = r.x; ansIm = 0.0
                leaveAnswer(); promptKind = 4
                lastExpr = src
                expr = Calc.format(r.x, fmt); cursor = expr.length
                done = true
                lines = listOf(unknown to Calc.format(r.x, fmt), "L−R" to Calc.format(r.residual, fmt))
                noteHistory(src, "$unknown=${Calc.format(r.x, fmt)}")
            }
        } catch (e: CalcException) {
            promptKind = 0
            expr = src; cursor = expr.length
            error = e.message ?: "Math error"
        }
    }


    // ---- Things the screen asks for ----------------------------------------------------------

    /** What the input line shows: the sum being typed, or the finished sum with an "=" after it. */
    val inputText: String
        get() = if (done) (if (promptKind == 3 || promptKind == 4) promptExpr else lastExpr) else expr

    val inputCursor: Int
        get() = if (done) inputText.length else cursor.coerceIn(0, expr.length)

    /** A tap on the input line: put the cursor there (an answer reopens its sum first). */
    fun tapCursor(offset: Int) {
        error = ""
        if (done) reopen(offset.coerceAtLeast(0)) else cursor = offset.coerceIn(0, expr.length)
        refresh()
    }

    /** The number on the screen, without raising an error if the sum isn't finished. */
    fun peekValue(): Double? {
        if (done || expr.isBlank()) return ans
        return try {
            if (cmplx) evalComplex(expr).re else evalReal(expr, 15_000).value
        } catch (e: CalcException) {
            null
        }
    }

    fun recallHistory(i: Int) {
        if (i !in history.indices) return
        checkpoint()
        promptKind = 0
        leaveAnswer()
        error = ""
        expr = history[i].first
        cursor = expr.length
        refresh()
    }

    /** Types an earlier answer into the sum: 3/4 becomes 3÷4. */
    fun insertResult(i: Int) {
        if (i !in history.indices) return
        checkpoint()
        val r = history[i].second.replace('/', '÷')
        if (r.any { it == '=' || it == ' ' }) return
        type(r)
        refresh()
    }

    fun clearHistory() { history.clear(); browse = -1 }

    /** The answer as text for the clipboard (or what is typed, if there is no answer yet). */
    fun copyText(): String {
        if (!done) return if (preview.isNotEmpty()) preview else prettySum(expr)
        if (lines.isNotEmpty()) return lines.joinToString("\n") { if (it.first.isEmpty()) it.second else it.first + "=" + it.second }
        return resultText()
    }

    /** The answer as a plain decimal, whatever form is on screen. */
    fun copyDecimal(): String = if (cmplx || !done) copyText() else Calc.format(ans, fmt)

    /** True when the sum has calculus and trig in it but the angle unit isn't radians (answers look odd then). */
    fun needsRad(): Boolean {
        if (angle == AngleMode.Rad || cmplx) return false
        val src = if (done) (if (promptKind == 3 || promptKind == 4) promptExpr else lastExpr) else expr
        val calculus = "∫(" in src || "d/dx(" in src || "lim(" in src
        val trig = Regex("(?<![h])(a?(sin|cos|tan|cot))\\(").containsMatchIn(src)
        return calculus && trig
    }

    /** Puts one-shot keys and half-finished prompts away (used when reopening after the app was closed). */
    fun settle() {
        shift = false; alpha = false; second = false; hyp = false
        if (prompting) {
            expr = promptExpr; cursor = expr.length
            promptKind = 0
        }
        error = ""
        refresh()
    }

    /** Pastes text from the clipboard, keeping only what a sum can use. */
    fun pasteText(text: String) {
        val ok = tidyPaste(text.take(1000).filter { it.isLetterOrDigit() || it in ".,+-−×÷*/^()%!π∞°'\"²³ " }.trim())
        if (ok.isEmpty()) return
        checkpoint()
        type(ok)
        refresh()
    }

    /**
     * 1,000 and 12,345.5 are thousands separators (not decimal points), 3,14 is a decimal comma, and the
     * comma in gcd(12,18) stays a comma. Spaces inside digit groups (1 000) are thousands separators too.
     */
    private fun tidyPaste(t: String): String {
        var s = Regex("""(?<![\d.,])\d{1,3}(?:,\d{3})+(?![\d,])""").replace(t) { it.value.replace(",", "") }
        s = Regex("""(?<![\d.,])\d{1,3}(?: \d{3})+(?![\d,])""").replace(s) { it.value.replace(" ", "") }
        if ('(' !in s && '.' !in s && s.count { it == ',' } == 1 && Regex("""\d,\d""").containsMatchIn(s)) s = s.replace(',', '.')
        return s
    }

    /** Redraws an answer after a setting changed (format, polar or rectangular). */
    fun restyle() {
        if (done && cmplx) complexText = CalcComplex.format(Cx(ans, ansIm), polar, angle, fmt)
        refresh()
    }

    fun setFormat(kind: NumFormat.Kind, digits: Int) {
        val range = if (kind == NumFormat.Kind.Fix || kind == NumFormat.Kind.Sci) 0..9 else 8..13
        fmt = NumFormat(kind, digits.coerceIn(range))
        restyle()
    }

    fun changeAngle(a: AngleMode) { angle = a; restyle() }

    // ---- Saving across rotation --------------------------------------------------------------

    fun encode(): String {
        val p = Properties()
        p["expr"] = expr; p["cursor"] = cursor.toString(); p["lastExpr"] = lastExpr
        p["done"] = done.toString(); p["error"] = error
        p["ans"] = ans.toString(); p["ansIm"] = ansIm.toString(); p["preAns"] = preAns.toString(); p["memory"] = memory.toString()
        p["vars"] = vars.entries.joinToString(";") { it.key + "=" + it.value }
        p["lines"] = lines.joinToString("\u0001") { it.first + "\u0002" + it.second }
        p["complexText"] = complexText
        p["history"] = history.joinToString("\u0001") { it.first + "\u0002" + it.second }
        p["angle"] = angle.name; p["fmtKind"] = fmt.kind.name; p["fmtDigits"] = fmt.digits.toString()
        p["mathDisplay"] = mathDisplay.toString(); p["lcd"] = lcd.toString(); p["cmplx"] = cmplx.toString(); p["polar"] = polar.toString(); p["haptics"] = haptics.toString()
        p["shift"] = shift.toString(); p["alpha"] = alpha.toString(); p["second"] = second.toString(); p["hyp"] = hyp.toString()
        p["isBool"] = isBool.toString(); p["flip"] = flip.toString(); p["eng"] = eng.toString(); p["si"] = si.toString(); p["dms"] = dms.toString()
        p["promptKind"] = promptKind.toString(); p["promptExpr"] = promptExpr
        p["promptVars"] = promptVars.joinToString(","); p["promptIndex"] = promptIndex.toString()
        val w = StringWriter()
        p.store(w, null)
        return w.toString()
    }

    companion object {
        fun decode(text: String): CalcModel {
            val m = CalcModel()
            try {
                val p = Properties()
                p.load(StringReader(text))
                fun s(k: String) = p.getProperty(k) ?: ""
                fun b(k: String) = s(k) == "true"
                fun d(k: String) = s(k).toDoubleOrNull() ?: 0.0
                fun pairs(k: String) = s(k).split("\u0001").filter { it.isNotEmpty() }.map { it.substringBefore("\u0002") to it.substringAfter("\u0002", "") }
                m.expr = s("expr"); m.cursor = (s("cursor").toIntOrNull() ?: 0).coerceIn(0, m.expr.length)
                m.lastExpr = s("lastExpr"); m.done = b("done"); m.error = s("error")
                m.ans = d("ans"); m.ansIm = d("ansIm"); m.preAns = d("preAns"); m.memory = d("memory")
                s("vars").split(";").filter { it.contains('=') }.forEach { m.vars[it.substringBefore('=')] = it.substringAfter('=').toDoubleOrNull() ?: 0.0 }
                m.lines = pairs("lines"); m.complexText = s("complexText")
                m.history.addAll(pairs("history"))
                m.angle = AngleMode.entries.firstOrNull { it.name == s("angle") } ?: AngleMode.Deg
                m.fmt = NumFormat(
                    NumFormat.Kind.entries.firstOrNull { it.name == s("fmtKind") } ?: NumFormat.Kind.Norm2,
                    (s("fmtDigits").toIntOrNull() ?: 13).coerceIn(1, 15),
                )
                m.mathDisplay = b("mathDisplay"); m.lcd = (s("lcd").toIntOrNull() ?: 0).coerceIn(0, LcdThemes.lastIndex)
                m.cmplx = b("cmplx"); m.polar = b("polar"); m.haptics = s("haptics") != "false"
                m.shift = b("shift"); m.alpha = b("alpha"); m.second = b("second"); m.hyp = b("hyp")
                m.isBool = b("isBool"); m.flip = b("flip"); m.eng = b("eng"); m.si = b("si"); m.dms = b("dms")
                m.promptKind = s("promptKind").toIntOrNull() ?: 0; m.promptExpr = s("promptExpr")
                m.promptVars = s("promptVars").split(",").filter { it.isNotEmpty() }
                m.promptIndex = s("promptIndex").toIntOrNull() ?: 0
                if (m.prompting && m.promptIndex !in m.promptVars.indices) m.promptKind = 0
            } catch (e: Exception) {
                return CalcModel()
            }
            m.refresh()
            return m
        }
    }
}


/** What each layer of a key does, for the long-press help. */
internal fun describeKey(k: CalcKeyDef): List<Pair<String, String>> {
    fun what(l: L): String = when (l.act) {
        KeyAct.Calc -> "Asks for the value of each letter in the sum, then works it out"
        KeyAct.Solve -> "Finds x in an equation like x²−4=0 (type = with ALPHA + CALC or the = chip)"
        KeyAct.Sto -> "Stores the number on screen in a letter A–F, x, y, z, t or M"
        KeyAct.Rcl -> "Types a stored letter into the sum"
        KeyAct.ClrVars -> "Sets all the letters back to 0"
        KeyAct.Sd -> "Swaps the answer between exact (3/4, √2) and decimal"
        KeyAct.Eng -> "Shows the answer as a multiple of 10³, like 12.3×10³"
        KeyAct.Si -> "Shows the answer with an SI prefix, like 12.3k"
        KeyAct.Dms -> "Types degrees, minutes, seconds; on an answer it shows that form"
        KeyAct.Fact -> "Prime factors of the number on screen"
        KeyAct.Mode -> "Opens the mode menu: Complex, Base-N, Statistics, Matrix, Vector, Distribution"
        KeyAct.Const -> "Opens the list of physical constants"
        KeyAct.Conv -> "Converts the number on screen between units"
        KeyAct.Func, KeyAct.Help -> "Opens the list of functions with help and examples"
        KeyAct.Matrix -> "Opens the matrix page"
        KeyAct.Vector -> "Opens the vector page"
        KeyAct.Stat -> "Opens the statistics page"
        KeyAct.Cmplx -> "Switches complex-number mode on or off"
        KeyAct.Distr -> "Opens the distribution page"
        KeyAct.History -> "Opens your earlier sums"
        KeyAct.Copy -> "Copies the answer"
        KeyAct.Paste -> "Pastes into the sum"
        KeyAct.Frac -> "Starts a stacked fraction: the number before the cursor goes on top, then type the bottom. ▲ ▼ switch between top and bottom"
        KeyAct.Compare -> "Opens the comparison signs: = ≠ > < ≥ ≤ (a sum like 2+2=4 answers True or False)"
        KeyAct.MemAdd -> "Adds the number on screen to memory M"
        KeyAct.MemSub -> "Subtracts the number on screen from memory M"
        KeyAct.Del -> "Deletes before the cursor"
        KeyAct.Ac -> "Clears the sum"
        KeyAct.ClrAll -> "Clears the sum, memory, letters and history"
        KeyAct.Eq -> "Works the sum out"
        KeyAct.Shift -> "Next key uses its amber job"
        KeyAct.Alpha -> "Next key uses its violet job (letters and extras)"
        KeyAct.Second -> "Switches some keys to Int, Frac, Sign, log₂ and Round"
        KeyAct.Hyp -> "Next trig key is hyperbolic (sinh, cosh, tanh)"
        else -> "Types " + l.insert.trim()
    }
    val out = ArrayList<Pair<String, String>>()
    out += k.label to what(L(k.label, k.insert, k.act))
    k.shift?.let { out += "SHIFT  ${it.label}" to what(it) }
    k.alpha?.let { out += "ALPHA  ${it.label}" to what(it) }
    k.second?.let { out += "2nd  ${it.label}" to what(it) }
    k.hyp?.let { out += "HYP  ${it.label}" to what(it) }
    k.shiftHyp?.let { out += "SHIFT + HYP  ${it.label}" to what(it) }
    return out
}
