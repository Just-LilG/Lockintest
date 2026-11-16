package com.godbless.lockin.study

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

/** A PDF the person added. It lives in this app's private storage and is never uploaded. */
data class Textbook(
    val id: String,
    val title: String,
    val pages: Int,
    val added: Long,
    val lastPage: Int,
    /** The exam on this book (midnight of its day), or 0. Cards of a book with an exam coming are spaced to be learned by then. */
    val exam: Long = 0L,
)

/** A page you read: on screen for at least a few seconds. [page] counts from 0. */
data class PageRead(val book: String, val page: Int, val at: Long)

/**
 * One question to learn. [box] is how well it is known (0 to 4) and [due] is when it should come back;
 * missing a card sends it to box 0, getting it right moves it up and pushes [due] further out.
 */
data class Card(
    val id: String,
    val book: String,
    val question: String,
    val answer: String,
    val page: Int,
    val box: Int,
    val due: Long,
    val right: Int,
    val wrong: Int,
    /** When it was last answered (0 if never). */
    val last: Long = 0L,
    /** A picture on the question side: a file name in the study images folder, or "". */
    val image: String = "",
)

/** How an answer went: missed it, got it, or knew it cold (which moves it out two boxes). */
enum class Rating { Missed, Good, Easy }

/** How far along a card is: never answered, being learned, or learned (box 3 and up). */
enum class Stage { New, Learning, Learned }

val Card.stage: Stage get() = when {
    right + wrong == 0 -> Stage.New
    box >= StudyStore.MasteredBox -> Stage.Learned
    else -> Stage.Learning
}

/** A card that keeps being missed (four times or more, and more often missed than got): worth rewriting. */
val Card.tricky: Boolean get() = wrong >= 4 && wrong > right && box <= 1

