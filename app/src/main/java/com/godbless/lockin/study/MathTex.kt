package com.godbless.lockin.study

import kotlin.math.max

/*
 * The maths engine: a LaTeX subset -> a tree -> "boxes" made of a few drawing operations. Pure Kotlin (no Android, no
 * Compose) so it runs in plain JVM tests; MathText.kt measures text and draws the operations.
 *
 * Coordinates inside a box: x to the right, y DOWN from the baseline (so "up" is negative). A box knows its width and how
 * far it reaches above (asc) and below (desc) the baseline. The layout follows TeX's rules in spirit (fractions, scripts,
 * radicals, big operators, delimiters, spacing between symbols) with sizes tuned for a phone.
 *
 * It never throws at the caller: [MathTex.parse] returns null for anything too long, too deep or broken, and the caller
 * shows the source text instead.
 */

// ---- Atom classes (TeX spacing), styles, fonts ----------------------------------------------------------------------
internal const val C_ORD = 0
internal const val C_OP = 1
internal const val C_BIN = 2
internal const val C_REL = 3
internal const val C_OPEN = 4
internal const val C_CLOSE = 5
internal const val C_PUNCT = 6

internal const val ST_D = 0 // display
internal const val ST_T = 1 // text (inline)
internal const val ST_S = 2 // script
internal const val ST_SS = 3 // scriptscript

internal const val F_RM = 0
internal const val F_IT = 1
internal const val F_BF = 2

private val SCALE = floatArrayOf(1f, 1f, 0.7f, 0.5f)
private const val AXIS = 0.25f
private const val RT = 0.05f

private fun scriptStyle(st: Int) = when (st) { ST_D, ST_T -> ST_S; else -> ST_SS }
private fun fracStyle(st: Int) = when (st) { ST_D -> ST_T; ST_T -> ST_S; else -> ST_SS }

// ---- The tree ---------------------------------------------------------------------------------------------------------
internal sealed class MNode {
    class Seq(val items: List<MNode>) : MNode()
    class Sym(val text: String, val cls: Int, val font: Int) : MNode()
    class Txt(val text: String, val font: Int) : MNode()
    class Fn(val name: String, val limits: Boolean) : MNode()
    class Space(val em: Float) : MNode()
    class Frac(val num: MNode, val den: MNode, val force: Int, val bar: Boolean) : MNode()
    class Scripts(val base: MNode, val sub: MNode?, val sup: MNode?) : MNode()
    class Sqrt(val body: MNode, val index: MNode?) : MNode()
    class Lr(val left: String, val right: String, val body: MNode) : MNode()
    class Acc(val kind: String, val body: MNode) : MNode()
    class BigOp(val name: String) : MNode()
    class Style(val style: Int, val body: MNode) : MNode()
    class Font(val font: Int, val body: MNode) : MNode()
    class Tint(val idx: Int, val body: MNode) : MNode()
    class Mat(val rows: List<List<MNode>>, val env: String, val spec: String?, val left: String, val right: String) : MNode()
}

internal class MathError(msg: String) : Exception(msg)

// ---- Drawing operations -----------------------------------------------------------------------------------------------
internal sealed class MOp {
    /** 0 = the text colour; 1..5 = lime, flare, sky, amber, mist (see [tintIndex]); 9 = the highlighter wash behind marked text. */
    var tint: Int = 0

    protected abstract fun move(dx: Float, dy: Float): MOp
    fun shift(dx: Float, dy: Float): MOp { val m = move(dx, dy); m.tint = tint; return m }
    fun tinted(i: Int): MOp { val m = move(0f, 0f); m.tint = i; return m }

    class Text(val x: Float, val y: Float, val text: String, val size: Float, val font: Int) : MOp() {
        /** The platform's measured text, set by the painter on the first draw so later frames do not look it up again. */
        var cached: Any? = null
        override fun move(dx: Float, dy: Float): MOp { val t = Text(x + dx, y + dy, text, size, font); t.cached = cached; return t }
    }
    class Rule(val x: Float, val y: Float, val w: Float, val h: Float) : MOp() {
        override fun move(dx: Float, dy: Float): MOp = Rule(x + dx, y + dy, w, h)
    }
    class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val w: Float) : MOp() {
        override fun move(dx: Float, dy: Float): MOp = Line(x1 + dx, y1 + dy, x2 + dx, y2 + dy, w)
    }
    class Quad(val x1: Float, val y1: Float, val cx: Float, val cy: Float, val x2: Float, val y2: Float, val w: Float) : MOp() {
        override fun move(dx: Float, dy: Float): MOp = Quad(x1 + dx, y1 + dy, cx + dx, cy + dy, x2 + dx, y2 + dy, w)
    }
    class Dot(val x: Float, val y: Float, val r: Float) : MOp() {
        override fun move(dx: Float, dy: Float): MOp = Dot(x + dx, y + dy, r)
    }
    /** Not drawn: a piece of the formula (a fraction, a root, a variable...) the student can tap to have explained. */
    class Spot(val x: Float, val y: Float, val w: Float, val h: Float, val node: MNode) : MOp() {
        override fun move(dx: Float, dy: Float): MOp = Spot(x + dx, y + dy, w, h, node)
    }
}

/** Colour names the AI may use (in text as {lime|a}, in maths as \textcolor{lime}{a}) to the [MOp.tint] they mean. */
internal fun tintIndex(name: String): Int = when (name.trim().lowercase()) {
    "lime", "green" -> 1
    "flare", "red" -> 2
    "sky", "blue" -> 3
    "amber", "orange", "yellow" -> 4
    "mist", "gray", "grey" -> 5
    else -> 0
}

internal object MathHit {
    /** The smallest tappable piece at ([px], [py]) in a box's own coordinates (origin on its baseline), or null. */
    fun find(ops: List<MOp>, px: Float, py: Float, slop: Float): MOp.Spot? {
        var best: MOp.Spot? = null
        var bestArea = Float.MAX_VALUE
        for (o in ops) {
            if (o !is MOp.Spot) continue
            if (px >= o.x - slop && px <= o.x + o.w + slop && py >= o.y - slop && py <= o.y + o.h + slop) {
                val area = o.w * o.h
                if (area < bestArea) { best = o; bestArea = area }
            }
        }
        return best
    }
}

internal class MBox(val w: Float, val asc: Float, val desc: Float, val ops: List<MOp>) {
    val height: Float get() = asc + desc
    companion object { val EMPTY = MBox(0f, 0f, 0f, emptyList()) }
}

/** What the layout needs from the font: the width of a run of text at a size. */
internal fun interface MathMetrics {
    fun width(text: String, font: Int, size: Float): Float
}

// ---- Tables -----------------------------------------------------------------------------------------------------------
private val GREEK = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε", "zeta" to "ζ",
    "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
    "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ",
    "upsilon" to "υ", "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
)
private val GREEK_UP = mapOf(
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ",
    "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
)

