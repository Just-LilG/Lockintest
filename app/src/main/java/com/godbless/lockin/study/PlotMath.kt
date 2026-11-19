package com.godbless.lockin.study

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.PI
import kotlin.math.E

/*
 * The numbers behind the AI's graphs, diagrams and "checked" marks. Pure Kotlin (no Android): see PlotMathTest.
 *
 *  - Expr: a small expression evaluator ("x^2 - 4x + 3", "2pi", "3(x+1)", "sin x"). Never throws; a bad input is null and
 *    a maths error (1/0, sqrt of a negative) is NaN, so a curve just has a gap there.
 *  - NumCheck: does "2^3 + 4 = 12" hold? Only formulas made of numbers alone are judged, everything else is "cannot tell".
 *  - PlotSpec: the ```plot block the AI writes.
 *  - DiagramSpec / DiagramLayout: the ```flow, ```cycle and ```timeline blocks and where their boxes go.
 */

// ---- Expressions --------------------------------------------------------------------------------------------------------
internal sealed class Ex {
    class Num(val v: Double) : Ex()
    class V(val name: Char) : Ex()
    class Neg(val a: Ex) : Ex()
    class Bin(val op: Char, val a: Ex, val b: Ex) : Ex()
    class Fn(val name: String, val a: Ex) : Ex()
}

internal object Expr {
    class Parsed(val ex: Ex, val vars: Set<Char>)

    private class Tk(val kind: Char, val num: Double = 0.0, val text: String = "")

    private val FUNC_NAMES = setOf("sqrt", "floor", "ceil", "asin", "acos", "atan", "sin", "cos", "tan", "exp", "abs", "log", "ln")
    private val CONST_NAMES = setOf("pi", "e")
    private val NAMES = (FUNC_NAMES + CONST_NAMES).sortedByDescending { it.length }

    /** The expression for [s], or null when it cannot be read. */
    fun parse(s: String): Parsed? {
        val toks = tokens(s) ?: return null
        val p = ExprParser(toks)
        return try {
            val e = p.expr()
            if (p.i != toks.size) null else Parsed(e, p.vars)
        } catch (x: IllegalStateException) {
            null
        }
    }