/** A book title fit to show: file-name dashes and underscores become spaces. */
fun prettyTitle(t: String): String = t.replace('_', ' ').replace(Regex("(?<=\\S)-(?=\\S)"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Textbook" }

/** One finished quiz: when, how many cards, how many right, and the seconds it took. */
data class QuizRun(val at: Long, val total: Int, val right: Int, val seconds: Int)

/** Why a PDF could not be added, in words for the person. */
class AddBookException(message: String) : Exception(message)

/**
 * The textbooks and flashcards, all kept on this phone. PDFs are copied into the app's own folder so
 * they keep working if the original is moved or deleted. Both lists are small JSON files next to them.
 */
class StudyStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "study").apply { mkdirs() }
    private val booksFile = File(dir, "books.json")
    private val cardsFile = File(dir, "cards.json")
    private val readsFile = File(dir, "reads.json")
    private val runsFile = File(dir, "quiz_runs.json")

    val books = mutableStateListOf<Textbook>()
    val cards = mutableStateListOf<Card>()

    /** Pages you've read, newest last (the last 600). What the AI quizzes you on. */
    val reads = mutableStateListOf<PageRead>()

    /** Finished quizzes, newest last (the last 200). */
    val runs = mutableStateListOf<QuizRun>()

    /** What the AI helper made for you (explanations, flashcard sets, quizzes), kept on this phone. */
    val ai = AiHistoryStore(dir)

    init {
        runs.addAll(readRuns())
        books.addAll(readBooks())
        cards.addAll(readCards())
        reads.addAll(readReads())
    }

    // ----- What you've read -----

    /** Notes that [page] of [book] was read just now. Re-reading a page moves it to the end. */
    fun markRead(book: String, page: Int) {
        reads.removeAll { it.book == book && it.page == page }
        reads.add(PageRead(book, page, System.currentTimeMillis()))
        while (reads.size > 600) reads.removeAt(0)
        val a = JSONArray()
        reads.forEach { a.put(JSONObject().put("b", it.book).put("p", it.page).put("t", it.at)) }
        runCatching { write(readsFile, a) }
    }

    /** Pages read since [sinceMillis], oldest first, at most [max] (the most recent ones). */
    fun readSince(sinceMillis: Long, max: Int = 12): List<PageRead> =
        reads.filter { it.at >= sinceMillis && book(it.book) != null }.takeLast(max)

    /** The pages read in the last day, or failing that the last [max] pages ever read. */
    fun recentReading(max: Int = 12): List<PageRead> =
        readSince(System.currentTimeMillis() - 24 * 3_600_000L, max).ifEmpty { reads.filter { book(it.book) != null }.takeLast(max) }

    private fun readReads(): List<PageRead> = runCatching {
        val a = JSONArray(readsFile.readText())
        List(a.length()) { a.getJSONObject(it).let { o -> PageRead(o.getString("b"), o.getInt("p"), o.getLong("t")) } }
    }.getOrDefault(emptyList())

    fun pdfFile(id: String) = File(dir, "$id.pdf")
    private val imageDir = File(dir, "img").apply { mkdirs() }
    /** A card's picture (see [Card.image]). */
    fun imageFile(name: String) = File(imageDir, name)
    fun thumbFile(id: String) = File(dir, "$id.png")
    fun book(id: String): Textbook? = books.firstOrNull { it.id == id }

    // ----- Textbooks -----

    /** Copies the PDF into private storage, checks it opens, and makes a small cover from its first page. */
    suspend fun addBook(uri: Uri): Result<Textbook> = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val file = pdfFile(id)
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw AddBookException("Couldn't read that file.")
            input.use { src -> FileOutputStream(file).use { out -> src.copyTo(out) } }

            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                fd.close()
                throw AddBookException("That PDF is protected with a password.")
            }
            val pages = try {
                val count = renderer.pageCount
                if (count == 0) throw AddBookException("That PDF has no pages.")
                val cover = renderPage(renderer, 0, 360)
                FileOutputStream(thumbFile(id)).use { cover.compress(Bitmap.CompressFormat.PNG, 90, it) }
                count
            } finally {
                renderer.close()
            }

            val book = Textbook(id, displayName(uri), pages, System.currentTimeMillis(), 0)
            withContext(Dispatchers.Main) {
                books.add(0, book)
                saveBooks()
            }
            Result.success(book)
        } catch (e: AddBookException) {
            file.delete()
            thumbFile(id).delete()
            Result.failure(e)
        } catch (e: Exception) {
            file.delete()
            thumbFile(id).delete()
            Result.failure(AddBookException("That file isn't a PDF this phone can open."))
        }
    }

    /** Deletes a textbook and the cards made from it. */
    fun removeBook(id: String) {
        books.removeAll { it.id == id }
        cards.removeAll { it.book == id }
        ai.removeBook(id)
        pdfFile(id).delete()
        thumbFile(id).delete()
        saveBooks()
        saveCards()
    }

    fun setLastPage(id: String, page: Int) {
        val i = books.indexOfFirst { it.id == id }
        if (i < 0 || books[i].lastPage == page) return
        books[i] = books[i].copy(lastPage = page)
        saveBooks()
    }

    private fun displayName(uri: Uri): String {
        val raw = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "Textbook"
        return prettyTitle(raw.removeSuffix(".pdf").removeSuffix(".PDF"))
    }

    // ----- Cards -----

    /** Adds a card and returns its id. [image]: a picture already saved with [saveImage], or "". */
    fun addCard(book: String, question: String, answer: String, page: Int, image: String = ""): String {
        val id = UUID.randomUUID().toString().take(12)
        cards.add(0, Card(id, book, question.trim(), answer.trim(), page, 0, 0L, 0, 0, image = image))
        saveCards()
        return id
    }

    /** Saves a picture for a card (a crop of a textbook page) and returns its file name. */
    fun saveImage(bitmap: Bitmap): String? = runCatching {
        val name = UUID.randomUUID().toString().replace("-", "").take(14) + ".jpg"
        // Small enough to keep many, sharp enough to read a diagram.
        val w = bitmap.width.coerceAtMost(1100)
        val scaled = if (w < bitmap.width) Bitmap.createScaledBitmap(bitmap, w, (bitmap.height * w.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1), true) else bitmap
        FileOutputStream(imageFile(name)).use { scaled.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        name
    }.getOrNull()

    /** Changes the words, book and page of a card. Its progress stays. */
    fun updateCard(id: String, book: String, question: String, answer: String, page: Int, image: String? = null) {
        val i = cards.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = cards[i]
        val pic = image ?: old.image
        if (old.image.isNotEmpty() && old.image != pic) imageFile(old.image).delete()
        cards[i] = old.copy(book = book, question = question.trim(), answer = answer.trim(), page = page, image = pic)
        saveCards()
    }

    /** New words for a card that wasn't working: it starts again as a new card (due now, no history). */
    fun rewriteCard(id: String, question: String, answer: String) {
        val i = cards.indexOfFirst { it.id == id }
        if (i < 0) return
        cards[i] = cards[i].copy(question = question.trim(), answer = answer.trim(), box = 0, due = 0L, right = 0, wrong = 0, last = 0L)
        saveCards()
    }

    /** Sets (or clears, with 0) the exam date of a book. */
    fun setExam(id: String, dayMillis: Long) {
        val i = books.indexOfFirst { it.id == id }
        if (i < 0) return
        books[i] = books[i].copy(exam = if (dayMillis > 0) dayStart(dayMillis) else 0L)
        saveBooks()
    }

    /** Whole days from today until a book's exam (0 on the day), or null when there's none coming. */
    fun daysToExam(book: Textbook?): Int? {
        val e = book?.exam ?: return null
        if (e <= 0L) return null
        val d = ((e - dayStart()) / DAY_MS).toInt()
        return if (d < 0) null else d
    }

    /** Puts a card back as it was (undo a grade, or undo a delete at its old spot). */
    fun restore(card: Card, at: Int = -1) {
        val i = cards.indexOfFirst { it.id == card.id }
        if (i >= 0) cards[i] = card else cards.add(if (at < 0) 0 else at.coerceAtMost(cards.size), card)
        saveCards()
    }

    fun renameBook(id: String, title: String) {
        val i = books.indexOfFirst { it.id == id }
        val t = title.trim()
        if (i < 0 || t.isEmpty()) return
        books[i] = books[i].copy(title = t)
        saveBooks()
    }

    fun deleteCard(id: String) {
        cards.firstOrNull { it.id == id }?.image?.takeIf { it.isNotEmpty() }?.let { imageFile(it).delete() }
        cards.removeAll { it.id == id }
        saveCards()
    }

    /**
     * Records an answer. Missed sends the card back to box 0 (due again straight away); Good moves it up one box and
     * out in time; Easy moves it up two. Returns the card as it was, so the answer can be undone with [restore].
     */
    fun grade(id: String, rating: Rating): Card? {
        val i = cards.indexOfFirst { it.id == id }
        if (i < 0) return null
        val c = cards[i]
        val now = System.currentTimeMillis()
        val box = when (rating) {
            Rating.Missed -> 0
            Rating.Good -> (c.box + 1).coerceAtMost(Days.lastIndex)
            Rating.Easy -> (c.box + 2).coerceAtMost(Days.lastIndex)
        }
        // With an exam coming, a card never waits past a third of the time that's left (and comes back at least
        // once more before the day), so everything is seen again in time.
        var days = Days[box]
        daysToExam(book(c.book))?.let { left -> if (days > 0) days = days.coerceAtMost((left / 3L).coerceAtLeast(1L)) }
        cards[i] = c.copy(
            box = box,
            due = dueAt(now, days),
            right = c.right + if (rating == Rating.Missed) 0 else 1,
            wrong = c.wrong + if (rating == Rating.Missed) 1 else 0,
            last = now,
        )
        saveCards()
        return c
    }

    /** Cards come due when the day starts, not 24 hours after you answered: day 1 means tomorrow morning. */
    private fun dueAt(now: Long, days: Long): Long {
        if (days <= 0L) return 0L
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.DAY_OF_YEAR, days.toInt())
        }
        return c.timeInMillis
    }

    /** Midnight at the start of today on this phone. */
    fun dayStart(now: Long = System.currentTimeMillis()): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Cards answered today that aren't due again today. */
    fun doneToday(pool: List<Card>): Int = pool.count { it.last >= dayStart() && !isDue(it) }

    /** When the next card in [pool] comes due, or null if none is waiting. */
    fun nextDue(pool: List<Card>): Long? = pool.filter { !isDue(it) }.minOfOrNull { it.due }

    // ----- Finished quizzes -----

    /** Cards answered on each of the last [n] days (oldest first, today last), from finished quizzes. */
    fun reviewsByDay(n: Int = 7): IntArray {
        val out = IntArray(n)
        val today = dayStart()
        for (r in runs) {
            val back = ((today - dayStart(r.at)) / DAY_MS).toInt()
            if (back in 0 until n) out[n - 1 - back] += r.total
        }
        return out
    }

    /** Share right over the last 7 days, 0 to 100, or null with nothing answered. */
    fun weekAccuracy(): Int? {
        val since = dayStart() - 6 * DAY_MS
        val rs = runs.filter { it.at >= since }
        val total = rs.sumOf { it.total }
        return if (total == 0) null else rs.sumOf { it.right } * 100 / total
    }

    fun logRun(total: Int, right: Int, seconds: Int) {
        runs.add(QuizRun(System.currentTimeMillis(), total, right, seconds))
        while (runs.size > 200) runs.removeAt(0)
        val a = JSONArray()
        runs.forEach { a.put(JSONObject().put("t", it.at).put("n", it.total).put("r", it.right).put("s", it.seconds)) }
        runCatching { write(runsFile, a) }
    }

    /** Days in a row (ending today or yesterday) with at least one finished quiz. */
    fun quizStreak(): Int {
        val dayOf = { ms: Long -> (ms + java.util.TimeZone.getDefault().getOffset(ms)) / 86_400_000L }
        val set = runs.map { dayOf(it.at) }.toSet()
        val today = dayOf(System.currentTimeMillis())
        var d = if (today in set) today else today - 1
        var n = 0
        while (d in set) { n++; d-- }
        return n
    }

    private fun readRuns(): List<QuizRun> = runCatching {
        val a = JSONArray(runsFile.readText())
        List(a.length()) { a.getJSONObject(it).let { o -> QuizRun(o.getLong("t"), o.getInt("n"), o.getInt("r"), o.optInt("s")) } }
    }.getOrDefault(emptyList())

    fun isDue(c: Card): Boolean = c.due <= System.currentTimeMillis()

    // ----- Saving -----

    private fun saveBooks() {
        val a = JSONArray()
        books.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("pages", it.pages).put("added", it.added).put("last", it.lastPage).put("exam", it.exam))
        }
        write(booksFile, a)
    }

    private fun saveCards() {
        val a = JSONArray()
        cards.forEach {
            a.put(
                JSONObject().put("id", it.id).put("book", it.book).put("q", it.question).put("a", it.answer)
                    .put("page", it.page).put("box", it.box).put("due", it.due).put("right", it.right).put("wrong", it.wrong).put("last", it.last)
                    .apply { if (it.image.isNotEmpty()) put("img", it.image) },
            )
        }
        write(cardsFile, a)
    }

    private fun write(target: File, array: JSONArray) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(target)
    }

    private fun readBooks(): List<Textbook> = runCatching {
        val a = JSONArray(booksFile.readText())
        List(a.length()) {
            val o = a.getJSONObject(it)
            Textbook(o.getString("id"), o.getString("title"), o.getInt("pages"), o.optLong("added"), o.optInt("last"), o.optLong("exam"))
        }.filter { pdfFile(it.id).exists() }
    }.getOrDefault(emptyList())

    private fun readCards(): List<Card> = runCatching {
        val a = JSONArray(cardsFile.readText())
        List(a.length()) {
            val o = a.getJSONObject(it)
            Card(
                o.getString("id"), o.optString("book"), o.getString("q"), o.getString("a"),
                o.optInt("page"), o.optInt("box"), o.optLong("due"), o.optInt("right"), o.optInt("wrong"), o.optLong("last"), o.optString("img"),
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Days until a card comes back, by box: now, tomorrow, 3 days, a week, 2 weeks, a month. */
        private val Days = longArrayOf(0, 1, 3, 7, 14, 30)

        /** From this box up a card counts as learned. */
        const val MasteredBox = 3
    }
}

/** Draws page [index] of the PDF onto a white bitmap [widthPx] wide. Not safe to call from two threads at once. */
internal fun renderPage(renderer: PdfRenderer, index: Int, widthPx: Int): Bitmap {
    val page = renderer.openPage(index)
    try {
        val height = (widthPx.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        // A PDF page has no background of its own, so paint it white first.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    } finally {
        page.close()
    }
}