private fun s(t: String, c: Int) = Pair(t, c)
private val SYMS: Map<String, Pair<String, Int>> = mapOf(
    "times" to s("×", C_BIN), "cdot" to s("·", C_BIN), "div" to s("÷", C_BIN), "pm" to s("±", C_BIN), "mp" to s("∓", C_BIN),
    "ast" to s("∗", C_BIN), "star" to s("⋆", C_BIN), "circ" to s("∘", C_BIN), "oplus" to s("⊕", C_BIN), "otimes" to s("⊗", C_BIN),
    "cup" to s("∪", C_BIN), "cap" to s("∩", C_BIN), "setminus" to s("∖", C_BIN), "wedge" to s("∧", C_BIN), "land" to s("∧", C_BIN),
    "vee" to s("∨", C_BIN), "lor" to s("∨", C_BIN), "bullet" to s("•", C_BIN),
    "le" to s("≤", C_REL), "leq" to s("≤", C_REL), "ge" to s("≥", C_REL), "geq" to s("≥", C_REL), "ne" to s("≠", C_REL),
    "neq" to s("≠", C_REL), "approx" to s("≈", C_REL), "equiv" to s("≡", C_REL), "sim" to s("∼", C_REL), "simeq" to s("≃", C_REL),
    "cong" to s("≅", C_REL), "propto" to s("∝", C_REL), "in" to s("∈", C_REL), "notin" to s("∉", C_REL), "ni" to s("∋", C_REL),
    "subset" to s("⊂", C_REL), "supset" to s("⊃", C_REL), "subseteq" to s("⊆", C_REL), "supseteq" to s("⊇", C_REL),
    "ll" to s("≪", C_REL), "gg" to s("≫", C_REL), "perp" to s("⊥", C_REL), "parallel" to s("∥", C_REL), "mid" to s("∣", C_REL),
    "to" to s("→", C_REL), "rightarrow" to s("→", C_REL), "leftarrow" to s("←", C_REL), "leftrightarrow" to s("↔", C_REL),
    "Rightarrow" to s("⇒", C_REL), "Leftarrow" to s("⇐", C_REL), "Leftrightarrow" to s("⇔", C_REL), "implies" to s("⟹", C_REL),
    "iff" to s("⟺", C_REL), "mapsto" to s("↦", C_REL), "uparrow" to s("↑", C_REL), "downarrow" to s("↓", C_REL),
    "infty" to s("∞", C_ORD), "partial" to s("∂", C_ORD), "nabla" to s("∇", C_ORD), "forall" to s("∀", C_ORD),
    "exists" to s("∃", C_ORD), "emptyset" to s("∅", C_ORD), "varnothing" to s("∅", C_ORD), "neg" to s("¬", C_ORD),
    "lnot" to s("¬", C_ORD), "angle" to s("∠", C_ORD), "triangle" to s("△", C_ORD), "degree" to s("°", C_ORD),
    "prime" to s("′", C_ORD), "ell" to s("ℓ", C_ORD), "hbar" to s("ℏ", C_ORD), "therefore" to s("∴", C_REL),
    "because" to s("∵", C_REL), "ldots" to s("…", C_ORD), "dots" to s("…", C_ORD), "cdots" to s("⋯", C_ORD),
    "vdots" to s("⋮", C_ORD), "ddots" to s("⋱", C_ORD), "checkmark" to s("✓", C_ORD), "square" to s("□", C_ORD),
    "aleph" to s("ℵ", C_ORD), "Re" to s("ℜ", C_ORD), "Im" to s("ℑ", C_ORD),
    "%" to s("%", C_ORD), "$" to s("$", C_ORD), "&" to s("&", C_ORD), "#" to s("#", C_ORD), "_" to s("_", C_ORD),
    "{" to s("{", C_OPEN), "}" to s("}", C_CLOSE), "lbrace" to s("{", C_OPEN), "rbrace" to s("}", C_CLOSE),
    "langle" to s("⟨", C_OPEN), "rangle" to s("⟩", C_CLOSE), "lfloor" to s("⌊", C_OPEN), "rfloor" to s("⌋", C_CLOSE),
    "lceil" to s("⌈", C_OPEN), "rceil" to s("⌉", C_CLOSE), "vert" to s("|", C_ORD), "Vert" to s("‖", C_ORD), "|" to s("‖", C_ORD),
)
private val SPACES = mapOf(
    "," to 0.167f, ":" to 0.222f, ";" to 0.278f, "!" to -0.167f, " " to 0.25f, "quad" to 1f, "qquad" to 2f,
    "enspace" to 0.5f, "thinspace" to 0.167f,
)
private val FUNCS = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "log", "ln", "lg", "exp",
    "det", "dim", "ker", "gcd", "deg", "arg", "mod", "Pr", "hom",
)
private val LIMFUNCS = setOf("lim", "max", "min", "sup", "inf", "limsup", "liminf", "argmax", "argmin")
private val ACCENTS = setOf(
    "overline", "underline", "hat", "widehat", "bar", "vec", "tilde", "widetilde", "dot", "ddot", "overrightarrow",
    "overbrace", "underbrace", "boxed", "cancel",
)
private val BIGOPS = setOf("sum", "prod", "int", "iint", "oint", "bigcup", "bigcap")
private val BIGOP_GLYPH = mapOf("bigcup" to "∪", "bigcap" to "∩")
private val BB = mapOf('R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ", 'H' to "ℍ", 'E' to "𝔼")
private val ENVS = mapOf(
    "pmatrix" to Pair("(", ")"), "bmatrix" to Pair("[", "]"), "Bmatrix" to Pair("{", "}"), "vmatrix" to Pair("|", "|"),
    "Vmatrix" to Pair("‖", "‖"), "matrix" to Pair(".", "."), "smallmatrix" to Pair(".", "."), "cases" to Pair("{", "."),
    "array" to Pair(".", "."), "aligned" to Pair(".", "."), "align" to Pair(".", "."), "align*" to Pair(".", "."),
    "split" to Pair(".", "."), "gather" to Pair(".", "."), "gather*" to Pair(".", "."), "alignat" to Pair(".", "."),
    "eqnarray" to Pair(".", "."),
)
private val ALIGNED = setOf("aligned", "align", "align*", "split", "alignat", "eqnarray")

/** Plain unicode maths characters a model (or the plain-text rescue) may type directly. */
private val UNI: Map<Char, Pair<String, Int>> = mapOf(
    '×' to s("×", C_BIN), '÷' to s("÷", C_BIN), '±' to s("±", C_BIN), '∓' to s("∓", C_BIN), '·' to s("·", C_BIN),
    '≤' to s("≤", C_REL), '≥' to s("≥", C_REL), '≠' to s("≠", C_REL), '≈' to s("≈", C_REL), '→' to s("→", C_REL),
    '←' to s("←", C_REL), '⇒' to s("⇒", C_REL), '∈' to s("∈", C_REL), '−' to s("−", C_BIN), '∞' to s("∞", C_ORD),
    '°' to s("°", C_ORD), '∂' to s("∂", C_ORD), '∇' to s("∇", C_ORD), '∑' to s("∑", C_ORD), '′' to s("′", C_ORD),
)
private const val SUPER_DIGITS = "²³¹"

// ---- Parser -----------------------------------------------------------------------------------------------------------
private class Tok(val kind: Char, val v: String) // 'c' command, 'h' character, 's' space

private fun tokenize(src: String): List<Tok> {
    val out = ArrayList<Tok>()
    var i = 0
    val n = src.length
    while (i < n) {
        val c = src[i]
        when {
            c == '\\' -> {
                var j = i + 1
                if (j < n && src[j].isLetter()) {
                    while (j < n && src[j].isLetter()) j++
                    out.add(Tok('c', src.substring(i + 1, j)))
                    i = j
                } else if (j < n) {
                    out.add(Tok('c', src[j].toString()))
                    i = j + 1
                } else i = j
            }
            c.isWhitespace() -> { out.add(Tok('s', " ")); i++ }
            else -> { out.add(Tok('h', c.toString())); i++ }
        }
    }
    return out
}

private class MathParser(src: String) {
    private val t = tokenize(src)
    private var i = 0
    private var depth = 0

    fun peek(): Tok? {
        while (i < t.size && t[i].kind == 's') i++
        return if (i < t.size) t[i] else null
    }

    fun next(): Tok? { val p = peek(); if (p != null) i++; return p }
    fun atCh(v: String): Boolean { val p = peek(); return p != null && p.kind == 'h' && p.v == v }
    fun atCmd(v: String): Boolean { val p = peek(); return p != null && p.kind == 'c' && p.v == v }

    fun isStop(): Boolean {
        val p = peek() ?: return true
        if (p.kind == 'h' && (p.v == "}" || p.v == "&")) return true
        return p.kind == 'c' && (p.v == "right" || p.v == "end" || p.v == "\\")
    }

    fun seq(): MNode.Seq {
        if (++depth > 40) throw MathError("too deep")
        val items = ArrayList<MNode>()
        while (!isStop()) {
            val a = atomWithScripts()
            if (a != null) items.add(a)
        }
        depth--
        return MNode.Seq(autosize(items))
    }

    fun groupOrAtom(): MNode {
        val p = peek() ?: return MNode.Seq(emptyList())
        if (p.kind == 'h' && p.v == "{") {
            next()
            val s = seq()
            if (atCh("}")) next()
            return s
        }
        return atom(true) ?: MNode.Seq(emptyList())
    }

    fun atomWithScripts(): MNode? {
        val a = atom(false) ?: return null
        var sub: MNode? = null
        var sup: MNode? = null
        while (true) {
            val p = peek()
            if (p != null && p.kind == 'h' && p.v == "^" && sup == null) { next(); sup = groupOrAtom() }
            else if (p != null && p.kind == 'h' && p.v == "_" && sub == null) { next(); sub = groupOrAtom() }
            else if (p != null && p.kind == 'h' && p.v == "'" && sup == null) { next(); sup = MNode.Sym("′", C_ORD, F_RM) }
            else if (p != null && p.kind == 'h' && p.v.length == 1 && SUPER_DIGITS.indexOf(p.v[0]) >= 0 && sup == null) {
                next(); sup = MNode.Sym(when (p.v[0]) { '²' -> "2"; '³' -> "3"; else -> "1" }, C_ORD, F_RM)
            } else break
        }
        return if (sub != null || sup != null) MNode.Scripts(a, sub, sup) else a
    }

    /** The text inside {...}, spaces kept (for \text). */
    fun rawGroup(): String {
        if (!atCh("{")) return next()?.v ?: ""
        next()
        var d = 1
        val sb = StringBuilder()
        while (i < t.size) {
            val tk = t[i]
            i++
            if (tk.kind == 'h' && tk.v == "{") d++
            else if (tk.kind == 'h' && tk.v == "}") { d--; if (d == 0) break }
            if (tk.kind == 'c') { if (!tk.v[0].isLetter()) sb.append(tk.v) } else sb.append(tk.v)
        }
        return sb.toString()
    }

    fun delim(): String {
        val p = next() ?: return "."
        if (p.kind == 'h') return p.v
        return when (p.v) {
            "{", "lbrace" -> "{"
            "}", "rbrace" -> "}"
            "langle" -> "⟨"
            "rangle" -> "⟩"
            "lfloor" -> "⌊"
            "rfloor" -> "⌋"
            "lceil" -> "⌈"
            "rceil" -> "⌉"
            "vert", "|" , "Vert" -> if (p.v == "vert") "|" else "‖"
            else -> "."
        }
    }

    private fun digitAfter(idx: Int) = idx + 1 < t.size && t[idx + 1].kind == 'h' && t[idx + 1].v[0].isDigit()

    fun atom(single: Boolean): MNode? {
        val p = next() ?: return null
        if (p.kind == 'c') return command(p.v)
        val v = p.v
        val c = v[0]
        if (v == "{") {
            val s = seq()
            if (atCh("}")) next()
            return s
        }
        if (single && c.isDigit()) return MNode.Sym(v, C_ORD, F_RM)
        if (c.isDigit() || (c == '.' && i < t.size && t[i].kind == 'h' && t[i].v[0].isDigit())) {
            val sb = StringBuilder(v)
            while (true) {
                val q = if (i < t.size) t[i] else null
                if (q != null && q.kind == 'h' && (q.v[0].isDigit() || (q.v == "." && digitAfter(i)))) { sb.append(q.v); i++ } else break
            }
            return MNode.Sym(sb.toString(), C_ORD, F_RM)
        }
        if (c == '√') return MNode.Sqrt(groupOrAtom(), null)
        UNI[c]?.let { return MNode.Sym(it.first, it.second, F_RM) }
        if (c.isLetter()) {
            return if (c.isUpperCase() && c.code >= 0x391 && c.code <= 0x3A9) MNode.Sym(v, C_ORD, F_RM) else MNode.Sym(v, C_ORD, F_IT)
        }
        return when (c) {
            '+' -> MNode.Sym("+", C_BIN, F_RM)
            '-' -> MNode.Sym("−", C_BIN, F_RM)
            '*' -> MNode.Sym("∗", C_BIN, F_RM)
            '=', '<', '>' -> MNode.Sym(v, C_REL, F_RM)
            '(', '[' -> MNode.Sym(v, C_OPEN, F_RM)
            ')', ']' -> MNode.Sym(v, C_CLOSE, F_RM)
            ',', ';' -> MNode.Sym(v, C_PUNCT, F_RM)
            ':' -> MNode.Sym(":", C_REL, F_RM)
            '^', '_' -> { i--; MNode.Seq(emptyList()) } // a script with nothing in front of it
            '&', '}' -> null
            else -> MNode.Sym(v, C_ORD, F_RM)
        }
    }

    private fun withFont(n: MNode, f: Int): MNode = when (n) {
        is MNode.Sym -> MNode.Sym(n.text, n.cls, f)
        is MNode.Seq -> MNode.Seq(n.items.map { withFont(it, f) })
        is MNode.Scripts -> MNode.Scripts(withFont(n.base, f), n.sub?.let { withFont(it, f) }, n.sup?.let { withFont(it, f) })
        is MNode.Txt -> MNode.Txt(n.text, f)
        is MNode.Fn -> MNode.Txt(n.name, f)
        else -> n
    }

    fun command(n: String): MNode? {
        GREEK[n]?.let { return MNode.Sym(it, C_ORD, F_IT) }
        GREEK_UP[n]?.let { return MNode.Sym(it, C_ORD, F_RM) }
        SPACES[n]?.let { return MNode.Space(it) }
        SYMS[n]?.let { return MNode.Sym(it.first, it.second, F_RM) }
        if (n in FUNCS) return MNode.Fn(n, false)
        if (n in LIMFUNCS) return MNode.Fn(n, true)
        if (n in BIGOPS) return MNode.BigOp(n)
        when (n) {
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val a = groupOrAtom(); val b = groupOrAtom()
                return MNode.Frac(a, b, when (n) { "dfrac" -> ST_D; "tfrac" -> ST_T; else -> -1 }, true)
            }
            "binom" -> {
                val a = groupOrAtom(); val b = groupOrAtom()
                return MNode.Lr("(", ")", MNode.Frac(a, b, -1, false))
            }
            "sqrt" -> {
                var idx: MNode? = null
                if (atCh("[")) {
                    next()
                    val items = ArrayList<MNode>()
                    while (peek() != null && !atCh("]")) {
                        val a = atomWithScripts()
                        if (a != null) items.add(a)
                    }
                    if (atCh("]")) next()
                    idx = MNode.Seq(items)
                }
                return MNode.Sqrt(groupOrAtom(), idx)
            }
            "left" -> {
                val l = delim()
                val body = seq()
                var r = "."
                if (atCmd("right")) { next(); r = delim() }
                return MNode.Lr(l, r, body)
            }
            "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr" -> {
                val d = delim()
                if (d == ".") return null
                return MNode.Sym(d, if ("([{⟨⌊⌈".contains(d)) C_OPEN else C_CLOSE, F_RM)
            }
            "text", "textrm", "mbox", "textnormal", "textsf", "texttt" -> return MNode.Txt(rawGroup(), F_RM)
            "mathrm", "operatorname", "mathsf", "mathtt" -> return if (atCh("{")) MNode.Txt(rawGroup(), F_RM) else atom(true)
            "textit", "emph", "mathit" -> return MNode.Txt(rawGroup(), F_IT)
            "textbf", "mathbf", "boldsymbol", "bm", "bf" -> return MNode.Font(F_BF, groupOrAtom())
            "mathbb", "Bbb" -> return MNode.Txt(rawGroup().map { BB[it] ?: it.toString() }.joinToString(""), F_RM)
            "mathcal", "mathscr", "mathfrak", "mathbfit" -> return MNode.Font(F_IT, groupOrAtom())
            "begin" -> return env(rawGroup())
            "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle" -> {
                val k = when (n) { "displaystyle" -> ST_D; "textstyle" -> ST_T; "scriptstyle" -> ST_S; else -> ST_SS }
                return MNode.Style(k, seq())
            }
            "color" -> { val idx = tintIndex(rawGroup()); val rest = seq(); return if (idx == 0) rest else MNode.Tint(idx, rest) }
            "textcolor" -> { val idx = tintIndex(rawGroup()); val body = groupOrAtom(); return if (idx == 0) body else MNode.Tint(idx, body) }
            "limits", "nolimits", "nonumber", "notag", "label", "tag", "hline", "middle", "protect", "relax", "mathstrut",
            "strut", "vphantom", "hphantom", "phantom" -> { if (n == "label" || n == "tag") rawGroup(); return null }
            "not" -> return MNode.Sym("̸", C_ORD, F_RM)
        }
        if (n in ACCENTS) return MNode.Acc(n, groupOrAtom())
        return MNode.Txt(n, F_RM) // an unknown command shows its name rather than failing
    }

    fun env(name: String): MNode {
        val lr = ENVS[name]
        if (lr == null) {
            seq()
            if (atCmd("end")) { next(); rawGroup() }
            return MNode.Txt(name, F_RM)
        }
        val spec = if (name == "array" || name == "alignat") rawGroup() else null
        var rows = ArrayList<List<MNode>>()
        var row = ArrayList<MNode>()
        while (true) {
            val cell = seq()
            val p = peek()
            if (p != null && p.kind == 'h' && p.v == "&") { next(); row.add(cell); continue }
            row.add(cell)
            if (p != null && p.kind == 'c' && p.v == "\\") { next(); rows.add(row); row = ArrayList(); continue }
            if (p != null && p.kind == 'c' && p.v == "end") { next(); rawGroup() }
            else if (p == null || (p.kind == 'c' && p.v == "right") || (p.kind == 'h' && p.v == "}")) { if (p != null) next() }
            rows.add(row)
            break
        }
        val kept = rows.filter { !(it.size == 1 && (it[0] as MNode.Seq).items.isEmpty()) }
        return MNode.Mat(if (kept.isEmpty()) rows else kept, name, spec, lr.first, lr.second)
    }
}

