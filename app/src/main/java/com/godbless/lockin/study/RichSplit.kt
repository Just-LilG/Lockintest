package com.godbless.lockin.study

/*
 * Splits an AI answer into what the screen draws: paragraphs and list items made of text (bold, italic, code) and inline
 * maths, and display equations on their own. Also rescues maths the model wrote as plain text ("x^2", "sqrt(b^2-4ac)",
 * "(b/a)") by turning it into LaTeX, so older saved answers and slips still come out typeset.
 *
 * Pure Kotlin, no Android: see RichSplitTest. The server asks the model for $...$ and $$...$$ (supabase/functions/ai).
 */

internal sealed class Span {
    class Txt(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false) : Span()
    class Math(val tex: String) : Span()
}

internal sealed class Block {
    /** One line of an answer. [marker] is "•" or "1." for a list item; [gapBefore] when a blank line came before it. */
    class Para(val spans: List<Span>, val marker: String?, val gapBefore: Boolean) : Block()
    class Display(val tex: String) : Block()
}

internal object RichSplit {
    private const val DOLLAR = '$'
    private val GREEK_WORDS = listOf("alpha", "beta", "gamma", "delta", "theta", "lambda", "mu", "sigma", "omega", "phi", "pi", "tau", "rho", "epsilon")
    private val GW = "(?:" + GREEK_WORDS.joinToString("|") + ")"

