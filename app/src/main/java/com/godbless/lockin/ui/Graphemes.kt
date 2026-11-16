package com.godbless.lockin.ui

import java.text.BreakIterator

/**
 * Splits text into what a person sees as one character each: an emoji (a surrogate pair, often with skin-tone or
 * joiner parts) stays whole. Splitting with `forEach`/`take(1)` cuts those in half, and each half draws as a
 * replacement mark (the black diamond with a question mark).
 */
fun graphemes(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    return try {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = ArrayList<String>(text.length)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = it.next()
        }
        out
    } catch (_: Throwable) {
        // Plain fallback: by code point, so pairs at least stay whole.
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            out.add(text.substring(i, i + n))
            i += n
        }
        out
    }
}

/** The first visible character (an emoji counts as one), upper-cased. */
fun String.firstGlyph(): String = graphemes(trim()).firstOrNull()?.uppercase() ?: ""

/** The first [n] visible characters, never cutting an emoji in half. */
fun String.safeTake(n: Int): String = if (length <= n) this else graphemes(this).take(n).joinToString("")