// A plain ( ) or [ ] that wraps a fraction, root, sum or matrix grows like \left( \right), so it reads like the textbook.
private fun isTall(n: MNode): Boolean = when (n) {
    is MNode.Frac, is MNode.Sqrt, is MNode.BigOp, is MNode.Mat -> true
    is MNode.Scripts -> isTall(n.base)
    is MNode.Seq -> n.items.any { isTall(it) }
    is MNode.Font -> isTall(n.body)
    is MNode.Style -> isTall(n.body)
    is MNode.Tint -> isTall(n.body)
    is MNode.Lr -> isTall(n.body)
    else -> false
}

private fun bracketOf(n: MNode): MNode.Sym? = when {
    n is MNode.Scripts -> bracketOf(n.base)
    n is MNode.Sym && n.text.length == 1 && "()[]".contains(n.text[0]) && (n.cls == C_OPEN || n.cls == C_CLOSE) -> n
    else -> null
}

private fun autosize(items: List<MNode>): List<MNode> {
    val out = ArrayList<MNode>()
    var i = 0
    while (i < items.size) {
        val it0 = items[i]
        val d = if (it0 is MNode.Sym) bracketOf(it0)?.text else null
        if (d == "(" || d == "[") {
            val want = if (d == "(") ")" else "]"
            var depth = 0
            var j = -1
            for (k in i until items.size) {
                val b = bracketOf(items[k]) ?: continue
                if (b.text == d && items[k] is MNode.Sym) depth++
                else if (b.text == want) { depth--; if (depth == 0) { j = k; break } }
            }
            if (j > 0) {
                val inner = autosize(items.subList(i + 1, j))
                if (inner.any { isTall(it) }) {
                    val lr = MNode.Lr(d, want, MNode.Seq(inner))
                    val close = items[j]
                    out.add(if (close is MNode.Scripts) MNode.Scripts(lr, close.sub, close.sup) else lr)
                } else {
                    out.add(it0); out.addAll(inner); out.add(items[j])
                }
                i = j + 1
                continue
            }
        }
        out.add(it0)
        i++
    }
    return out
}

