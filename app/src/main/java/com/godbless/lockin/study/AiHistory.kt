package com.godbless.lockin.study

import androidx.compose.runtime.mutableStateListOf
import com.godbless.lockin.data.AiCard
import com.godbless.lockin.data.AiExplanation
import com.godbless.lockin.data.AiPassage
import com.godbless.lockin.data.AiQuestion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class AiKind { Explain, Cards, Quiz, Passage }

/**
 * One thing the AI made for you, kept so it doesn't vanish when the sheet closes. [page] is the first page it
 * was about, counted from 1; [pages] are all of them. [data] holds what it said (see the log functions).
 */
class AiEntry(
    val id: String,
    val kind: AiKind,
    val book: String,
    val bookTitle: String,
    val page: Int,
    val pages: List<Int>,
    val at: Long,
    /** An explanation in simpler words. */
    val simpler: Boolean,
    val data: JSONObject,
)

fun AiEntry.explanation(): AiExplanation {
    val pts = data.optJSONArray("points") ?: JSONArray()
    return AiExplanation(data.optString("summary"), List(pts.length()) { pts.getString(it) }, data.optString("example"), data.optString("check"))
}

fun AiEntry.cards(): List<AiCard> {
    val a = data.optJSONArray("cards") ?: JSONArray()
    return List(a.length()) { a.getJSONObject(it).let { o -> AiCard(o.optString("q"), o.optString("a"), o.optInt("p", 1)) } }
}

/** How many of this set's cards were added to your flashcards (0 if none yet). */
val AiEntry.cardsAdded: Int get() = data.optInt("added", 0)

fun AiEntry.questions(): List<AiQuestion> {
    val a = data.optJSONArray("qs") ?: JSONArray()
    return List(a.length()) {
        val o = a.getJSONObject(it)
        val opts = o.optJSONArray("o") ?: JSONArray()
        AiQuestion(o.optString("q"), List(opts.length()) { i -> opts.getString(i) }, o.optInt("a"), o.optString("w"), o.optInt("p", 1))
    }
}

/** One follow-up on a selection: what you asked (or tapped) and what the AI answered. */
class PassageTurn(val ask: String, val answer: String)

/** A saved selection: what it said first, the follow-ups after, and where the selection was on the page. */
class SavedPassage(val first: AiPassage, val turns: List<PassageTurn>, val rect: List<Float>, val snip: String)

fun AiEntry.passage(): SavedPassage {
    val t = data.optJSONArray("terms") ?: JSONArray()
    val first = AiPassage(
        data.optString("text"), data.optString("meaning"), data.optString("context"), data.optString("example"),
        List(t.length()) { t.getJSONObject(it).let { o -> o.optString("t") to o.optString("m") } }, "",
    )
    val th = data.optJSONArray("thread") ?: JSONArray()
    val turns = List(th.length()) { th.getJSONObject(it).let { o -> PassageTurn(o.optString("q"), o.optString("a")) } }
    val r = data.optJSONArray("rect") ?: JSONArray()
    return SavedPassage(first, turns, List(r.length()) { r.getDouble(it).toFloat() }, data.optString("snip"))
}

/** Your score on this quiz, or null if you haven't finished it. */
val AiEntry.quizScore: Pair<Int, Int>? get() = if (data.optInt("right", -1) >= 0) data.optInt("right") to data.optInt("total") else null

/**
 * Everything the AI helper has made for you, newest first, in one small file on this phone (like the books and
 * cards). Reopening a page you already had explained shows the saved explanation straight away, without
 * asking the AI again.
 */
class AiHistoryStore(private val dir: File) {
    private val file = File(dir, "ai_history.json")
    val entries = mutableStateListOf<AiEntry>()

    init {
        entries.addAll(read())
    }

    /** The saved explanation for this page (counted from 1), in the plain or the simpler wording. */
    fun explanation(book: String, page: Int, simpler: Boolean): AiEntry? =
        entries.firstOrNull { it.kind == AiKind.Explain && it.book == book && it.page == page && it.simpler == simpler }

    fun countFor(book: String): Int = entries.count { it.book == book }

    /** Saves an explanation. A newer one for the same page and wording replaces the old. */
    fun logExplain(book: String, title: String, page: Int, simpler: Boolean, e: AiExplanation): String {
        entries.removeAll { it.kind == AiKind.Explain && it.book == book && it.page == page && it.simpler == simpler }
        val data = JSONObject().put("summary", e.summary).put("points", JSONArray(e.points)).put("example", e.example).put("check", e.check)
        return add(AiEntry(newId(), AiKind.Explain, book, title, page, listOf(page), now(), simpler, data))
    }