    private fun tokens(s: String): List<Tk>? {
        val out = ArrayList<Tk>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                    out.add(Tk('n', num = s.substring(i, j).toDoubleOrNull() ?: return null))
                    i = j
                }
                c.isLetter() -> {
                    var hit: String? = null
                    for (nm in NAMES) {
                        if (!s.startsWith(nm, i)) continue
                        if (nm == "e" && i + 1 < s.length && s[i + 1].isLetter() && NAMES.none { s.startsWith(it, i + 1) }) continue
                        hit = nm
                        break
                    }
                    if (hit != null) {
                        out.add(Tk(if (hit in FUNC_NAMES) 'f' else 'k', text = hit))
                        i += hit.length
                    } else {
                        out.add(Tk('v', text = c.toString()))
                        i++
                    }
                }
                c == '+' || c == '*' || c == '/' || c == '^' || c == '(' || c == ')' || c == '|' -> { out.add(Tk(c)); i++ }
                c == '-' || c == '−' -> { out.add(Tk('-')); i++ }
                c == '×' || c == '·' -> { out.add(Tk('*')); i++ }
                c == '÷' -> { out.add(Tk('/')); i++ }
                else -> return null
            }
        }
        return out
    }

    private class ExprParser(val t: List<Tk>) {
        var i = 0
        val vars = HashSet<Char>()
        private var depth = 0

        fun peek(): Char? = if (i < t.size) t[i].kind else null
        fun take(): Tk = t[i++]
        private fun bad(): Nothing = throw IllegalStateException("bad expression")

        fun expr(): Ex {
            if (++depth > 60) bad()
            var a = term()
            while (peek() == '+' || peek() == '-') {
                val op = take().kind
                a = Ex.Bin(op, a, term())
            }
            depth--
            return a
        }

        private fun startsFactor(): Boolean = when (peek()) { 'n', 'v', 'k', 'f', '(' -> true; else -> false }

        private fun term(): Ex {
            var a = unary()
            while (true) {
                val p = peek()
                if (p != null && (p == '*' || p == '/')) {
                    take()
                    a = Ex.Bin(p, a, unary())
                } else if (startsFactor()) {
                    a = Ex.Bin('*', a, unary())
                } else return a
            }
        }

        private fun unary(): Ex {
            if (peek() == '-') { take(); return Ex.Neg(unary()) }
            if (peek() == '+') { take(); return unary() }
            return power()
        }

        private fun power(): Ex {
            val a = atom()
            if (peek() == '^') { take(); return Ex.Bin('^', a, unary()) }
            return a
        }

        private fun atom(): Ex {
            if (peek() == null) bad()
            val tk = take()
            when (tk.kind) {
                'n' -> return Ex.Num(tk.num)
                'k' -> return Ex.Num(if (tk.text == "pi") PI else E)
                'v' -> { vars.add(tk.text[0]); return Ex.V(tk.text[0]) }
                'f' -> {
                    if (peek() == '(') {
                        take()
                        val a = expr()
                        if (peek() != ')') bad()
                        take()
                        return Ex.Fn(tk.text, a)
                    }
                    return Ex.Fn(tk.text, power())
                }
                '(' -> {
                    val a = expr()
                    if (peek() != ')') bad()
                    take()
                    return a
                }
                '|' -> {
                    val a = expr()
                    if (peek() != '|') bad()
                    take()
                    return Ex.Fn("abs", a)
                }
            }
            bad()
        }
    }

    /** The value, or NaN where the maths has no answer. */
    fun eval(e: Ex, env: Map<Char, Double>): Double = when (e) {
        is Ex.Num -> e.v
        is Ex.V -> env[e.name] ?: Double.NaN
        is Ex.Neg -> -eval(e.a, env)
        is Ex.Fn -> {
            val a = eval(e.a, env)
            when (e.name) {
                "sin" -> sin(a); "cos" -> cos(a); "tan" -> tan(a); "asin" -> asin(a); "acos" -> acos(a); "atan" -> atan(a)
                "sqrt" -> if (a < 0) Double.NaN else sqrt(a)
                "ln" -> if (a <= 0) Double.NaN else ln(a)
                "log" -> if (a <= 0) Double.NaN else log10(a)
                "exp" -> exp(a); "abs" -> abs(a); "floor" -> floor(a); "ceil" -> ceil(a)
                else -> Double.NaN
            }
        }
        is Ex.Bin -> {
            val a = eval(e.a, env)
            val b = eval(e.b, env)
            when (e.op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> if (b == 0.0) Double.NaN else a / b
                '^' -> a.pow(b)
                else -> Double.NaN
            }
        }
    }
}

// ---- "Is this sum right?" -----------------------------------------------------------------------------------------------
internal object NumCheck {
    private val FRAC = Regex("""\\d?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""")
    private val ROOT_N = Regex("""\\sqrt\s*\[([^\]]*)\]\s*\{([^{}]*)\}""")
    private val ROOT = Regex("""\\sqrt\s*\{([^{}]*)\}""")
    private val POW = Regex("""\^\s*\{([^{}]*)\}""")
    private val KNOWN = Regex("""\b(sqrt|pi)\b""")
    private val LEFTOVER = Regex("""[\\%°A-Za-z]""")
    private val SPLIT = Regex("""(=|\\approx)""")
    private val DECIMAL = Regex("""-?\d+\.\d+""")
    private val DECIMALS = Regex("""\.(\d+)\s*$""")

    /** Numbers-only LaTeX to the evaluator's syntax, or null when anything else is in it. */
    fun toExpr(tex: String): String? {
        var t = tex.replace("\\left", "").replace("\\right", "").replace("\\,", "").replace("\\!", "").replace("\\ ", " ")
        repeat(8) {
            var u = FRAC.replace(t) { "((" + it.groupValues[1] + ")/(" + it.groupValues[2] + "))" }
            u = ROOT_N.replace(u) { "((" + it.groupValues[2] + ")^(1/(" + it.groupValues[1] + ")))" }
            u = ROOT.replace(u) { "sqrt(" + it.groupValues[1] + ")" }
            u = POW.replace(u) { "^(" + it.groupValues[1] + ")" }
            if (u == t) return@repeat
            t = u
        }
        t = t.replace("\\cdot", "*").replace("\\times", "*").replace("\\div", "/").replace("\\pi", "pi")
        if (LEFTOVER.containsMatchIn(KNOWN.replace(t, ""))) return null
        return t.replace('{', '(').replace('}', ')')
    }