internal object MathTex {
    const val MAX_SOURCE = 900

    /** The tree for [src], or null when it is too long, too deeply nested or otherwise cannot be read. */
    fun parse(src: String): MNode? {
        if (src.length > MAX_SOURCE) return null
        return try {
            val p = MathParser(src)
            val rows = ArrayList<MNode>()
            var cur = ArrayList<MNode>()
            while (true) {
                cur.addAll(p.seq().items)
                val tok = p.peek() ?: break
                p.next() // a row break (\\), or a stray closer, which is dropped
                if (tok.kind == 'c' && tok.v == "\\") { rows.add(MNode.Seq(cur)); cur = ArrayList() }
            }
            if (cur.isNotEmpty() || rows.isEmpty()) rows.add(MNode.Seq(cur))
            if (rows.size == 1) rows[0]
            else MNode.Mat(rows.filter { (it as MNode.Seq).items.isNotEmpty() }.map { listOf(it) }, "gather", null, ".", ".")
        } catch (e: MathError) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }
}

// ---- Layout -----------------------------------------------------------------------------------------------------------
/** [spots]: leave the invisible tap targets (see [MOp.Spot]) in the boxes; only worth it where formulas can be tapped. */
internal class MathLayout(private val m: MathMetrics, private val base: Float, private val spots: Boolean = false) {

    private fun fs(st: Int) = base * SCALE[st]

    /** A finished box for [ast]: display (centred, big operators with limits) or inline (text style). */
    fun box(ast: MNode, display: Boolean): MBox = lay(ast, if (display) ST_D else ST_T)

    /** Inline maths that may wrap: pieces end after a binary operator or relation (the space after it stays inside). */
    fun pieces(ast: MNode): List<MBox> {
        if (ast !is MNode.Seq) return listOf(lay(ast, ST_T))
        return layPieces(ast.items, ST_T)
    }

    // --- glyph boxes and their estimated height
    private fun glyphHd(c: Char, font: Int): Pair<Float, Float> {
        val ch = c
        return when {
            ch.isDigit() -> Pair(0.68f, 0f)
            ch.isUpperCase() -> Pair(0.70f, 0f)
            "bdfhklt".indexOf(ch) >= 0 -> Pair(0.70f, if (ch == 'f' && font == F_IT) 0.2f else 0f)
            ch == 'i' -> Pair(0.68f, 0f)
            ch == 'j' -> Pair(0.68f, 0.22f)
            "gpqy".indexOf(ch) >= 0 -> Pair(0.46f, 0.22f)
            ch in 'a'..'z' -> Pair(0.46f, 0f)
            "βγζηξρφψχμϕ".indexOf(ch) >= 0 -> Pair(if ("βζξϕψ".indexOf(ch) >= 0) 0.70f else 0.46f, 0.22f)
            "δθλϑ".indexOf(ch) >= 0 -> Pair(0.70f, 0f)
            "αεϵικνοπστυωϖςϱ".indexOf(ch) >= 0 -> Pair(0.46f, 0f)
            "+−=±∓×÷<>≤≥≠≈≡∼≃≅∝→←↔⇒⇐⇔⟹⟺↦·∗∘⊕⊗∈∉∋⊂⊃⊆⊇≪≫∥∣∧∨∩∪•⋅".indexOf(ch) >= 0 -> Pair(0.58f, 0.08f)
            "()[]{}|‖⟨⟩⌊⌋⌈⌉".indexOf(ch) >= 0 -> Pair(0.75f, 0.25f)
            ch == '.' -> Pair(0.1f, 0f)
            ch == ',' || ch == ';' -> Pair(0.1f, 0.18f)
            ch == ':' || ch == '!' || ch == '?' -> Pair(0.70f, 0f)
            ch == '/' -> Pair(0.75f, 0.25f)
            "∞∂∇∀∃∅¬∠△°′".indexOf(ch) >= 0 -> Pair(0.70f, 0f)
            "…⋯".indexOf(ch) >= 0 -> Pair(0.15f, 0f)
            ch == ' ' -> Pair(0f, 0f)
            else -> Pair(0.70f, 0.1f)
        }
    }

