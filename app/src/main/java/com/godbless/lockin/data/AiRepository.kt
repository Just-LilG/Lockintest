package com.godbless.lockin.data

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** A textbook page for the AI: which book, its page number (from 1), and a JPEG of it in base64. */
class AiPage(val book: String, val page: Int, val image: String)

class AiQuestion(val question: String, val options: List<String>, val answer: Int, val why: String, val page: Int)
class AiCard(val question: String, val answer: String, val page: Int)
class AiSimpler(val question: String, val answer: String, val hint: String)
class AiExplanation(val summary: String, val points: List<String>, val example: String, val check: String)

/**
 * What the AI said about a word, sentence or equation you selected on a page. [text] is what it read in the
 * selection. A first explanation fills [meaning], [context], [example] and [terms]; a follow-up (a question, another
 * example) fills [answer] instead.
 */
class AiPassage(
    val text: String,
    val meaning: String,
    val context: String,
    val example: String,
    val terms: List<Pair<String, String>>,
    val answer: String,
)

/**
 * The AI study helper (the "ai" Edge Function: Groq first, Gemini behind it). Everything it says comes from the pages you send:
 * a quiz, an explanation, or flashcards. Pages are sent as pictures, so scanned books work too.
 */
class AiRepository(private val partners: PartnerRepository) {

    private companion object {
        @Volatile var warmedAt = 0L
    }

    /**
     * Tells the server the AI is about to be used, so it is awake, its keys are loaded and the connection to the AI
     * is open when the real request arrives. Costs nothing from the daily allowance; at most once every four minutes.
     */
    suspend fun warm() {
        val now = System.currentTimeMillis()
        if (now - warmedAt < 240_000L) return
        warmedAt = now
        runCatching { partners.call("POST", "/functions/v1/ai", """{"task":"warm"}""", readMs = 8_000) }
            .onFailure { warmedAt = 0L }
    }

    private suspend fun ask(task: String, pages: List<AiPage>, extra: JSONObject.() -> Unit = {}): JSONObject {
        val p = JSONArray()
        pages.forEach { p.put(JSONObject().put("book", it.book).put("page", it.page).put("image", it.image)) }
        val body = JSONObject().put("task", task).put("pages", p).apply(extra).toString()
        val started = System.currentTimeMillis()
        // Reading a dozen pages takes the AI a little while.
        return try {
            JSONObject(partners.call("POST", "/functions/v1/ai", body, readMs = 115_000).body)
        } catch (e: ApiException) {
            // The AI said "busy" or hiccuped straight away (the server has already tried its other routes, and gave the
            // allowance back): one more go after a breath is cheaper than asking the person to tap Try again.
            // Not after a long wait, which would double it.
            if ((e.status == 502 || e.status == 503 || e.status == 504) && System.currentTimeMillis() - started < 20_000L) {
                delay(1_200)
                JSONObject(partners.call("POST", "/functions/v1/ai", body, readMs = 115_000).body)
            } else {
                throw e
            }
        }
    }

    suspend fun quiz(pages: List<AiPage>, count: Int = 6): Result<List<AiQuestion>> = partners.attempt {
        val a = ask("quiz", pages) { put("count", count) }.optJSONArray("questions") ?: JSONArray()
        List(a.length()) {
            val q = a.getJSONObject(it)
            val o = q.getJSONArray("options")
            AiQuestion(q.getString("q"), List(o.length()) { i -> o.getString(i) }, q.getInt("answer"), q.optString("why"), q.optInt("page", 1))
        }
    }

    suspend fun cards(pages: List<AiPage>, count: Int = 6): Result<List<AiCard>> = partners.attempt {
        val a = ask("cards", pages) { put("count", count) }.optJSONArray("cards") ?: JSONArray()
        List(a.length()) {
            val c = a.getJSONObject(it)
            AiCard(c.getString("q"), c.getString("a"), c.optInt("page", 1))
        }
    }

    /**
     * A clearer version of a card that keeps being missed: a simpler question, a shorter answer and a memory hook.
     * [page] (the card's textbook page) is optional: it keeps the AI to what the book says.
     */
    suspend fun simplify(question: String, answer: String, page: AiPage?): Result<AiSimpler> = partners.attempt {
        val j = ask("simplify", listOfNotNull(page)) { put("card", JSONObject().put("q", question).put("a", answer)) }
        AiSimpler(j.optString("q").ifBlank { question }, j.optString("a").ifBlank { answer }, j.optString("hint"))
    }

    suspend fun explain(page: AiPage, simpler: Boolean = false): Result<AiExplanation> = partners.attempt {
        val j = ask("explain", listOf(page)) { put("simpler", simpler) }
        val pts = j.optJSONArray("points") ?: JSONArray()
        AiExplanation(j.optString("summary"), List(pts.length()) { pts.getString(it) }, j.optString("example"), j.optString("check"))
    }

    /**
     * Explains a selection: [snip] is a JPEG (base64) of just the part you selected, [page] the whole page around it.
     * [mode] is "explain", "simpler", "example" or "ask" (with [question]). [prior] is what was already said, so a
     * follow-up builds on it instead of repeating it.
     */
    suspend fun passage(page: AiPage, snip: String, mode: String = "explain", question: String = "", prior: String = ""): Result<AiPassage> = partners.attempt {
        val j = ask("passage", listOf(page)) {
            put("snip", snip)
            put("mode", mode)
            if (question.isNotBlank()) put("question", question)
            if (prior.isNotBlank()) put("prior", prior.take(1400))
        }
        val t = j.optJSONArray("terms") ?: JSONArray()
        AiPassage(
            j.optString("text"), j.optString("meaning"), j.optString("context"), j.optString("example"),
            List(t.length()) { t.getJSONObject(it).let { o -> o.optString("term") to o.optString("meaning") } }.filter { it.first.isNotBlank() },
            j.optString("answer"),
        )
    }
}