    /** true: every = and \approx holds. false: one does not. null: cannot tell (letters, units, one side only...). */
    fun check(tex: String): Boolean? {
        if (tex.indexOf('=') < 0 && !tex.contains("\\approx")) return null // the common case, answered without a regex
        val pieces = SPLIT.split(tex)
        if (pieces.size < 2) return null
        val ops = SPLIT.findAll(tex).map { it.value }.toList()
        val values = ArrayList<Double>()
        for (side in pieces) {
            val e = toExpr(side.trim()) ?: return null
            val p = Expr.parse(e) ?: return null
            if (p.vars.isNotEmpty()) return null
            val v = Expr.eval(p.ex, emptyMap())
            if (v.isNaN() || v.isInfinite()) return null
            values.add(v)
        }
        var ok = true
        for ((i, op) in ops.withIndex()) {
            val a = values[i]
            val b = values[i + 1]
            val rhs = pieces[i + 1].trim()
            var tol: Double
            if (op == "=") {
                tol = 1e-9 * maxOf(1.0, abs(a), abs(b))
                if (DECIMAL.matches(rhs)) {
                    val d = DECIMALS.find(rhs)?.groupValues?.get(1)?.length ?: 0
                    tol = maxOf(tol, 0.5 * 10.0.pow(-d) + 1e-12)
                }
            } else tol = 0.01 * maxOf(1.0, abs(b))
            if (abs(a - b) > tol) ok = false
        }
        return ok
    }
}

// ---- ```plot ------------------------------------------------------------------------------------------------------------
internal class PlotCurve(val src: String, val ex: Ex)
internal class PlotPoint(val x: Double, val y: Double, val label: String)
internal class PlotParam(val name: Char, val value: Double, val lo: Double, val hi: Double)

internal class PlotSpec(
    val curves: List<PlotCurve>,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double?,
    val yMax: Double?,
    val points: List<PlotPoint>,
    val params: List<PlotParam>,
    val title: String,
) {
    companion object {
        private const val RANGE = """(-?[\d.]+)\s*(?:\.\.|to|,)\s*(-?[\d.]+)"""
        private val X_RANGE = Regex("^x\\s*:\\s*" + RANGE + "\\s*$")
        private val Y_RANGE = Regex("^y\\s*:\\s*" + RANGE + "\\s*$")
        private val TITLE = Regex("^title\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        private val POINTS = Regex("^points?\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        private val POINT = Regex("""\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)\s*([^;(]*)""")
        private val PARAM = Regex("^param\\s*:\\s*([a-z])\\s*=\\s*(-?[\\d.]+)\\s*(?:\\[\\s*" + RANGE + "\\s*\\])?\\s*$")
        private val CURVE = Regex("""^(?:[yf]\d?(?:\(x\))?|g(?:\(x\))?)\s*=\s*(.+)$""", RegexOption.IGNORE_CASE)

        /** The spec for a plot block, or null when it cannot be drawn (the caller shows it as code instead). */
        fun parse(text: String): PlotSpec? {
            val curves = ArrayList<PlotCurve>()
            val points = ArrayList<PlotPoint>()
            val params = ArrayList<PlotParam>()
            var x: Pair<Double, Double>? = null
            var y: Pair<Double, Double>? = null
            var title = ""
            val curveVars = ArrayList<Set<Char>>()
            for (raw in text.trim().split('\n')) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val low = line.lowercase()
                val mx = X_RANGE.find(low)
                if (mx != null) {
                    x = Pair(mx.groupValues[1].toDoubleOrNull() ?: return null, mx.groupValues[2].toDoubleOrNull() ?: return null)
                    continue
                }
                val my = Y_RANGE.find(low)
                if (my != null) {
                    y = Pair(my.groupValues[1].toDoubleOrNull() ?: return null, my.groupValues[2].toDoubleOrNull() ?: return null)
                    continue
                }
                val mt = TITLE.find(line)
                if (mt != null) { title = mt.groupValues[1].trim(); continue }
                val mp = POINTS.find(line)
                if (mp != null) {
                    for (pt in POINT.findAll(mp.groupValues[1])) {
                        val px = pt.groupValues[1].toDoubleOrNull() ?: continue
                        val py = pt.groupValues[2].toDoubleOrNull() ?: continue
                        points.add(PlotPoint(px, py, pt.groupValues[3].trim()))
                    }
                    continue
                }
                val mr = PARAM.find(low)
                if (mr != null) {
                    val v = mr.groupValues[2].toDoubleOrNull() ?: return null
                    var lo = mr.groupValues[3].toDoubleOrNull() ?: minOf(-5.0, v - 5)
                    var hi = mr.groupValues[4].toDoubleOrNull() ?: maxOf(5.0, v + 5)
                    if (!(lo < hi)) { lo = v - 5; hi = v + 5 } // a slider needs a range
                    if (params.size < 3) params.add(PlotParam(mr.groupValues[1][0], v.coerceIn(lo, hi), lo, hi))
                    continue
                }
                val src = CURVE.find(line)?.groupValues?.get(1) ?: line
                val parsed = Expr.parse(src) ?: return null
                if (curves.size < 3) { curves.add(PlotCurve(src, parsed.ex)); curveVars.add(parsed.vars) }
            }
            val xr = x ?: return null
            if (curves.isEmpty() || !(xr.first < xr.second)) return null
            val allowed = params.map { it.name }.toSet() + 'x'
            if (curveVars.any { !allowed.containsAll(it) }) return null
            return PlotSpec(curves, xr.first, xr.second, y?.first, y?.second, points, params, title)
        }
    }
}