    private fun glyphs(text: String, font: Int, st: Int): MBox {
        val sz = fs(st)
        var a = 0f
        var d = 0f
        for (c in text) { val h = glyphHd(c, font); a = max(a, h.first); d = max(d, h.second) }
        return MBox(m.width(text, font, sz), a * sz, d * sz, listOf(MOp.Text(0f, 0f, text, sz, font)))
    }

    private fun place(into: MutableList<MOp>, b: MBox, x: Float, dy: Float) {
        for (o in b.ops) into.add(o.shift(x, dy))
    }

    private fun shifted(ops: List<MOp>, dx: Float, dy: Float): List<MOp> = ops.map { it.shift(dx, dy) }

    // --- spacing
    private fun cls(n: MNode): Int = when (n) {
        is MNode.Sym -> n.cls
        is MNode.Fn -> C_OP
        is MNode.BigOp -> C_OP
        is MNode.Scripts -> cls(n.base)
        else -> C_ORD
    }

    private fun gap(a: Int, b: Int, st: Int): Float {
        if (st >= ST_S) return 0f
        if (a == C_OP && (b == C_ORD || b == C_OP)) return 0.167f
        if (a == C_ORD && b == C_OP) return 0.167f
        if (a == C_BIN || b == C_BIN) return 0.222f
        if (a == C_REL || b == C_REL) return 0.278f
        if (a == C_PUNCT) return 0.167f
        return 0f
    }

    private fun classesFor(items: List<MNode>): IntArray {
        val c = IntArray(items.size) { cls(items[it]) }
        for (i in c.indices) {
            if (c[i] == C_BIN) {
                val prev = if (i > 0) c[i - 1] else -1
                val nxt = if (i + 1 < c.size) c[i + 1] else -1
                if (prev == -1 || prev == C_BIN || prev == C_REL || prev == C_OPEN || prev == C_PUNCT || prev == C_OP ||
                    nxt == -1 || nxt == C_REL || nxt == C_CLOSE || nxt == C_PUNCT
                ) c[i] = C_ORD
            }
        }
        return c
    }

    private fun laySeq(items: List<MNode>, st: Int): MBox {
        val c = classesFor(items)
        val em = fs(st)
        var x = 0f
        var asc = 0f
        var desc = 0f
        val ops = ArrayList<MOp>()
        for (i in items.indices) {
            if (i > 0) x += gap(c[i - 1], c[i], st) * em
            val b = lay(items[i], st)
            place(ops, b, x, 0f)
            x += b.w
            asc = max(asc, b.asc); desc = max(desc, b.desc)
        }
        return MBox(x, asc, desc, ops)
    }

    private fun layPieces(items: List<MNode>, st: Int): List<MBox> {
        val c = classesFor(items)
        val em = fs(st)
        val out = ArrayList<MBox>()
        var ops = ArrayList<MOp>()
        var x = 0f
        var asc = 0f
        var desc = 0f
        for (i in items.indices) {
            if (i > 0 && !(c[i - 1] == C_BIN || c[i - 1] == C_REL)) x += gap(c[i - 1], c[i], st) * em
            val b = lay(items[i], st)
            place(ops, b, x, 0f)
            x += b.w
            asc = max(asc, b.asc); desc = max(desc, b.desc)
            if ((c[i] == C_BIN || c[i] == C_REL) && i + 1 < items.size) {
                x += gap(c[i], c[i + 1], st) * em
                out.add(MBox(x, asc, desc, ops))
                ops = ArrayList(); x = 0f; asc = 0f; desc = 0f
            }
        }
        out.add(MBox(x, asc, desc, ops))
        return out
    }

    // --- the dispatcher
    private fun spotted(n: MNode): Boolean = when (n) {
        is MNode.Frac, is MNode.Sqrt, is MNode.Lr, is MNode.Acc, is MNode.Mat, is MNode.BigOp, is MNode.Scripts -> true
        is MNode.Sym -> n.font == F_IT
        else -> false
    }

    private fun lay(n: MNode, st: Int): MBox {
        val b = layNode(n, st)
        if (!spots || !spotted(n)) return b
        val ops = ArrayList<MOp>(b.ops.size + 1)
        ops.addAll(b.ops)
        ops.add(MOp.Spot(0f, -b.asc, b.w, b.height, n))
        return MBox(b.w, b.asc, b.desc, ops)
    }

    private fun tintBox(b: MBox, idx: Int) = MBox(b.w, b.asc, b.desc, b.ops.map { if (it.tint == 0) it.tinted(idx) else it })

    private fun layNode(n: MNode, st: Int): MBox {
        val em = fs(st)
        return when (n) {
            is MNode.Seq -> laySeq(n.items, st)
            is MNode.Sym -> glyphs(n.text, n.font, st)
            is MNode.Txt -> glyphs(n.text, n.font, st)
            is MNode.Font -> lay(withFont(n.body, n.font), st)
            is MNode.Tint -> tintBox(lay(n.body, st), n.idx)
            is MNode.Space -> MBox(n.em * em, 0f, 0f, emptyList())
            is MNode.Fn -> glyphs(n.name, F_RM, st)
            is MNode.Style -> lay(n.body, n.style)
            is MNode.Frac -> layFrac(n, st)
            is MNode.Scripts -> layScripts(n, st)
            is MNode.Sqrt -> laySqrt(n, st)
            is MNode.Lr -> delimit(n.left, n.right, lay(n.body, st), st)
            is MNode.Acc -> layAcc(n, st)
            is MNode.BigOp -> layBigOp(n.name, st)
            is MNode.Mat -> layMat(n, st)
        }
    }

    private fun withFont(n: MNode, f: Int): MNode = when (n) {
        is MNode.Sym -> MNode.Sym(n.text, n.cls, f)
        is MNode.Seq -> MNode.Seq(n.items.map { withFont(it, f) })
        is MNode.Scripts -> MNode.Scripts(withFont(n.base, f), n.sub?.let { withFont(it, f) }, n.sup?.let { withFont(it, f) })
        is MNode.Txt -> MNode.Txt(n.text, f)
        is MNode.Fn -> MNode.Txt(n.name, f)
        else -> n
    }

    // --- fractions
    private fun layFrac(n: MNode.Frac, st: Int): MBox {
        val em = fs(st)
        val inner = if (n.force < 0) fracStyle(st) else if (n.force == ST_D) ST_T else ST_S
        val num = lay(n.num, inner)
        val den = lay(n.den, inner)
        val rt = max(RT * em, 1f)
        val disp = st == ST_D
        val u0 = if (disp) 0.677f else 0.394f
        val v0 = if (disp) 0.686f else 0.345f
        var clr = if (disp) 3 * rt else rt
        if (!n.bar) clr = rt * 1.5f
        val axis = AXIS * em
        val u = max(u0 * em, axis + rt / 2 + clr + num.desc)
        val v = max(v0 * em, den.asc - axis + rt / 2 + clr)
        val pad = 0.1f * em
        val w = max(num.w, den.w) + 2 * pad
        val ops = ArrayList<MOp>()
        place(ops, num, (w - num.w) / 2, -u)
        place(ops, den, (w - den.w) / 2, v)
        if (n.bar) ops.add(MOp.Rule(0f, -(axis + rt / 2), w, rt))
        return MBox(w, u + num.asc, v + den.desc, ops)
    }