    fun logCards(book: String, title: String, pages: List<Int>, cards: List<AiCard>): String {
        val a = JSONArray()
        cards.forEach { a.put(JSONObject().put("q", it.question).put("a", it.answer).put("p", it.page)) }
        val ps = pages.distinct().sorted()
        return add(AiEntry(newId(), AiKind.Cards, book, title, ps.firstOrNull() ?: 1, ps, now(), false, JSONObject().put("cards", a).put("added", 0)))
    }

    fun markCardsAdded(id: String, n: Int) = update(id) { it.put("added", n) }

    fun logQuiz(book: String, title: String, pages: List<Int>, qs: List<AiQuestion>): String {
        val a = JSONArray()
        qs.forEach { q ->
            a.put(JSONObject().put("q", q.question).put("o", JSONArray(q.options)).put("a", q.answer).put("w", q.why).put("p", q.page))
        }
        val ps = pages.distinct().sorted()
        return add(AiEntry(newId(), AiKind.Quiz, book, title, ps.firstOrNull() ?: 1, ps, now(), false, JSONObject().put("qs", a).put("right", -1).put("total", qs.size)))
    }

    /**
     * Saves an explained selection. [rect] is where it was on the page (left, top, right, bottom as fractions of the
     * page) and [snip] the file name of its picture (see [snipFile]).
     */
    fun logPassage(book: String, title: String, page: Int, p: AiPassage, rect: List<Float>, snip: String): String {
        val terms = JSONArray()
        p.terms.forEach { terms.put(JSONObject().put("t", it.first).put("m", it.second)) }
        val data = JSONObject().put("text", p.text).put("meaning", p.meaning).put("context", p.context).put("example", p.example)
            .put("terms", terms).put("thread", JSONArray()).put("rect", JSONArray(rect.map { it.toDouble() })).put("snip", snip)
        return add(AiEntry(newId(), AiKind.Passage, book, title, page, listOf(page), now(), false, data))
    }

    /** Adds a follow-up to a saved selection. */
    fun addPassageTurn(id: String, ask: String, answer: String) = update(id) { d ->
        val th = d.optJSONArray("thread") ?: JSONArray().also { d.put("thread", it) }
        th.put(JSONObject().put("q", ask).put("a", answer))
    }

    /** Where a selection's picture is kept (small JPEGs beside the history file). */
    fun snipFile(name: String): File = File(File(dir, "snips").apply { mkdirs() }, name)

    fun setQuizScore(id: String, right: Int, total: Int) = update(id) { it.put("right", right).put("total", total) }

    fun remove(id: String) {
        entries.filter { it.id == id }.forEach(::dropSnip)
        entries.removeAll { it.id == id }
        save()
    }

    private fun dropSnip(e: AiEntry) {
        if (e.kind == AiKind.Passage) e.data.optString("snip").takeIf { it.isNotBlank() }?.let { runCatching { snipFile(it).delete() } }
    }

    /** A book was removed: so is what the AI said about it. */
    fun removeBook(book: String) {
        entries.filter { it.book == book }.forEach(::dropSnip)
        if (entries.removeAll { it.book == book }) save()
    }

    private fun update(id: String, change: (JSONObject) -> Unit) {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) return
        val e = entries[i]
        val copy = JSONObject(e.data.toString()).also(change)
        entries[i] = AiEntry(e.id, e.kind, e.book, e.bookTitle, e.page, e.pages, e.at, e.simpler, copy)
        save()
    }

    private fun add(e: AiEntry): String {
        entries.add(0, e)
        while (entries.size > Cap) dropSnip(entries.removeAt(entries.lastIndex))
        save()
        return e.id
    }

    private fun newId() = UUID.randomUUID().toString().take(12)
    private fun now() = System.currentTimeMillis()

    private fun save() {
        val a = JSONArray()
        entries.forEach {
            a.put(
                JSONObject().put("id", it.id).put("k", it.kind.name).put("b", it.book).put("bt", it.bookTitle).put("p", it.page)
                    .put("ps", JSONArray(it.pages)).put("t", it.at).put("s", it.simpler).put("d", it.data),
            )
        }
        runCatching {
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(file)
        }
    }

    private fun read(): List<AiEntry> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) {
            val o = a.getJSONObject(it)
            val ps = o.optJSONArray("ps") ?: JSONArray()
            AiEntry(
                o.getString("id"), AiKind.valueOf(o.getString("k")), o.getString("b"), o.optString("bt"), o.optInt("p", 1),
                List(ps.length()) { i -> ps.getInt(i) }, o.optLong("t"), o.optBoolean("s"), o.getJSONObject("d"),
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val Cap = 150
    }
}