internal object PlotCalc {
    class Sampled(val xs: DoubleArray, val ys: List<DoubleArray>)

    fun sample(spec: PlotSpec, params: Map<Char, Double>, n: Int = 240): Sampled {
        val xs = DoubleArray(n) { spec.xMin + (spec.xMax - spec.xMin) * it / (n - 1) }
        val env = HashMap<Char, Double>(params)
        val ys = spec.curves.map { c ->
            DoubleArray(n) { i -> env['x'] = xs[i]; Expr.eval(c.ex, env) }
        }
        return Sampled(xs, ys)
    }

    /** The vertical range to show: the AI's, else what the curves do, ignoring the wild 1% so a pole does not flatten it. */
    fun yRange(spec: PlotSpec, s: Sampled): Pair<Double, Double> {
        if (spec.yMin != null && spec.yMax != null && spec.yMin < spec.yMax) return Pair(spec.yMin, spec.yMax)
        val vals = ArrayList<Double>()
        for (ys in s.ys) for (v in ys) if (!v.isNaN() && abs(v) < 1e6) vals.add(v)
        for (p in spec.points) vals.add(p.y)
        if (vals.isEmpty()) return Pair(-1.0, 1.0)
        vals.sort()
        var lo = vals[(vals.size * 0.01).toInt()]
        var hi = vals[maxOf(0, if (vals.size > 3) (vals.size * 0.99).toInt() - 1 else vals.size - 1)]
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.1
        return Pair(lo - pad, hi + pad)
    }

    /** A round tick step (1, 2 or 5 times a power of ten) giving about [target] ticks over [span]. */
    fun niceStep(span: Double, target: Int = 5): Double {
        val raw = span / target
        val p = 10.0.pow(floor(log10(raw)))
        for (m in doubleArrayOf(1.0, 2.0, 5.0, 10.0)) if (raw <= m * p) return m * p
        return 10 * p
    }
}

// ---- Diagrams ---------------------------------------------------------------------------------------------------------
internal class DiagramSpec(
    val kind: String,
    val nodes: List<String>,
    val edges: List<Triple<Int, Int, String?>>,
    val items: List<Pair<String, String>>,
) {
    companion object {
        private val TIMELINE_LINE = Regex("""^(.{1,24}?)\s*(?::|\||—|–| - )\s+(.+)$""")
        private val LABELLED = Regex("""^(.*?)\s*:\s*([^>:]+)$""")
        private val ARROW = Regex("""\s*(?:->|→)\s*""")

        /** [kind] is "flow", "cycle" or "timeline". Null when there is nothing to draw or too much (over 12 boxes). */
        fun parse(kind: String, text: String): DiagramSpec? {
            val lines = text.trim().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (kind == "timeline") {
                val items = lines.map { l ->
                    val m = TIMELINE_LINE.find(l)
                    if (m != null) Pair(m.groupValues[1], m.groupValues[2]) else Pair("", l)
                }
                return if (items.isEmpty() || items.size > 14) null else DiagramSpec(kind, emptyList(), emptyList(), items)
            }
            val nodes = ArrayList<String>()
            val edges = ArrayList<Triple<Int, Int, String?>>()
            fun node(raw: String): Int {
                val label = raw.trim().trim('-', '•', '*').trim()
                if (label !in nodes) nodes.add(label)
                return nodes.indexOf(label)
            }
            for (line0 in lines) {
                var l = line0
                var label: String? = null
                val hasArrow = l.contains("->") || l.contains("→")
                if (hasArrow) {
                    val tail = l.split("->", "→").last()
                    if (tail.contains(':')) {
                        LABELLED.find(l)?.let { m -> l = m.groupValues[1]; label = m.groupValues[2].trim() }
                    }
                }
                val parts = l.split(ARROW).filter { it.isNotBlank() }
                if (!hasArrow) { if (parts.isNotEmpty()) node(parts[0]); continue }
                val ids = parts.map { node(it) }
                for (k in 0 until ids.size - 1) edges.add(Triple(ids[k], ids[k + 1], if (k == ids.size - 2) label else null))
            }
            if (kind == "cycle" && edges.isEmpty() && nodes.size > 1) {
                for (i in nodes.indices) edges.add(Triple(i, (i + 1) % nodes.size, null))
            }
            if (nodes.isEmpty() || nodes.size > 12) return null
            return DiagramSpec(kind, nodes, edges, emptyList())
        }
    }
}