    // --- scripts
    private fun layScripts(n: MNode.Scripts, st: Int): MBox {
        val base = n.base
        if (base is MNode.BigOp) return layBigOpScripts(base.name, n.sub, n.sup, st)
        if (base is MNode.Fn && base.limits && st == ST_D) return layLimits(lay(base, st), n.sub, n.sup, st)
        if (base is MNode.Acc && (base.kind == "overbrace" || base.kind == "underbrace")) {
            return layLimits(lay(base, st), if (base.kind == "underbrace") n.sub else null, if (base.kind == "overbrace") n.sup else null, st)
        }
        val simple = base is MNode.Sym || base is MNode.Txt || base is MNode.Fn
        return attach(lay(base, st), simple, n.sub, n.sup, st, 0f)
    }

    private fun attach(b: MBox, simple: Boolean, sub: MNode?, sup: MNode?, st: Int, subDx: Float): MBox {
        val em = fs(st)
        val sst = scriptStyle(st)
        val sem = fs(sst)
        val sb = sub?.let { lay(it, sst) }
        val up = sup?.let { lay(it, sst) }
        val xh = 0.45f * em
        val rt = max(RT * em, 1f)
        var u = if (simple) 0f else b.asc - 0.386f * sem
        var v = if (simple) 0f else b.desc + 0.05f * sem
        if (up != null && sb == null) {
            u = max(u, max(0.38f * em, up.desc + xh / 4))
        } else if (sb != null && up == null) {
            v = max(v, max(0.15f * em, sb.asc - 0.8f * xh))
        } else if (sb != null && up != null) {
            u = max(u, max(0.38f * em, up.desc + xh / 4))
            v = max(v, 0.247f * em)
            val gp = (u - up.desc) - (sb.asc - v)
            if (gp < 4 * rt) {
                v += 4 * rt - gp
                val psi = 0.8f * xh - (u - up.desc)
                if (psi > 0) { u += psi; v -= psi }
            }
        }
        val ops = ArrayList<MOp>(b.ops)
        var asc = b.asc
        var desc = b.desc
        if (up != null) { place(ops, up, b.w, -u); asc = max(asc, u + up.asc) }
        if (sb != null) { place(ops, sb, b.w + subDx, v); desc = max(desc, v + sb.desc) }
        val w = b.w + max(up?.w ?: 0f, if (sb != null) sb.w + subDx else 0f) + 0.03f * em
        return MBox(w, asc, desc, ops)
    }

    private fun layLimits(b: MBox, sub: MNode?, sup: MNode?, st: Int): MBox {
        val em = fs(st)
        val sst = scriptStyle(st)
        val sb = sub?.let { lay(it, sst) }
        val up = sup?.let { lay(it, sst) }
        val w = max(b.w, max(sb?.w ?: 0f, up?.w ?: 0f))
        val ops = ArrayList<MOp>()
        val g = 0.12f * em
        var asc = b.asc
        var desc = b.desc
        place(ops, b, (w - b.w) / 2, 0f)
        if (up != null) {
            place(ops, up, (w - up.w) / 2, -(b.asc + g + up.desc))
            asc = b.asc + g + up.desc + up.asc
        }
        if (sb != null) {
            place(ops, sb, (w - sb.w) / 2, b.desc + g + sb.asc)
            desc = b.desc + g + sb.asc + sb.desc
        }
        return MBox(w, asc, desc, ops)
    }

    // --- big operators, drawn as paths so they look the same on every phone
    private fun bigGlyph(name: String, st: Int): MBox {
        val em = fs(st)
        val disp = st == ST_D
        val axis = AXIS * em
        val ops = ArrayList<MOp>()
        if (name == "sum" || name == "prod") {
            val h = (if (disp) 1.45f else 1f) * em
            val w = (if (name == "sum") 0.95f else 0.9f) * em * (if (disp) 1f else 0.85f)
            val sw = max(0.06f * em, 1.2f)
            val top = -(axis + h / 2)
            val bot = -(axis - h / 2)
            if (name == "sum") {
                ops.add(MOp.Line(w, top, 0f, top, sw)); ops.add(MOp.Line(0f, top, 0.55f * w, -axis, sw))
                ops.add(MOp.Line(0.55f * w, -axis, 0f, bot, sw)); ops.add(MOp.Line(0f, bot, w, bot, sw))
                ops.add(MOp.Line(w, top, w, top + 0.14f * em, sw)); ops.add(MOp.Line(w, bot, w, bot - 0.14f * em, sw))
            } else {
                ops.add(MOp.Line(-0.02f * w, top, 1.02f * w, top, sw))
                ops.add(MOp.Line(0.12f * w, top, 0.12f * w, bot, sw)); ops.add(MOp.Line(0.88f * w, top, 0.88f * w, bot, sw))
            }
            return MBox(w + 0.05f * em, -top, bot, ops)
        }
        val h = (if (disp) 2.0f else 1.2f) * em
        val w = (if (disp) 0.32f else 0.26f) * h
        val sw = max(0.055f * em, 1.2f)
        val top = -(axis + h / 2)
        val bot = -(axis - h / 2)
        val hh = bot - top
        ops.add(MOp.Quad(0.62f * w, top + 0.16f * hh, 0.66f * w, top, 0.92f * w, top + 0.05f * hh, sw))
        ops.add(MOp.Line(0.62f * w, top + 0.16f * hh, 0.38f * w, bot - 0.16f * hh, sw))
        ops.add(MOp.Quad(0.38f * w, bot - 0.16f * hh, 0.34f * w, bot, 0.08f * w, bot - 0.05f * hh, sw))
        return MBox(w, -top, bot, ops)
    }

    private fun layBigOp(name: String, st: Int): MBox = when (name) {
        "sum", "prod" -> bigGlyph(name, st)
        "int", "iint", "oint" -> bigGlyph("int", st)
        else -> glyphs(BIGOP_GLYPH[name] ?: name, F_RM, st)
    }

    private fun layBigOpScripts(name: String, sub: MNode?, sup: MNode?, st: Int): MBox {
        val b = layBigOp(name, st)
        if ((name == "sum" || name == "prod" || name == "bigcup" || name == "bigcap") && st == ST_D) return layLimits(b, sub, sup, st)
        if (name == "int" || name == "iint" || name == "oint") {
            return attach(b, false, sub, sup, st, if (st == ST_D) -0.25f * fs(st) else -0.12f * fs(st))
        }
        return attach(b, false, sub, sup, st, 0f)
    }

    // --- square roots
    private fun laySqrt(n: MNode.Sqrt, st: Int): MBox {
        val em = fs(st)
        val body = lay(n.body, st)
        val rt = max(RT * em, 1f)
        val gp = (if (st == ST_D) 0.18f else 0.13f) * em
        val h = body.asc + gp + rt
        val dp = body.desc
        val sw = 0.62f * em
        val stroke = max(rt * 1.1f, 1.2f)
        val idx = n.index?.let { lay(it, ST_S) }
        val x0 = if (idx != null) max(0f, idx.w - 0.4f * sw) else 0f
        val top = -(h - rt / 2)
        val bot = dp
        val hh = bot - top
        val mid = bot - 0.42f * hh
        val ops = ArrayList<MOp>()
        val px = floatArrayOf(x0, x0 + 0.16f * sw, x0 + 0.45f * sw, x0 + sw)
        val py = floatArrayOf(mid + 0.05f * hh, mid - 0.03f * hh, bot, top)
        for (k in 0 until 3) ops.add(MOp.Line(px[k], py[k], px[k + 1], py[k + 1], stroke))
        val pad = 0.06f * em
        ops.add(MOp.Rule(x0 + sw, -h, body.w + pad, rt))
        place(ops, body, x0 + sw, 0f)
        if (idx != null) place(ops, idx, 0f, -(0.45f * (h + dp) - dp))
        return MBox(x0 + sw + body.w + pad, h, dp, ops)
    }