    // ---- delimiters
    private val DISPLAY_BRACKETS = Regex("""\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_PARENS = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL)
    private val DISPLAY_DOLLARS = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    private val BULLET = Regex("""^\s*(?:[-*•]|\d{1,2}[.)])\s+""")

    fun normalize(t: String): String {
        var s = DISPLAY_BRACKETS.replace(t) { "$DOLLAR$DOLLAR" + it.groupValues[1] + "$DOLLAR$DOLLAR" }
        s = INLINE_PARENS.replace(s) { DOLLAR.toString() + it.groupValues[1] + DOLLAR }
        return s
    }

    /**
     * [i] is an opening dollar. Pandoc's rule: the opener is followed by a non-space, the closer is preceded by a non-space
     * and not followed by a digit, so "$5 and $6" stays text. Returns the closer's index, or -1.
     */
    fun findInlineClose(s: String, i: Int): Int {
        val n = s.length
        if (i + 1 >= n || s[i + 1].isWhitespace() || s[i + 1] == DOLLAR) return -1
        var j = i + 1
        while (j < n) {
            val c = s[j]
            if (c == '\\' && j + 1 < n) { j += 2; continue }
            if (c == '\n') return -1
            if (c == DOLLAR) {
                if (s[j - 1].isWhitespace()) return -1
                if (j + 1 < n && s[j + 1].isDigit()) return -1
                return j
            }
            j++
        }
        return -1
    }

    // ---- plain-text maths
    private fun balancedEnd(s: String, i: Int): Int {
        var d = 0
        for (j in i until s.length) {
            if (s[j] == '(') d++
            else if (s[j] == ')') { d--; if (d == 0) return j + 1 }
        }
        return -1
    }

    private fun balancedStart(s: String, i: Int): Int {
        var d = 0
        for (j in i downTo 0) {
            if (s[j] == ')') d++
            else if (s[j] == '(') { d--; if (d == 0) return j }
        }
        return -1
    }

    private const val OPS = """[+\-−±∓=*/<>≤≥≠≈×·]"""
    private const val EXP = """(?:\^(?:\{[^{}]*\}|-?\d+|-?[A-Za-z]))?"""
    private val TERM = """(?:\d+(?:\.\d+)?[A-Za-z]?|$GW(?![A-Za-z])|[A-Za-z](?![A-Za-z])|[A-Za-z]\^[^\s]|\([^()]*\)[A-Za-z0-9]*)"""
    private val POW = Regex(
        """(?:\d+(?:\.\d+)?[A-Za-z]?|[A-Za-z])\^(?:\{[^{}]*\}|\([^()]*\)|-?\d+(?:\.\d+)?|-?[A-Za-z])(?![A-Za-z0-9])""" +
            """|\)\^(?:\{[^{}]*\}|-?\d+|-?[A-Za-z])(?![A-Za-z0-9])""",
    )
    private val SUB = Regex("""(?<![A-Za-z0-9_])[A-Za-z]_(?:\{[^{}]*\}|\d+|[A-Za-z])(?![A-Za-z0-9_])""")
    private val SQRT = Regex("""sqrt\(""")
    private val R_EXT = Regex("^\\s*" + OPS + "\\s*" + TERM + EXP)
    private val L_EXT = Regex("(?<![A-Za-z0-9])" + TERM + EXP + "\\s*" + OPS + "\\s*$")
    private val L_JUX = Regex("(?<![A-Za-z0-9])(?:\\d+(?:\\.\\d+)?\\s?)?" + GW + "\\s?$")
    private val COEFF_TAIL = Regex("(?:\\d+(?:\\.\\d+)?)?[A-Za-z]?$")

    private fun anchors(s: String): List<IntArray> {
        val spans = ArrayList<IntArray>()
        for (m in POW.findAll(s)) spans.add(intArrayOf(m.range.first, m.range.last + 1))
        for (m in SUB.findAll(s)) spans.add(intArrayOf(m.range.first, m.range.last + 1))
        for (m in SQRT.findAll(s)) {
            val e = balancedEnd(s, m.range.last)
            if (e > 0) spans.add(intArrayOf(m.range.first, e))
        }
        spans.sortWith(compareBy({ it[0] }, { it[1] }))
        return spans
    }

    /** Pulls a coefficient or function name stuck to a "(" into the span: the "a" of a(x+1). */
    private fun withCoefficient(s: String, a: Int): Int {
        val m = COEFF_TAIL.find(s.substring(0, a)) ?: return a
        val len = m.value.length
        if (len == 0) return a
        if (a - len > 0 && s[a - len - 1].isLetter()) return a
        return a - len
    }

    private fun grow(s: String, a0: Int, b0: Int): IntArray {
        var a = a0
        var b = b0
        repeat(6) {
            val pa = a
            val pb = b
            // balance parentheses
            val opens = ArrayList<Int>()
            var j = a
            while (j < b) {
                if (s[j] == '(') opens.add(j)
                else if (s[j] == ')') {
                    if (opens.isNotEmpty()) opens.removeAt(opens.size - 1)
                    else {
                        val k = balancedStart(s, j)
                        if (k in 0 until a) a = withCoefficient(s, k)
                    }
                }
                j++
            }
            if (opens.isNotEmpty()) {
                val e = balancedEnd(s, opens[0])
                if (e > 0) b = maxOf(b, e)
            }
            // the span sits inside a group that continues past it:  (-b ± sqrt(…)) / (2a)
            var k = b
            while (k < s.length && s[k] == ' ') k++
            if (k < s.length && s[k] == ')') {
                val o = balancedStart(s, k)
                if (o in 0 until a) { a = withCoefficient(s, o); b = k + 1 }
            }
            R_EXT.find(s.substring(b))?.let { b += it.value.length }
            L_JUX.find(s.substring(0, a))?.let { if (it.value.isNotEmpty()) a = it.range.first }
            L_EXT.find(s.substring(0, a))?.let { a = it.range.first }
            if (a == pa && b == pb) return intArrayOf(a, b)
        }
        return intArrayOf(a, b)
    }

    private val FRAC_PAREN = Regex("""\(([^()/]+?)\s*/\s*([^()/]+?)\)""")
    private val FRAC_GROUPS = Regex("""\(([^()]*)\)\s*/\s*\(([^()]*)\)""")
    private val FRAC_BARE = Regex("""(?<![A-Za-z0-9^_{}\\])(\d+|[A-Za-z])\s*/\s*(\d+|[A-Za-z])(?![A-Za-z0-9(])""")
    private val POW_PAREN = Regex("""\^\(([^()]*)\)""")
    private val SUB_PAREN = Regex("""_\(([^()]*)\)""")
    private val GREEK_RE = Regex("\\b(" + GREEK_WORDS.joinToString("|") + ")\\b")

    /** Plain-text maths ("x^2 + (b/a)x") to LaTeX. */
    fun toTex(span: String): String {
        var t = span
        while (true) {
            val m = SQRT.find(t) ?: break
            val e = balancedEnd(t, m.range.last)
            if (e < 0) break
            t = t.substring(0, m.range.first) + "\\sqrt{" + t.substring(m.range.last + 1, e - 1) + "}" + t.substring(e)
        }
        t = POW_PAREN.replace(t) { "^{" + it.groupValues[1] + "}" }
        t = SUB_PAREN.replace(t) { "_{" + it.groupValues[1] + "}" }
        t = FRAC_PAREN.replace(t) { "\\frac{" + it.groupValues[1].trim() + "}{" + it.groupValues[2].trim() + "}" }
        t = FRAC_GROUPS.replace(t) { "\\frac{" + it.groupValues[1] + "}{" + it.groupValues[2] + "}" }
        t = FRAC_BARE.replace(t) { "\\frac{" + it.groupValues[1] + "}{" + it.groupValues[2] + "}" }
        t = t.replace("<=", "\\le ").replace(">=", "\\ge ").replace("!=", "\\ne ").replace("->", "\\to ").replace("=>", "\\Rightarrow ")
        t = t.replace("*", "\\cdot ")
        t = GREEK_RE.replace(t) { "\\" + it.groupValues[1] }
        return t.replace("²", "^2").replace("³", "^3")
    }

    /** Plain text -> pieces: (false, text) and (true, tex). Only text with ^, _x or sqrt( in it is looked at. */
    fun upgrade(s: String): List<Pair<Boolean, String>> {
        if (!s.contains('^') && !s.contains('_') && !s.contains("sqrt(")) return listOf(Pair(false, s))
        val spans = anchors(s)
        val out = ArrayList<Pair<Boolean, String>>()
        var pos = 0
        var i = 0
        while (i < spans.size) {
            val g = grow(s, spans[i][0], spans[i][1])
            var a = g[0]
            var b = g[1]
            a = maxOf(a, pos)
            var j = i + 1
            while (j < spans.size && spans[j][0] <= b) { // overlapping anchors share one span
                val g2 = grow(s, spans[j][0], spans[j][1])
                b = maxOf(b, spans[j][1], g2[1])
                j++
            }
            if (b <= a) { i = j; continue }
            if (a > pos) out.add(Pair(false, s.substring(pos, a)))
            out.add(Pair(true, toTex(s.substring(a, b).trim())))
            pos = b
            i = j
        }
        if (pos < s.length) out.add(Pair(false, s.substring(pos)))
        return out
    }

    /** A string that is an equation rather than a sentence (the "you selected" text, a card's answer). */
    fun looksMath(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 200) return false
        if (t.indexOf(DOLLAR) >= 0 || t.indexOf('\\') >= 0) return true
        val words = Regex("[A-Za-z]{4,}").findAll(t).map { it.value }.toList()
        val known = words.filter { it.lowercase() in GREEK_WORDS || it.lowercase() in setOf("sqrt", "sin", "cos", "tan", "log") }
        if (words.size - known.size > 0) return false
        val hasOp = Regex("[=+\\-*/^<>]").containsMatchIn(t)
        val hasSymbol = hasOp || t.contains("sqrt") || t.any { it.isDigit() } || t.contains('_')
        return hasSymbol && t.any { it.isLetterOrDigit() } && (!t.contains(' ') || hasOp)
    }

    /** The LaTeX for a whole string already known to be maths ([looksMath]). */
    fun forceTex(text: String): String {
        val t = text.trim()
        if (t.indexOf('\\') >= 0 || t.indexOf(DOLLAR) >= 0) return t.trim(DOLLAR)
        return toTex(t)
    }

    // ---- inline and blocks
    fun inline(line: String): List<Span> {
        val out = ArrayList<Span>()
        val buf = StringBuilder()
        var bold = false
        var ital = false
        fun flush() {
            if (buf.isEmpty()) return
            val s = buf.toString()
            buf.setLength(0)
            for ((isMath, piece) in upgrade(s)) out.add(if (isMath) Span.Math(piece) else Span.Txt(piece, bold, ital))
        }
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (c == '\\' && i + 1 < n && "$DOLLAR*`\\".indexOf(line[i + 1]) >= 0) { buf.append(line[i + 1]); i += 2; continue }
            if (c == DOLLAR) {
                val j = findInlineClose(line, i)
                if (j > 0) { flush(); out.add(Span.Math(line.substring(i + 1, j))); i = j + 1; continue }
            }
            if (c == '`') {
                val j = line.indexOf('`', i + 1)
                if (j > 0) { flush(); out.add(Span.Txt(line.substring(i + 1, j), code = true)); i = j + 1; continue }
            }
            if (line.startsWith("**", i)) { flush(); bold = !bold; i += 2; continue }
            if (c == '*') {
                val nextNonSpace = i + 1 < n && !line[i + 1].isWhitespace()
                val prevAlnum = i > 0 && line[i - 1].isLetterOrDigit()
                if ((nextNonSpace || ital) && !(prevAlnum && !ital) && (ital || line.indexOf('*', i + 1) > 0)) {
                    flush(); ital = !ital; i++; continue
                }
            }
            buf.append(c)
            i++
        }
        flush()
        return out
    }

    private fun lines(text: String): List<Block> {
        val out = ArrayList<Block>()
        var gap = false
        for (raw in text.split('\n')) {
            if (raw.isBlank()) { gap = true; continue }
            val m = BULLET.find(raw)
            var marker: String? = null
            val body: String
            if (m != null) {
                val tok = m.value.trim()
                marker = if (tok == "-" || tok == "*" || tok == "•") "•" else tok
                body = raw.substring(m.value.length)
            } else body = raw.trim()
            out.add(Block.Para(inline(body), marker, gap && out.isNotEmpty()))
            gap = false
        }
        return out
    }

    fun blocks(text: String): List<Block> {
        val t = normalize(text.replace("\r", ""))
        val out = ArrayList<Block>()
        var pos = 0
        for (m in DISPLAY_DOLLARS.findAll(t)) {
            out.addAll(lines(t.substring(pos, m.range.first)))
            out.add(Block.Display(m.groupValues[1].trim()))
            pos = m.range.last + 1
        }
        out.addAll(lines(t.substring(pos)))
        return out
    }

    /** True when [text] needs more than a plain Text: a formula, a display equation, a list or bold. */
    fun isRich(blocks: List<Block>): Boolean = blocks.any {
        it is Block.Display || (it is Block.Para && (it.marker != null || it.spans.any { s -> s is Span.Math }))
    }
}
