package com.godbless.lockin.study

/*
 * Splits an AI answer into what the screen draws: paragraphs and list items made of text (bold, italic, code) and inline
 * maths, and display equations on their own. Also rescues maths the model wrote as plain text ("x^2", "sqrt(b^2-4ac)",
 * "(b/a)") by turning it into LaTeX, so older saved answers and slips still come out typeset.
 *
 * Pure Kotlin, no Android: see RichSplitTest. The server asks the model for $...$ and $$...$$ (supabase/functions/ai).
 */

internal sealed class Span {
    /** [color] is a [tintIndex] (0 = the normal text colour); [mark] is the highlighter. */
    class Txt(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val color: Int = 0,
        val mark: Boolean = false,
    ) : Span()
    class Math(val tex: String, val color: Int = 0) : Span()
}

internal sealed class Block {
    /** One line of an answer. [marker] is "•" or "1." for a list item; [gapBefore] when a blank line came before it. */
    class Para(val spans: List<Span>, val marker: String?, val gapBefore: Boolean) : Block()
    class Display(val tex: String) : Block()
    class Heading(val level: Int, val spans: List<Span>) : Block()
    /** [aligns] has one of 'l', 'c', 'r' per column; every row has exactly as many cells as the header. */
    class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>, val aligns: List<Char>) : Block()
    /** [kind]: tip, warning, remember, example, note, careful or key idea. */
    class Callout(val kind: String, val body: List<Block>) : Block()
    class Quote(val body: List<Block>) : Block()
    class Code(val text: String, val lang: String) : Block()
    object Rule : Block()
    class Plot(val spec: PlotSpec) : Block()
    class Diagram(val spec: DiagramSpec) : Block()
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
        val words = FOUR_LETTERS.findAll(t).map { it.value }.toList()
        val known = words.filter { it.lowercase() in GREEK_WORDS || it.lowercase() in setOf("sqrt", "sin", "cos", "tan", "log") }
        if (words.size - known.size > 0) return false
        val hasOp = OPERATOR.containsMatchIn(t)
        val hasSymbol = hasOp || t.contains("sqrt") || t.any { it.isDigit() } || t.contains('_')
        return hasSymbol && t.any { it.isLetterOrDigit() } && (!t.contains(' ') || hasOp)
    }

    /** The LaTeX for a whole string already known to be maths ([looksMath]). */
    fun forceTex(text: String): String {
        val t = text.trim()
        if (t.indexOf('\\') >= 0 || t.indexOf(DOLLAR) >= 0) return t.trim(DOLLAR)
        return toTex(t)
    }

    // ---- inline
    private val COLOR_NAMES = listOf("lime", "flare", "sky", "amber", "mist", "green", "red", "blue", "orange", "yellow", "gray", "grey")

    fun inline(line: String): List<Span> {
        val out = ArrayList<Span>()
        val buf = StringBuilder()
        var bold = false
        var ital = false
        var mark = false
        val colors = ArrayList<Int>()
        fun color() = if (colors.isEmpty()) 0 else colors[colors.size - 1]
        fun flush() {
            if (buf.isEmpty()) return
            val s = buf.toString()
            buf.setLength(0)
            for ((isMath, piece) in upgrade(s)) out.add(if (isMath) Span.Math(piece, color()) else Span.Txt(piece, bold, ital, false, color(), mark))
        }
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (c == '\\' && i + 1 < n && "$DOLLAR*`\\{}=".indexOf(line[i + 1]) >= 0) { buf.append(line[i + 1]); i += 2; continue }
            if (c == DOLLAR) {
                val j = findInlineClose(line, i)
                if (j > 0) { flush(); out.add(Span.Math(line.substring(i + 1, j), color())); i = j + 1; continue }
            }
            if (c == '`') {
                val j = line.indexOf('`', i + 1)
                if (j > 0) { flush(); out.add(Span.Txt(line.substring(i + 1, j), code = true)); i = j + 1; continue }
            }
            if (c == '{') {
                val name = COLOR_NAMES.firstOrNull { line.startsWith("{" + it + "|", i) }
                if (name != null) { flush(); colors.add(tintIndex(name)); i += name.length + 2; continue }
            }
            if (c == '}' && colors.isNotEmpty()) { flush(); colors.removeAt(colors.size - 1); i++; continue }
            if (line.startsWith("**", i)) { flush(); bold = !bold; i += 2; continue }
            if (line.startsWith("==", i) && (mark || (i + 2 < n && !line[i + 2].isWhitespace())) && (mark || line.indexOf("==", i + 2) > 0)) {
                flush(); mark = !mark; i += 2; continue
            }
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

    // ---- blocks
    private val FOUR_LETTERS = Regex("[A-Za-z]{4,}")
    private val OPERATOR = Regex("[=+\\-*/^<>]")
    private val SPACES = Regex("\\s+")
    private val HEADING = Regex("""^\s{0,3}(#{1,3})\s+(.+?)\s*#*\s*$""")
    private val RULE = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,})\s*$""")
    private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private val CALLOUT = Regex("""^(tip|warning|remember|example|note|careful|key idea)\s*[:：]\s*(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val QUOTE_MARK = Regex("""^\s*>\s?""")

    /** The cells of one table row; a "|" inside $..$ (like |x|) or escaped as \| is not a column break. */
    fun splitCells(row0: String): List<String> {
        var row = row0.trim()
        if (row.startsWith("|")) row = row.substring(1)
        if (row.endsWith("|") && !row.endsWith("\\|")) row = row.substring(0, row.length - 1)
        val cells = ArrayList<String>()
        val cur = StringBuilder()
        var inMath = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            if (c == '\\' && i + 1 < row.length) {
                if (row[i + 1] == '|') cur.append('|') else cur.append(c).append(row[i + 1])
                i += 2
                continue
            }
            if (c == DOLLAR) inMath = !inMath
            if (c == '|' && !inMath) { cells.add(cur.toString().trim()); cur.setLength(0) } else cur.append(c)
            i++
        }
        cells.add(cur.toString().trim())
        return cells
    }

    private fun aligns(sep: String): List<Char> = splitCells(sep).map { c ->
        val l = c.startsWith(":")
        val r = c.endsWith(":")
        if (l && r) 'c' else if (r) 'r' else 'l'
    }

    private fun fence(lang: String, body: String): Block {
        when (lang) {
            "plot" -> PlotSpec.parse(body)?.let { return Block.Plot(it) }
            "flow", "cycle", "timeline" -> DiagramSpec.parse(lang, body)?.let { return Block.Diagram(it) }
        }
        return Block.Code(body, lang)
    }

    private fun paraLines(lines: List<String>): List<Block> {
        val out = ArrayList<Block>()
        var gap = false
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) { gap = true; i++; continue }
            val s = raw.trim()
            val h = HEADING.find(raw)
            if (h != null) { out.add(Block.Heading(h.groupValues[1].length, inline(h.groupValues[2]))); gap = false; i++; continue }
            if (RULE.matches(raw)) { out.add(Block.Rule); gap = false; i++; continue }
            if (s.startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('|')) {
                val head = splitCells(s)
                val al = aligns(lines[i + 1])
                var j = i + 2
                val rows = ArrayList<List<List<Span>>>()
                while (j < lines.size && lines[j].trim().startsWith("|")) {
                    val cells = splitCells(lines[j])
                    rows.add(List(head.size) { k -> inline(if (k < cells.size) cells[k] else "") })
                    j++
                }
                out.add(Block.Table(head.map { inline(it) }, rows, List(head.size) { k -> if (k < al.size) al[k] else 'l' }))
                i = j; gap = false; continue
            }
            if (s.startsWith(">")) {
                var j = i
                val body = ArrayList<String>()
                while (j < lines.size && lines[j].trim().startsWith(">")) { body.add(QUOTE_MARK.replace(lines[j], "")); j++ }
                val inner = body.joinToString("\n")
                val m = CALLOUT.find(inner.trim())
                if (m != null) out.add(Block.Callout(m.groupValues[1].lowercase(), blocks(m.groupValues[2])))
                else out.add(Block.Quote(blocks(inner)))
                i = j; gap = false; continue
            }
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
            i++
        }
        return out
    }

    /** Ordinary text (no code fences): display equations first, then the line-by-line blocks. */
    private fun textBlocks(t: String): List<Block> {
        val out = ArrayList<Block>()
        var pos = 0
        for (m in DISPLAY_DOLLARS.findAll(t)) {
            out.addAll(paraLines(t.substring(pos, m.range.first).split('\n')))
            out.add(Block.Display(m.groupValues[1].trim()))
            pos = m.range.last + 1
        }
        out.addAll(paraLines(t.substring(pos).split('\n')))
        return out
    }

    fun blocks(text: String): List<Block> {
        val lines = normalize(text.replace("\r", "")).split('\n')
        val out = ArrayList<Block>()
        val normal = StringBuilder()
        fun flushNormal() {
            if (normal.isNotEmpty()) { out.addAll(textBlocks(normal.toString())); normal.setLength(0) }
        }
        var i = 0
        while (i < lines.size) {
            val s = lines[i].trim()
            if (s.startsWith("```")) {
                flushNormal()
                val lang = s.removePrefix("```").trim().lowercase().split(SPACES).first()
                var j = i + 1
                val body = ArrayList<String>()
                while (j < lines.size && !lines[j].trim().startsWith("```")) { body.add(lines[j]); j++ }
                out.add(fence(lang, body.joinToString("\n")))
                i = j + 1
                continue
            }
            normal.append(lines[i]).append('\n')
            i++
        }
        flushNormal()
        return out
    }

    /** True when [blocks] need more than a plain Text: anything but plain paragraphs (bold, italic and code are fine). */
    fun isRich(blocks: List<Block>): Boolean = blocks.any {
        it !is Block.Para || it.marker != null || it.spans.any { s -> s is Span.Math || (s is Span.Txt && (s.color != 0 || s.mark)) }
    }
}