    // --- brackets that grow with what they wrap
    private fun delimit(l: String, r: String, body: MBox, st: Int): MBox {
        val em = fs(st)
        val axis = AXIS * em
        val hd = max(body.asc - axis, body.desc + axis)
        val h = max(2 * hd * 0.901f, 2 * hd - 0.5f * em)
        val ops = ArrayList<MOp>()
        var w = 0f
        val pad = if (h > 1.25f * em) 0.1f * em else 0f
        if (l != ".") {
            val b = drawDelim(l, h, st)
            place(ops, b, w, 0f)
            w += b.w + pad
        }
        place(ops, body, w, 0f)
        w += body.w
        if (r != ".") {
            w += pad
            val b = drawDelim(r, h, st)
            place(ops, b, w, 0f)
            w += b.w
        }
        return MBox(w, max(body.asc, axis + h / 2), max(body.desc, h / 2 - axis), ops)
    }

    private fun drawDelim(d: String, h: Float, st: Int): MBox {
        val em = fs(st)
        val axis = AXIS * em
        if (h <= 1.25f * em && d != "‖") {
            val g = glyphs(d, F_RM, st)
            if ("()[]{}|⟨⟩".contains(d)) {
                return MBox(g.w + 0.03f * em, max(g.asc, 0.75f * em), max(g.desc, 0.25f * em), shifted(g.ops, 0.015f * em, 0f))
            }
            return g
        }
        val top = -(axis + h / 2)
        val bot = -(axis - h / 2)
        val mid = (top + bot) / 2
        val sw = max(0.06f * em, 1.2f)
        var ops: List<MOp>
        var w: Float
        when (d) {
            "(" -> {
                w = 0.30f * em
                ops = shifted(listOf(MOp.Quad(w, top, -w, mid, w, bot, sw)), 0.03f * em, 0f)
                w += 0.06f * em
            }
            ")" -> {
                w = 0.30f * em
                ops = shifted(listOf(MOp.Quad(0f, top, 2 * w, mid, 0f, bot, sw)), 0.03f * em, 0f)
                w += 0.06f * em
            }
            "[", "]" -> {
                w = 0.34f * em
                val left = d == "["
                val xs = if (left) 0.10f * em else w - 0.10f * em
                val xe = if (left) w - 0.04f * em else 0.04f * em
                ops = listOf(MOp.Line(xe, top, xs, top, sw), MOp.Line(xs, top, xs, bot, sw), MOp.Line(xs, bot, xe, bot, sw))
            }
            "{", "}" -> {
                w = 0.45f * em
                val left = d == "{"
                val xFar = if (left) w - 0.05f * em else 0.05f * em
                val xMid = if (left) w / 2 + 0.02f * em else w / 2 - 0.02f * em
                val xTip = if (left) 0.04f * em else w - 0.04f * em
                val rr = 0.14f * em
                ops = listOf(
                    MOp.Quad(xFar, top, xMid, top, xMid, top + rr, sw), MOp.Line(xMid, top + rr, xMid, mid - rr, sw),
                    MOp.Quad(xMid, mid - rr, xMid, mid, xTip, mid, sw), MOp.Quad(xTip, mid, xMid, mid, xMid, mid + rr, sw),
                    MOp.Line(xMid, mid + rr, xMid, bot - rr, sw), MOp.Quad(xMid, bot - rr, xMid, bot, xFar, bot, sw),
                )
            }
            "|", "∣" -> { w = 0.2f * em; ops = listOf(MOp.Line(w / 2, top, w / 2, bot, sw)) }
            "‖" -> {
                w = 0.32f * em
                ops = listOf(MOp.Line(0.09f * em, top, 0.09f * em, bot, sw), MOp.Line(w - 0.09f * em, top, w - 0.09f * em, bot, sw))
            }
            "⟨", "⟩" -> {
                w = 0.34f * em
                val left = d == "⟨"
                val tip = if (left) 0.06f * em else w - 0.06f * em
                val far = if (left) w - 0.06f * em else 0.06f * em
                ops = listOf(MOp.Line(far, top, tip, mid, sw), MOp.Line(tip, mid, far, bot, sw))
            }
            "⌊", "⌋", "⌈", "⌉" -> {
                w = 0.32f * em
                val xl = 0.08f * em
                val xr = w - 0.08f * em
                val leftSide = d == "⌊" || d == "⌈"
                val xv = if (leftSide) xl else xr
                val xo = if (leftSide) xr else xl
                ops = if (d == "⌊" || d == "⌋") listOf(MOp.Line(xv, top, xv, bot, sw), MOp.Line(xv, bot, xo, bot, sw))
                else listOf(MOp.Line(xv, bot, xv, top, sw), MOp.Line(xv, top, xo, top, sw))
            }
            else -> return glyphs(d, F_RM, st)
        }
        return MBox(w, -top, bot, ops)
    }

    // --- accents, bars, braces
    private fun layAcc(n: MNode.Acc, st: Int): MBox {
        val em = fs(st)
        val b = lay(n.body, st)
        val rt = max(RT * em, 1f)
        var ops: MutableList<MOp> = ArrayList(b.ops)
        var asc = b.asc
        var desc = b.desc
        val cx = b.w / 2
        val sw = max(0.05f * em, 1f)
        val top0 = max(b.asc, 0.46f * em)
        when (n.kind) {
            "overline" -> { val y = -(b.asc + 0.12f * em); ops.add(MOp.Rule(0f, y - rt, b.w, rt)); asc = b.asc + 0.12f * em + rt }
            "underline" -> { val y = b.desc + 0.12f * em; ops.add(MOp.Rule(0f, y, b.w, rt)); desc = b.desc + 0.12f * em + rt }
            "hat", "widehat" -> {
                val y = -(top0 + 0.06f * em)
                ops.add(MOp.Line(cx - 0.2f * em, y, cx, y - 0.17f * em, sw)); ops.add(MOp.Line(cx, y - 0.17f * em, cx + 0.2f * em, y, sw))
                asc = -y + 0.17f * em + sw
            }
            "bar" -> { val y = -(top0 + 0.10f * em); ops.add(MOp.Rule(cx - 0.2f * em, y - rt, 0.4f * em, rt)); asc = -y + rt }
            "vec", "overrightarrow" -> {
                val y = -(top0 + 0.12f * em)
                val x1 = if (n.kind == "vec") cx - 0.22f * em else 0f
                val x2 = if (n.kind == "vec") cx + 0.22f * em else b.w
                ops.add(MOp.Line(x1, y, x2, y, sw)); ops.add(MOp.Line(x2 - 0.08f * em, y - 0.06f * em, x2, y, sw))
                ops.add(MOp.Line(x2 - 0.08f * em, y + 0.06f * em, x2, y, sw))
                asc = -y + 0.08f * em
            }
            "tilde", "widetilde" -> {
                val y = -(top0 + 0.1f * em)
                ops.add(MOp.Quad(cx - 0.22f * em, y, cx - 0.11f * em, y - 0.16f * em, cx, y - 0.04f * em, sw))
                ops.add(MOp.Quad(cx, y - 0.04f * em, cx + 0.11f * em, y + 0.08f * em, cx + 0.22f * em, y - 0.08f * em, sw))
                asc = -y + 0.2f * em
            }
            "dot", "ddot" -> {
                val y = -(top0 + 0.12f * em)
                val r = max(0.035f * em, 0.8f)
                if (n.kind == "dot") ops.add(MOp.Dot(cx, y, r)) else { ops.add(MOp.Dot(cx - 0.09f * em, y, r)); ops.add(MOp.Dot(cx + 0.09f * em, y, r)) }
                asc = -y + r
            }
            "overbrace", "underbrace" -> {
                val up = n.kind == "overbrace"
                val y = if (up) -(b.asc + 0.1f * em) else b.desc + 0.1f * em
                val dy = if (up) -0.12f * em else 0.12f * em
                val mm = b.w / 2
                ops.add(MOp.Quad(0f, y + dy, 0f, y, 0.15f * em, y, sw)); ops.add(MOp.Line(0.15f * em, y, mm - 0.15f * em, y, sw))
                ops.add(MOp.Quad(mm - 0.15f * em, y, mm, y, mm, y - dy, sw)); ops.add(MOp.Quad(mm, y - dy, mm, y, mm + 0.15f * em, y, sw))
                ops.add(MOp.Line(mm + 0.15f * em, y, b.w - 0.15f * em, y, sw)); ops.add(MOp.Quad(b.w - 0.15f * em, y, b.w, y, b.w, y + dy, sw))
                if (up) asc = -y + 0.14f * em else desc = y + 0.14f * em
            }
            "boxed" -> {
                val p = 0.22f * em
                val x1 = b.w + 2 * p
                val y0 = -(b.asc + p)
                val y1 = b.desc + p
                val moved = ArrayList<MOp>(shifted(ops, p, 0f))
                moved.add(MOp.Line(0f, y0, x1, y0, sw)); moved.add(MOp.Line(0f, y1, x1, y1, sw))
                moved.add(MOp.Line(0f, y0, 0f, y1, sw)); moved.add(MOp.Line(x1, y0, x1, y1, sw))
                return MBox(x1, b.asc + p, b.desc + p, moved)
            }
            "cancel" -> ops.add(MOp.Line(0f, b.desc, b.w, -b.asc, sw))
        }
        return MBox(b.w, asc, desc, ops)
    }