internal class NodeRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
}

internal class DiagramLayout(val rects: List<NodeRect>, val height: Float)

internal object DiagramPlacer {
    /** Layers top to bottom by the longest path from the starts; edges that close a loop are ignored for layering. */
    fun layers(d: DiagramSpec): List<List<Int>> {
        val n = d.nodes.size
        val out = List(n) { ArrayList<Int>() }
        for ((a, b, _) in d.edges) out[a].add(b)
        val state = IntArray(n)
        val back = HashSet<Pair<Int, Int>>()
        fun dfs(u: Int) {
            state[u] = 1
            for (v in out[u]) {
                if (state[v] == 1) back.add(Pair(u, v)) else if (state[v] == 0) dfs(v)
            }
            state[u] = 2
        }
        for (u in 0 until n) if (state[u] == 0) dfs(u)
        val layer = IntArray(n)
        repeat(n + 1) {
            var changed = false
            for ((a, b, _) in d.edges) {
                if (Pair(a, b) in back) continue
                if (layer[b] < layer[a] + 1) { layer[b] = layer[a] + 1; changed = true }
            }
            if (!changed) return@repeat
        }
        val rows = java.util.TreeMap<Int, ArrayList<Int>>()
        for (i in 0 until n) rows.getOrPut(layer[i]) { ArrayList() }.add(i)
        return rows.values.toList()
    }

    /** [sizeFor] gives a box's size for a label and the widest it may be. */
    fun flow(d: DiagramSpec, avail: Float, gapX: Float, gapY: Float, sizeFor: (String, Float) -> Pair<Float, Float>): DiagramLayout {
        val rects = arrayOfNulls<NodeRect>(d.nodes.size)
        var y = 0f
        for (row in layers(d)) {
            val k = row.size
            val maxW = (avail - gapX * (k - 1)) / k
            val sizes = row.map { sizeFor(d.nodes[it], maxW) }
            val h = sizes.maxOf { it.second }
            val total = sizes.sumOf { it.first.toDouble() }.toFloat() + gapX * (k - 1)
            var x = (avail - total) / 2
            for ((idx, i) in row.withIndex()) {
                val (w, hh) = sizes[idx]
                rects[i] = NodeRect(x, y + (h - hh) / 2, w, hh)
                x += w + gapX
            }
            y += h + gapY
        }
        return DiagramLayout(rects.map { it ?: NodeRect(0f, 0f, 0f, 0f) }, maxOf(0f, y - gapY))
    }

    fun cycle(d: DiagramSpec, avail: Float, sizeFor: (String, Float) -> Pair<Float, Float>): DiagramLayout {
        val n = d.nodes.size
        val sizes = d.nodes.map { sizeFor(it, avail * 0.40f) }
        val r = maxOf(avail / 2 - sizes.maxOf { it.first } / 2, 40f)
        val ry = if (n > 2) r * 0.9f else r
        val cx = avail / 2
        val cy = ry + sizes.maxOf { it.second } / 2
        val rects = sizes.mapIndexed { i, (w, h) ->
            val a = -PI / 2 + 2 * PI * i / n
            NodeRect(cx + (r * cos(a)).toFloat() - w / 2, cy + (ry * sin(a)).toFloat() - h / 2, w, h)
        }
        return DiagramLayout(rects, cy * 2)
    }
}
