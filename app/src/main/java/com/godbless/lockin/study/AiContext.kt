package com.godbless.lockin.study

/*
 * What the app tells the AI about the book besides the pictures (the server's "ctx"): the page's own text, the edges of
 * the neighbouring pages, the passages elsewhere in the book that match the question, short notes about the book, and
 * what the student keeps getting wrong. All of it is optional: with none of it the AI works from the pictures alone.
 * Pure Kotlin: see AiContextTest.
 */

internal class AskRef(val page: Int, val text: String)

internal class AskContext(
    val ptext: String,
    val prev: String,
    val next: String,
    val refs: List<AskRef>,
    val notes: String,
    val weak: List<String>,
) {
    fun isEmpty() = ptext.isBlank() && prev.isBlank() && next.isBlank() && refs.isEmpty() && notes.isBlank() && weak.isEmpty()
}

internal object AiContext {
    /** [page] is 0-based; the refs are numbered from 1, as the student sees pages. */
    fun build(index: BookIndex?, page: Int, question: String, notes: String, weak: List<String>): AskContext {
        val own = index?.pageText(page).orEmpty()
        val before = index?.pageText(page - 1).orEmpty()
        val after = index?.pageText(page + 1).orEmpty()
        val refs = if (index != null && question.isNotBlank()) {
            index.search(question, k = 3, exclude = setOf(page)).map { AskRef(it.page + 1, it.text) }
        } else emptyList()
        return AskContext(
            ptext = own.take(3500),
            prev = if (before.length > 900) before.takeLast(900) else before,
            next = after.take(900),
            refs = refs,
            notes = notes.take(800),
            weak = weak,
        )
    }

    /**
     * Up to [limit] questions of this book the student has missed more often than got right, the worst first: what to
     * stress. A card nobody has answered yet is not a weakness.
     */
    fun weakTopics(cards: List<Card>, bookId: String, limit: Int = 5): List<String> =
        cards.asSequence()
            .filter { it.book == bookId && it.wrong > it.right }
            .sortedWith(compareByDescending<Card> { it.wrong - it.right }.thenByDescending { it.last })
            .map { it.question.replace(Regex("""\s+"""), " ").trim().take(100) }
            .filter { it.length > 3 }
            .distinct()
            .take(limit)
            .toList()

    /** Evenly spread pages to read when writing notes about the whole book. */
    fun samplePages(have: List<Int>, max: Int = 12): List<Int> {
        if (have.size <= max) return have
        val step = have.size.toDouble() / max
        return List(max) { have[(it * step).toInt()] }.distinct()
    }

    /** The notes as the AI will read them: a summary, then the key terms. */
    fun notesText(summary: String, glossary: List<Pair<String, String>>): String =
        buildString {
            append(summary.trim())
            if (glossary.isNotEmpty()) append("\nKey terms: ").append(glossary.joinToString("; ") { "${it.first}: ${it.second}" })
        }.trim()
}