    // --- matrices, cases and aligned blocks
    private fun layMat(n: MNode.Mat, st: Int): MBox {
        val name = n.env
        val aligned = name in ALIGNED
        val cellSt = if (aligned || name == "gather" || name == "gather*") ST_D else if (name == "smallmatrix") ST_S else ST_T
        val em = fs(cellSt)
        val rows = if (aligned) {
            n.rows.map { row -> row.mapIndexed { j, c -> if (j % 2 == 0) c else MNode.Seq(listOf<MNode>(MNode.Seq(emptyList())) + (c as MNode.Seq).items) } }
        } else n.rows
        val grid = rows.map { row -> row.map { lay(it, cellSt) } }
        val ncol = grid.maxOfOrNull { it.size } ?: 0
        val colw = FloatArray(ncol)
        for (r in grid) for (j in r.indices) colw[j] = max(colw[j], r[j].w)
        val align: CharArray
        val gaps = FloatArray(ncol)
        when {
            aligned -> {
                align = CharArray(ncol) { if (it % 2 == 0) 'r' else 'l' }
                for (j in 0 until ncol) gaps[j] = if (j % 2 == 0) 0f else 2f * em
            }
            name == "cases" -> { align = CharArray(ncol) { 'l' }; gaps.fill(1f * em) }
            name == "gather" || name == "gather*" -> { align = CharArray(ncol) { 'c' }; gaps.fill(1f * em) }
            else -> {
                val given = (n.spec ?: "").filter { it == 'l' || it == 'c' || it == 'r' }
                align = CharArray(ncol) { if (it < given.length) given[it] else 'c' }
                gaps.fill(1f * em)
            }
        }
        val rowGap = (if (aligned || name == "gather") 0.45f else 0.3f) * em
        val strutA = 0.7f * em
        val strutD = 0.25f * em
        val heights = grid.map { r ->
            Pair(max(strutA, r.maxOfOrNull { it.asc } ?: 0f), max(strutD, r.maxOfOrNull { it.desc } ?: 0f))
        }
        val totalH = heights.sumOf { (it.first + it.second).toDouble() }.toFloat() + rowGap * max(0, grid.size - 1)
        val axis = AXIS * em
        var y = -(totalH / 2 + axis)
        val ops = ArrayList<MOp>()
        for ((ri, r) in grid.withIndex()) {
            val (a, d) = heights[ri]
            var x = 0f
            for ((j, b) in r.withIndex()) {
                val cw = colw[j]
                val dx = when (align[j]) { 'l' -> 0f; 'r' -> cw - b.w; else -> (cw - b.w) / 2 }
                place(ops, b, x + dx, y + a)
                x += cw + (if (j < ncol - 1) gaps[j] else 0f)
            }
            y += a + d + rowGap
        }
        var w = colw.sum()
        for (j in 0 until max(0, ncol - 1)) w += gaps[j]
        val box = MBox(w, totalH / 2 + axis, totalH / 2 - axis, ops)
        return if (n.left != "." || n.right != ".") delimit(n.left, n.right, box, cellSt) else box
    }
}

// ---- A tapped piece back to LaTeX -----------------------------------------------------------------------------------------
internal object MathSrc {
    private val REV: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for ((k, v) in GREEK) m.putIfAbsent(v, "\\$k ")
        for ((k, v) in GREEK_UP) m.putIfAbsent(v, "\\$k ")
        for ((k, v) in SYMS) if (k[0].isLetter()) m.putIfAbsent(v.first, "\\$k ")
        m
    }
    private val TINTS = listOf("", "lime", "flare", "sky", "amber", "mist")

    private fun delim(d: String): String = when (d) {
        "{" -> "\\{"; "}" -> "\\}"; "⟨" -> "\\langle "; "⟩" -> "\\rangle "; "⌊" -> "\\lfloor "; "⌋" -> "\\rfloor "
        "⌈" -> "\\lceil "; "⌉" -> "\\rceil "; "‖" -> "\\| "; else -> d
    }

    /** LaTeX for [n] that [MathTex.parse] reads back to the same thing (used to ask the AI about a tapped piece). */
    fun tex(n: MNode): String = when (n) {
        is MNode.Seq -> n.items.joinToString("") { tex(it) }
        is MNode.Sym -> REV[n.text] ?: (if ("{}%$&#_".indexOf(n.text[0]) >= 0) "\\" + n.text else if (n.text == "−") "-" else n.text)
        is MNode.Txt -> if (n.font == F_IT) "\\textit{" + n.text + "}" else "\\text{" + n.text + "}"
        is MNode.Fn -> "\\" + n.name + " "
        is MNode.Space -> "\\, "
        is MNode.Frac -> (if (n.bar) "\\frac{" else "\\binom{") + tex(n.num) + "}{" + tex(n.den) + "}"
        is MNode.Scripts -> {
            val base = n.base
            val b = if (base is MNode.Seq && base.items.size > 1) "{" + tex(base) + "}" else tex(base)
            b + (if (n.sub != null) "_{" + tex(n.sub) + "}" else "") + (if (n.sup != null) "^{" + tex(n.sup) + "}" else "")
        }
        is MNode.Sqrt -> (if (n.index != null) "\\sqrt[" + tex(n.index) + "]{" else "\\sqrt{") + tex(n.body) + "}"
        is MNode.Lr -> {
            val body = n.body
            if (n.left == "(" && n.right == ")" && body is MNode.Frac && !body.bar) tex(body)
            else "\\left" + delim(n.left) + tex(body) + "\\right" + delim(n.right)
        }
        is MNode.Acc -> "\\" + n.kind + "{" + tex(n.body) + "}"
        is MNode.BigOp -> "\\" + n.name + " "
        is MNode.Style -> tex(n.body)
        is MNode.Font -> if (n.font == F_BF) "\\mathbf{" + tex(n.body) + "}" else tex(n.body)
        is MNode.Tint -> "\\textcolor{" + TINTS[n.idx.coerceIn(0, 5)] + "}{" + tex(n.body) + "}"
        is MNode.Mat -> {
            val rows = n.rows.joinToString(" \\\\ ") { r -> r.joinToString(" & ") { tex(it) } }
            "\\begin{" + n.env + "}" + (if (n.spec != null) "{" + n.spec + "}" else "") + rows + "\\end{" + n.env + "}"
        }
    }
}
