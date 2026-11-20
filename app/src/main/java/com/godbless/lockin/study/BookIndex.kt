package com.godbless.lockin.study

import kotlin.math.ln

/*
 * Search inside one book's own text. The AI is given the pages around a question: the reader's page, its neighbours, and
 * the few places in the book that talk about the same thing (this file), so it can answer from the book and cite
 * "page 9" instead of guessing. Pure Kotlin (no Android): see BookIndexTest. The extraction from the PDF is in BookTexts.kt.
 *
 * Pages are cut into passages of up to 650 characters and ranked with BM25 (the standard keyword ranking): a word that
 * is rare in the book counts for more than a common one, and a short passage that is all about the word beats a long one
 * that mentions it once. Words are lower-cased, stop words dropped and plurals/endings lightly trimmed.
 */

internal class BookHit(val page: Int, val text: String, val score: Double)

internal object BookTerms {
    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was", "were", "be", "been", "it", "this",
        "that", "these", "those", "as", "at", "by", "from", "what", "which", "who", "how", "why", "does", "do", "did", "can", "you",
        "your", "about", "into", "than", "then", "there", "their", "its", "not", "no", "if", "but", "so", "we", "i", "me", "my",
    )

    /** A light stem: "forces" and "force" match, "accelerating" and "accelerated" do not need to. */
    fun stem(w: String): String = when {
        w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
        w.endsWith("ing") && w.length > 5 -> w.dropLast(3)
        w.endsWith("ed") && w.length > 4 -> w.dropLast(2)
        w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && w.length > 3 -> w.dropLast(1)
        else -> w
    }

    fun terms(text: String): List<String> =
        WORD.findAll(text.lowercase()).map { it.value }.filter { it.length >= 2 && it !in STOP }.map { stem(it) }.toList()
}

internal class BookIndex {
    private class Chunk(val page: Int, val text: String, val tf: Map<String, Int>, val len: Int)

    private val chunks = ArrayList<Chunk>()
    private val df = HashMap<String, Int>()
    private val pageTexts = HashMap<Int, String>()
    private var totalLen = 0L

    /** How many pages have text. */
    val pageCount: Int get() = pageTexts.size

    /** The pages that have text, in order (0-based, like the reader). */
    val pagesWithText: List<Int> get() = pageTexts.keys.sorted()

    fun pageText(page: Int): String? = pageTexts[page]

    /** Adds one page ([page] is 0-based). Adding it again replaces nothing: build an index once, from empty. */
    fun add(page: Int, text: String) {
        if (text.isBlank()) return
        pageTexts[page] = text
        for (c in chunk(text)) {
            val ts = BookTerms.terms(c)
            if (ts.isEmpty()) continue
            val tf = HashMap<String, Int>()
            for (t in ts) tf[t] = (tf[t] ?: 0) + 1
            chunks.add(Chunk(page, c, tf, ts.size))
            totalLen += ts.size
            for (t in tf.keys) df[t] = (df[t] ?: 0) + 1
        }
    }

    /**
     * The best passages for [query], at most one per page, best first, at most [k]; pages in [exclude] are skipped (the
     * page already being read). Passages scoring under 30% of the best are dropped, and nothing matches nothing.
     */
    fun search(query: String, k: Int = 3, exclude: Set<Int> = emptySet()): List<BookHit> {
        val qs = BookTerms.terms(query).toSet()
        if (qs.isEmpty() || chunks.isEmpty()) return emptyList()
        val n = chunks.size
        val avg = totalLen.toDouble() / n
        val best = HashMap<Int, BookHit>()
        for (c in chunks) {
            if (c.page in exclude) continue
            var s = 0.0
            for (t in qs) {
                val f = c.tf[t] ?: continue
                val d = df[t] ?: 1
                val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
                s += idf * (f * 2.5) / (f + 1.5 * (0.25 + 0.75 * c.len / avg))
            }
            val old = best[c.page]
            if (s > 0 && (old == null || s > old.score)) best[c.page] = BookHit(c.page, c.text, s)
        }
        val hits = best.values.sortedByDescending { it.score }
        if (hits.isEmpty()) return emptyList()
        val top = hits[0].score
        return hits.filter { it.score >= 0.3 * top }.take(k)
    }

    companion object {
        private val BREAKS = Regex("""\n\s*\n|\n""")
        private val SPACES = Regex("""\s+""")

        /** Cuts a page into passages of at most [max] characters, at paragraph or sentence ends where it can. */
        fun chunk(text: String, max: Int = 650): List<String> {
            val out = ArrayList<String>()
            var cur = ""
            fun flush() { if (cur.isNotEmpty()) { out.add(cur); cur = "" } }
            for (raw in text.replace("\r", "").split(BREAKS)) {
                var p = raw.replace(SPACES, " ").trim()
                if (p.isEmpty()) continue
                while (p.length > max) {
                    val dot = p.lastIndexOf(". ", max - 2)
                    val cut = if (dot > max / 3) dot + 1 else max
                    val piece = p.substring(0, cut).trim()
                    p = p.substring(cut).trim()
                    flush()
                    out.add(piece)
                }
                if (cur.length + p.length + 1 > max) flush()
                cur = (cur + " " + p).trim()
            }
            flush()
            return out
        }
    }
}

/** One page per line, "page<TAB>text", with \ , newline and tab escaped: how a book's text is kept on the phone. */
internal object BookTextCodec {
    fun encode(pages: Map<Int, String>): String = buildString {
        for (p in pages.keys.sorted()) {
            append(p).append('\t')
            for (c in pages.getValue(p)) {
                when (c) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    '\r' -> {}
                    else -> append(c)
                }
            }
            append('\n')
        }
    }

    fun decode(s: String): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (line in s.split('\n')) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val page = line.substring(0, tab).toIntOrNull() ?: continue
            val sb = StringBuilder()
            var i = tab + 1
            while (i < line.length) {
                val c = line[i]
                if (c == '\\' && i + 1 < line.length) {
                    when (line[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); '\\' -> sb.append('\\'); else -> sb.append(line[i + 1]) }
                    i += 2
                } else { sb.append(c); i++ }
            }
            out[page] = sb.toString()
        }
        return out
    }
}
